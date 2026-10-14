package com.remotecontrol.agent

import android.content.Context
import android.util.Log
import com.remotecontrol.shared.AuthResult
import com.remotecontrol.shared.CaptureConfig
import com.remotecontrol.shared.CaptureStatus
import com.remotecontrol.shared.Hello
import com.remotecontrol.shared.HelloAck
import com.remotecontrol.shared.PairingAuthority
import com.remotecontrol.shared.Protocol
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.Collections

/**
 * WebSocket server. Every connection must open with a valid HELLO (PIN for first pairing,
 * token afterwards); anything else is dropped. Later sessions add handlers for the
 * post-authentication messages in [handleAuthenticated].
 */
class AgentServer(
    context: Context,
    port: Int,
    private val authority: PairingAuthority,
    private val deviceName: String,
) : WebSocketServer(InetSocketAddress(port)), ScreenCapturer.FrameSink {

    private val authenticated: MutableSet<WebSocket> = Collections.synchronizedSet(mutableSetOf())

    // Post-authentication handlers (Sessions 7, 8, 9b, 10b). FilesHandler also routes APP_ICON, ADDRESSES and VIEWER_STATE.
    private val wakeHandler = WakeHandler(context)
    private val appsHandler = AppsHandler(context)
    private val filesHandler = FilesHandler(context)

    /** Called (any thread) with the number of authenticated Controllers whenever it changes (notification text). */
    @Volatile var onControllersChanged: ((Int) -> Unit)? = null

    init {
        isReuseAddr = true
        connectionLostTimeout = 15 // drop dead peers so a reconnecting Controller isn't blocked
        // Open mode (home use): plain ws://, no certificate. Anyone on the same network can connect (see handleHello).
        // The viewer came back after being hidden: replay the cached frame at once.
        StreamGate.onResume = { ScreenCapturer.current?.requestKeyframe()?.let { sendFrame(it) } }
    }

    private fun notifyControllers() { onControllersChanged?.invoke(authenticated.size) }

    override fun onStart() {
        Log.i(TAG, "Server listening on port $port")
        AgentLog.add("Listening on port $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.i(TAG, "Connection from ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        if (authenticated.remove(conn)) { AgentLog.add("Controller disconnected"); notifyControllers() }
        Log.i(TAG, "Closed ${conn.remoteSocketAddress} code=$code")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Server error", ex)
        AgentLog.add("Error: ${ex.message}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // Input events are parsed by InputCommand (tolerant, never closes the connection on bad input).
        if (conn in authenticated && InputCommand.looksLikeInput(message)) {
            handleInput(message)
            return
        }
        // Raw-JSON messages answered before Protocol.decode (authenticated clients only). `reply` may run on a worker thread.
        if (conn in authenticated) {
            val reply: (String) -> Unit = { r -> try { if (conn.isOpen) conn.send(r) } catch (e: Exception) { Log.w(TAG, "reply failed: ${e.message}") } }
            if (wakeHandler.tryHandle(message, reply)) return
            if (appsHandler.tryHandle(message, reply)) return
            if (filesHandler.tryHandle(message, reply)) return   // FILE_*, APP_ICON, ADDRESSES, VIEWER_STATE
        }
        val msg = try {
            Protocol.decode(message)
        } catch (e: Exception) {
            Log.w(TAG, "Bad message, closing: ${e.message}")
            conn.close(1003, "bad message")
            return
        }

        if (conn !in authenticated) {
            if (msg is Hello) handleHello(conn, msg) else conn.close(1008, "HELLO required")
            return
        }
        handleAuthenticated(conn, msg)
    }

    private fun handleHello(conn: WebSocket, hello: Hello) {
        Log.i(TAG, "HELLO from '${hello.deviceName}' (${if (hello.token != null) "token" else "pin"})")
        // Open mode (home use): every Controller on the network is accepted, no PIN, no token check.
        authenticated.add(conn)
        notifyControllers()
        conn.send(Protocol.encode(HelloAck(true, deviceName, token = "open")))
        Log.i(TAG, "Sent HELLO_ACK accepted=true")
        sendStatus(conn)
        StreamGate.report(true)   // a new link starts with a visible viewer (a stale pause from an earlier link must not stick)
        ScreenCapturer.current?.requestKeyframe()?.let { try { conn.send(it) } catch (_: Exception) {} }
        AgentLog.add("'${hello.deviceName}' connected")
    }

    private fun handleAuthenticated(conn: WebSocket, msg: com.remotecontrol.shared.Message) {
        when (msg) {
            is CaptureConfig -> {
                val c = ScreenCapturer.current
                if (c != null) {
                    c.configure(msg)
                    broadcastStatus()
                    c.requestKeyframe()?.let { sendFrame(it) }
                }
                else sendStatus(conn)
            }
            // INPUT_EVENT is handled in onMessage (Session 5). Later sessions: WAKE, LAUNCH_APP, FILE_*.
            else -> Log.d(TAG, "Unhandled message ${msg::class.simpleName}")
        }
    }

    private var lastInputWarnAt = 0L

    private fun handleInput(json: String) {
        val cmd = InputCommand.parse(json)
        if (cmd == null) {
            Log.w(TAG, "Ignored malformed INPUT_EVENT")
            return
        }
        val svc = InputAccessibilityService.instance
        if (svc == null) {
            // Log at most every 5 s so a Controller streaming touches doesn't flood the log.
            val now = System.currentTimeMillis()
            if (now - lastInputWarnAt > 5000) {
                lastInputWarnAt = now
                AgentLog.add("Input ignored: enable the Agent in Settings > Accessibility")
            }
            return
        }
        svc.execute(cmd)
    }

    private fun clients(): List<WebSocket> = synchronized(authenticated) { authenticated.toList() }

    /** Session 10b: a hidden viewer counts as "nobody is watching" (the capturer keeps its cheap cached frame). */
    override fun hasViewers(): Boolean = authenticated.isNotEmpty() && !StreamGate.paused()

    /** Drops the frame for any client whose socket still has unsent data (latest-frame-wins). */
    override fun sendFrame(json: String) {
        for (c in clients()) {
            if (c.isOpen && !c.hasBufferedData()) {
                try { c.send(json) } catch (e: Exception) { Log.w(TAG, "send failed: ${e.message}") }
            }
        }
    }

    private fun statusJson(): String =
        Protocol.encode(ScreenCapturer.current?.status() ?: CaptureStatus(active = false))

    private fun sendStatus(conn: WebSocket) {
        try { conn.send(statusJson()) } catch (_: Exception) {}
    }

    fun broadcastStatus() {
        val json = statusJson()
        for (c in clients()) if (c.isOpen) try { c.send(json) } catch (_: Exception) {}
    }

    private companion object {
        const val TAG = "RemoteAgent"
    }
}
