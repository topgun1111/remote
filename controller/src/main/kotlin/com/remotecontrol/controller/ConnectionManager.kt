package com.remotecontrol.controller

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.remotecontrol.shared.CaptureConfig
import com.remotecontrol.shared.FailureClassifier
import com.remotecontrol.shared.Hello
import com.remotecontrol.shared.HelloAck
import com.remotecontrol.shared.Message
import com.remotecontrol.shared.Protocol
import com.remotecontrol.shared.ReconnectPolicy
import com.remotecontrol.shared.Route
import com.remotecontrol.shared.ScreenFrame
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import kotlin.random.Random

/**
 * Owns the single connection to an Agent: pairing with a PIN, authenticating with a stored token, and reconnecting.
 *
 * What it combines (Sessions 2 to 10b):
 *  - routes (9a/9b): the local address and the remote/VPN address are tried in the order `RouteLogic.plan` gives; only
 *    transport failures move to the next route, a rejected handshake is terminal.
 *  - encryption (10b): `wss://` with a pinned certificate (`SecureDial`); plain `ws://` only as the documented fallback.
 *  - reconnect (10a): backoff with jitter (`ReconnectPolicy`), a network change retries within about a second
 *    (`NetworkWatcher`), readable failure texts (`FailureClassifier`).
 *  - raw-JSON side channels answered before `Protocol.decode`: wake, apps, files/icons/addresses (`WakeHub`, `AppsHub`, `FilesHub`).
 *  - the viewer-visible report (`ViewerStateClient`) and the capture defaults applied after each handshake.
 *
 * Threading: all public methods and all [Listener] callbacks (except [Listener.onFrame]) run on the main thread; OkHttp
 * callbacks are re-posted to the main thread and discarded if they belong to a superseded connection attempt.
 */
