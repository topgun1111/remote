package com.remotecontrol.controller

import android.content.Context
import com.remotecontrol.shared.Route
import com.remotecontrol.shared.RouteKind
import com.remotecontrol.shared.RouteLogic
import com.remotecontrol.shared.RouteSession
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Session 9a: gives `ConnectionManager` the address to dial and walks to the next route after a transport failure
 * (LAN first on the Agent's Wi-Fi, otherwise the remote/VPN address first; see `RouteLogic.plan`). No protocol change.
 *
 * Usage (details in `SESSION9A-INTEGRATION.md`):
 *  - [begin] when a connection cycle starts for a paired device -> first [Route] (null = nothing usable to dial)
 *  - [failed] on a TRANSPORT failure (timeout, refused, unreachable, closed before the handshake finished) -> next route to
 *    dial right now, or null when all routes failed in this cycle: back off as before, then call [restart]
 *  - [succeeded] when the handshake was accepted
 *  - a REJECTED handshake (wrong PIN, revoked token) is not a transport failure: do not call [failed]
 *  - [restart] re-plans from the current network state (also use it when an established link drops)
 * Thread-safe: callbacks arrive on OkHttp threads.
 */
class RouteConnector(context: Context, private val store: RouteStore) {
    private val app = context.applicationContext
    private val settings = SettingsStore(app)
    private var key = ""
    private var lanHost = ""
    private var lanPort = RouteLogic.DEFAULT_PORT
    private var session: RouteSession? = null

    @Synchronized
    fun begin(host: String, port: Int): Route? {
        lanHost = host; lanPort = port; key = store.key(host, port)
        return replan()
    }

    /** New cycle from fresh network state. Returns the first route, or null if the device has no usable address. */
    @Synchronized
    fun restart(): Route? = if (key.isEmpty()) null else replan()

    @Synchronized
    fun current(): Route? = session?.current()

    @Synchronized
    fun failed(): Route? = session?.failed()

    /** Remembers the route that worked (for the status text and for diagnostics). Returns it, or null if there is none. */
    @Synchronized
    fun succeeded(): Route? {
        val r = session?.succeeded() ?: return null
        store.setLastGood(key, r.kind)
        return r
    }

    /** Text for the connection status line, e.g. `Local network 192.168.1.20:8765`; empty when no route is active. */
    @Synchronized
    fun describeConnected(): String = session?.connected?.let { RouteLogic.describe(it) } ?: ""

    /** The route currently being dialled, as text (for the "Connecting ..." status). */
    @Synchronized
    fun describeCurrent(): String = session?.current()?.let { RouteLogic.describe(it) } ?: ""

    /** Device key (`host:port`) of the current cycle, for [RouteStore] and `AddressesClient.request`. Empty before [begin]. */
    @Synchronized
    fun key(): String = key

    /** Short badge for the viewer's top bar: `LAN`, `VPN` or empty when not connected. */
    @Synchronized
    fun badge(): String = when (session?.connected?.kind) {
        RouteKind.LAN -> "LAN"
        RouteKind.REMOTE -> "VPN"
        null -> ""
    }

    /**
     * Session 10a: true when the next backoff should use the remote/VPN cap: the route being dialled (or the last one
     * that worked, when no cycle is active) is the remote one. Call it BEFORE [failed], which moves to the next route.
     */
    @Synchronized
    fun remoteLikely(): Boolean = (session?.current()?.kind ?: store.lastGood(key)) == RouteKind.REMOTE

    /** True when the connection went through the remote/VPN route (for a small status badge). */
    @Synchronized
    fun viaRemote(): Boolean = session?.connected?.kind == RouteKind.REMOTE

    private fun replan(): Route? {
        val all = RouteLogic.plan(lanHost, lanPort, store.remote(key), LocalNetwork.addresses(app))
        // Session 10b: "LAN only" in Settings = never dial the remote/VPN address.
        val routes = if (settings.lanOnly) all.filter { it.kind == RouteKind.LAN } else all
        val s = RouteSession(routes)
        session = s
        return s.current()
    }

    companion object {
        /** An OkHttp client sharing [base]'s pool and settings but with this route's connect timeout and ping interval. */
        fun clientFor(base: OkHttpClient, route: Route): OkHttpClient =
            base.newBuilder()
                .connectTimeout(route.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .pingInterval(if (route.kind == RouteKind.REMOTE) PING_REMOTE_S else PING_LAN_S, TimeUnit.SECONDS)
                .build()

        /** WebSocket ping interval: 10 s on the LAN (as before), 15 s over the VPN (below usual mobile NAT timeouts, less traffic). */
        const val PING_LAN_S = 10L
        const val PING_REMOTE_S = 15L

        /** `ws://host:port` for a route. Since 10b prefer `SecureDial.url(route, attempt)` (wss:// with pinning). */
        fun url(route: Route): String = "ws://${route.host}:${route.port}"
    }
}