class ConnectionManager(
    context: Context,
    private val store: DeviceStore,
    private val listener: Listener,
) {
    enum class State { IDLE, CONNECTING, CONNECTED, RETRYING, REJECTED }

    interface Listener {
        fun onState(state: State, detail: String)
        fun onPaired(device: PairedDevice)
        fun onMessage(message: Message) {}

        /** Screen frames skip the main thread: this is called on the network thread, so it must be cheap and thread-safe. */
        fun onFrame(frame: ScreenFrame) {}

        /** Informational line for the log (encryption state, saved remote address, failure reason). */
        fun onInfo(text: String) {}
    }

    private data class Target(
        val host: String,
        val port: Int,
        val pin: String? = null,          // set only while pairing
        val device: PairedDevice? = null, // set once paired / when reconnecting to a known device
    )

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val baseClient = OkHttpClient.Builder().build() // timeouts and ping interval are set per route (RouteConnector.clientFor)

    val routeStore = RouteStore(app)
    val settings = SettingsStore(app)
    private val routes = RouteConnector(app, routeStore)
    private val secure = SecureDial(settings)
    private val addresses = AddressesClient({ send(it) }, routeStore).also { it.start() }
    private val viewerState = ViewerStateClient { send(it) }
    private val net = NetworkWatcher(app) { online -> onNetworkChange(online) }.also { it.start() }

    private var target: Target? = null
    private var socket: WebSocket? = null
    private var dialAttempt: DialAttempt? = null
    @Volatile private var generation = 0
    @Volatile private var authenticated = false
    private var attempt = 0
    private var connectedAt = 0L
    private var wantConnected = false
    private var state = State.IDLE
    private var tlsBadge = ""

    private val retryRunnable = Runnable { dialFirst(routes.restart()) }

    val currentState: State get() = state
    val connectedDeviceId: String? get() = if (state == State.CONNECTED) target?.device?.id else null

    /** `LAN`/`VPN` and `TLS`/`PLAIN` of the current link, e.g. `LAN · TLS`; empty when not connected. */
    fun badge(): String =
        if (state != State.CONNECTED) "" else listOf(routes.badge(), tlsBadge).filter { it.isNotEmpty() }.joinToString(" · ")

    /** First-time pairing: connect with the PIN shown on the Agent. Not retried automatically. */
    fun pair(host: String, port: Int, pin: String) {
        begin(Target(host, port, pin = pin))
    }

    /** Connect to an already-paired Agent using its stored token; retries automatically on drops. */
    fun connect(device: PairedDevice) {
        store.setLastUsed(device.id)
        begin(Target(device.host, device.port, device = device))
    }

    fun disconnect() {
        wantConnected = false
        teardown()
        setState(State.IDLE, "Disconnected")
    }

    /** True when the current (or retrying) connection cycle is aimed at [device]. */
    fun targets(device: PairedDevice): Boolean =
        target?.let { it.host == device.host && it.port == device.port } == true

    /** Forget a paired device completely: connection, token, remote address, last route and certificate pin. */
    fun forget(device: PairedDevice) {
        if (targets(device)) disconnect()
        store.remove(device.id)
        routeStore.forget(device.key)
        settings.forget(device.key)
    }

    /** Stops everything, including the network watcher. Call from `Activity.onDestroy`. */
    fun shutdown() {
        wantConnected = false
        teardown()
        net.stop()
        viewerState.stop()
        addresses.stop()
    }

    /** Sends one JSON text frame. True only while the link is up and authenticated (what `WakeBar`, panels and input need). */
    fun send(json: String): Boolean = authenticated && (socket?.send(json) ?: false)

    fun send(message: Message): Boolean = send(Protocol.encode(message))

    /** Tell the Agent whether the live view is on screen, so it stops sending frames while hidden (10b). Main thread. */
    fun setViewerVisible(visible: Boolean) = viewerState.setVisible(visible)

    // ------------------------------------------------------------------ connection cycle

    private fun begin(t: Target) {
        teardown()
        target = t
        wantConnected = true
        attempt = 0
        dialFirst(routes.begin(t.host, t.port))
    }

    private fun teardown() {
        generation++
        main.removeCallbacks(retryRunnable)
        socket?.close(1000, "bye")
        socket = null
        authenticated = false
        connectedAt = 0L
    }

    /** Start of a connection cycle (first connect, retry after backoff or after a network change). */
    private fun dialFirst(route: Route?) {
        if (!wantConnected || target == null) return
        if (route == null) {
            wantConnected = false
            setState(State.IDLE, "No usable address. Check the device's remote address and the 'LAN only' setting.")
            return
        }
        dialAttempt = secure.begin(routes.key())
        dial(route)
    }

    private fun dial(r: Route) {
        val t = target ?: return
        val a = dialAttempt ?: secure.begin(routes.key()).also { dialAttempt = it }
        val gen = ++generation
        authenticated = false
        setState(
            State.CONNECTING,
            (if (t.pin != null) "Pairing with " else "Connecting to ") + (t.device?.name ?: t.host) + " · " + routes.describeCurrent(),
        )
        val client = secure.client(baseClient, r, a)
        val request = Request.Builder().url(secure.url(r, a)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = Hello(
                    deviceName = Build.MODEL ?: "Controller",
                    pin = t.pin,
                    token = t.device?.token,
                )
                webSocket.send(Protocol.encode(hello))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                // Fast path for the bulk of the traffic: screen frames.
                if (text.startsWith(FRAME_PREFIX)) {
                    val f = try { Protocol.decode(text) as? ScreenFrame } catch (e: Exception) { null }
                    if (f != null) { listener.onFrame(f); WakeHub.onFrame() }
                    return
                }
                // Raw-JSON side channels, before Protocol.decode (they may run on this thread; they post to the main thread themselves).
                if (WakeHub.onRawMessage(text)) return
                if (AppsHub.onRawMessage(text)) return
                if (FilesHub.onRawMessage(text)) return
                val msg = try { Protocol.decode(text) } catch (e: Exception) {
                    Log.w(TAG, "Bad message: ${e.message}"); return
                }
                if (msg is ScreenFrame) { listener.onFrame(msg); WakeHub.onFrame(); return }
                main.post { if (gen == generation) handle(msg) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (gen == generation) transportLost(null) }
            }

            override fun onFailure(webSocket: WebSocket, t2: Throwable, response: Response?) {
                main.post { if (gen == generation) transportLost(t2) }
            }
        })
    }

    private fun handle(msg: Message) {
        if (msg !is HelloAck) {
            listener.onMessage(msg)
            return
        }
        val t = target ?: return
        if (!msg.accepted) {
            // Retrying with the same credentials can't succeed, so stop and let the user decide.
            wantConnected = false
            teardown()
            Log.i(TAG, "Rejected: ${msg.error}")
            setState(State.REJECTED, msg.error ?: "Rejected by Agent")
            return
        }
        authenticated = true
        attempt = 0
        connectedAt = SystemClock.elapsedRealtime()
        routes.succeeded()
        if (t.pin != null && msg.token != null) {
            val device = PairedDevice(UUID.randomUUID().toString(), msg.deviceName, t.host, t.port, msg.token!!)
            store.save(device)
            target = Target(t.host, t.port, device = device) // from now on authenticate by token
            Log.i(TAG, "Paired with '${device.name}'")
            listener.onPaired(device)
        }
        Log.i(TAG, "Handshake OK with '${msg.deviceName}'")

        val a = dialAttempt
        val firstUse = a != null && a.secure && settings.pin(a.key) == null
        val encrypted = a != null && secure.accepted(a)
        tlsBadge = if (a != null) secure.badge(a, firstUse) else ""
        listener.onInfo(if (encrypted) "Encrypted (TLS)" else "WARNING: connection is NOT encrypted")

        setState(State.CONNECTED, "Connected to ${msg.deviceName} · ${routes.describeConnected()}")

        viewerState.onConnected()
        if (settings.applyCaptureOnConnect) {
            val d = settings.captureDefaults()
            send(CaptureConfig(fps = d.fps, quality = d.quality, maxDimension = d.maxDimension))
        }
        addresses.request(routes.key()) { o ->
            if (o.applied) listener.onInfo("Remote address saved from the Agent: ${o.suggestion!!.first}")
        }
    }

    /**
     * Transport failure or close, before or after the handshake (not a rejected handshake). Order: TLS decision (plain
     * fallback / terminal pin problem) -> next route -> backoff.
     */
    private fun transportLost(t: Throwable?) {
        socket = null
        val wasUp = authenticated
        authenticated = false
        val tgt = target
        val remote = routes.remoteLikely()            // before failed(), which moves to the next route

        val a = dialAttempt
        if (!wasUp && a != null) {
            when (val o = secure.onFailure(a, t, remote)) {
                is SecureDial.Outcome.Retry -> {
                    val r = routes.current()
                    if (r != null) {
                        listener.onInfo("No TLS answer from this Agent, trying without encryption")
                        dialAttempt = o.attempt
                        dial(r)
                        return
                    }
                }
                is SecureDial.Outcome.Terminal -> {
                    wantConnected = false
                    teardown()
                    setState(State.REJECTED, o.text)
                    return
                }
                SecureDial.Outcome.Normal -> {}
            }
        }

        val f = FailureClassifier.classify(t, remote)
        listener.onInfo(f.text)
        if (!wantConnected || tgt == null || tgt.device == null) {
            // A failed pairing attempt (or a manual disconnect) is not retried.
            wantConnected = false
            setState(State.IDLE, f.text)
            return
        }
        if (!f.retry) {
            wantConnected = false
            setState(State.IDLE, f.text)
            return
        }
        if (!wasUp) {
            val next = routes.failed()
            if (next != null) {
                dialAttempt = secure.begin(routes.key())
                dial(next)
                return
            }
        }
        // All routes failed in this cycle, or an established link dropped: back off, then plan again.
        val up = if (connectedAt > 0) SystemClock.elapsedRealtime() - connectedAt else 0L
        connectedAt = 0L
        val idx = ReconnectPolicy.attemptAfterDrop(attempt, up)
        val delay = ReconnectPolicy.delayMs(idx, remote, Random.nextDouble())
        attempt = idx + 1
        setState(State.RETRYING, "${f.text} Retrying in ${(delay + 500) / 1000}s")
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, delay)
    }

    /** Wi-Fi <-> mobile data, VPN on/off, network lost or back (10a). */
    private fun onNetworkChange(online: Boolean) {
        val t = target ?: return
        if (!wantConnected || t.device == null) return     // pairing attempts and manual disconnects are left alone
        generation++                                       // callbacks of the old socket are discarded
        main.removeCallbacks(retryRunnable)
        socket?.cancel()
        socket = null
        authenticated = false
        connectedAt = 0L
        if (!online) {
            setState(State.RETRYING, "No network")
            return
        }
        attempt = 0
        setState(State.RETRYING, "Network changed, reconnecting…")
        main.postDelayed(retryRunnable, ReconnectPolicy.afterNetworkChange())
    }

    private fun setState(s: State, detail: String) {
        state = s
        if (s != State.CONNECTED) tlsBadge = ""
        listener.onState(s, detail)
    }

    private companion object {
        const val TAG = "RemoteController"
        const val FRAME_PREFIX = "{\"type\":\"SCREEN_FRAME\""
    }
}
