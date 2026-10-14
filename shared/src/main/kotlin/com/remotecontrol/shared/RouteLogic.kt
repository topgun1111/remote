package com.remotecontrol.shared

/**
 * Session 9a: pure routing logic for reaching an Agent from the home Wi-Fi or from outside (Tailscale VPN address).
 * No Android types, so it is unit-tested on the JVM (`RouteLogicTest`).
 */
enum class RouteKind { LAN, REMOTE }

/** One address to dial. [connectTimeoutMs] differs per route (a wrong guess on the LAN must fail fast). */
data class Route(val kind: RouteKind, val host: String, val port: Int, val connectTimeoutMs: Long)

enum class AddressClass { INVALID, UNSPECIFIED, LOOPBACK, LINK_LOCAL, MULTICAST, PRIVATE_LAN, CGNAT, PUBLIC }

/** An IPv4 address of this phone with its network prefix length (e.g. 192.168.1.5/24). */
data class LocalAddr(val ip: String, val prefix: Int)

object RouteLogic {
    const val DEFAULT_PORT = 8765

    const val LAN_FIRST_TIMEOUT_MS = 4_000L
    const val LAN_ONLY_TIMEOUT_MS = 5_000L
    const val LAN_FALLBACK_TIMEOUT_MS = 3_000L
    const val REMOTE_TIMEOUT_MS = 8_000L

    /** Strict dotted-quad IPv4 -> unsigned 32-bit value, or null (no leading zeros, no spaces, no IPv6). */
    fun parseIpv4(s: String): Long? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            val n = p.toInt()
            if (n > 255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    private fun inRange(v: Long, base: String, prefix: Int): Boolean {
        val b = parseIpv4(base)!!
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (v and mask) == (b and mask)
    }

    fun classify(ip: String): AddressClass {
        val v = parseIpv4(ip) ?: return AddressClass.INVALID
        return when {
            inRange(v, "0.0.0.0", 8) -> AddressClass.UNSPECIFIED
            inRange(v, "127.0.0.0", 8) -> AddressClass.LOOPBACK
            inRange(v, "169.254.0.0", 16) -> AddressClass.LINK_LOCAL
            inRange(v, "224.0.0.0", 4) || inRange(v, "240.0.0.0", 4) -> AddressClass.MULTICAST
            inRange(v, "10.0.0.0", 8) || inRange(v, "172.16.0.0", 12) || inRange(v, "192.168.0.0", 16) -> AddressClass.PRIVATE_LAN
            // 100.64.0.0/10: Tailscale addresses, but also carrier-grade NAT on mobile data (the Agent tells them apart).
            inRange(v, "100.64.0.0", 10) -> AddressClass.CGNAT
            else -> AddressClass.PUBLIC
        }
    }

    /** Can this address be dialled or reported at all (not loopback, link-local, multicast, unspecified, invalid)? */
    fun usable(c: AddressClass): Boolean = c == AddressClass.PRIVATE_LAN || c == AddressClass.CGNAT || c == AddressClass.PUBLIC

    /** True when [ip] is inside the subnet of one of this phone's addresses (so a LAN route can plausibly work). */
    fun onSameSubnet(ip: String, local: List<LocalAddr>): Boolean {
        val v = parseIpv4(ip) ?: return false
        return local.any { l ->
            val base = parseIpv4(l.ip) ?: return@any false
            l.prefix in 1..32 && inRange(v, l.ip, l.prefix) && base != 0L
        }
    }

    private val HOST_RE = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

    /**
     * Parses what a person types: `host`, `host:port`, tolerating a `ws://` / `wss://` prefix and a trailing path.
     * Host = IPv4 or a name (MagicDNS); IPv6 is rejected; port 1..65535, default [DEFAULT_PORT].
     */
    fun parseEndpoint(raw: String): Pair<String, Int>? {
        var s = raw.trim()
        for (p in listOf("wss://", "ws://")) if (s.startsWith(p, ignoreCase = true)) { s = s.substring(p.length); break }
        s = s.substringBefore('/').substringBefore('?').trim()
        if (s.isEmpty() || s.contains('[') || s.contains(']')) return null
        val colons = s.count { it == ':' }
        if (colons > 1) return null
        val host: String
        var port = DEFAULT_PORT
        if (colons == 1) {
            host = s.substringBefore(':')
            val ps = s.substringAfter(':')
            if (ps.isEmpty() || !ps.all { it in '0'..'9' } || ps.length > 5) return null
            port = ps.toInt()
            if (port !in 1..65535) return null
        } else host = s
        if (host.isEmpty()) return null
        val looksNumeric = host.all { it in '0'..'9' || it == '.' }
        if (looksNumeric) {
            if (parseIpv4(host) == null || !usable(classify(host))) return null
        } else if (!HOST_RE.matches(host)) return null
        return host.lowercase() to port
    }

    /** Normalised text of an endpoint: `host`, or `host:port` when the port is not the default. */
    fun formatEndpoint(host: String, port: Int): String = if (port == DEFAULT_PORT) host else "$host:$port"

    /**
     * Ordered routes for one connection cycle. [lanHost]/[lanPort] = the address the device was paired with, [remote] =
     * the stored remote endpoint (or null), [local] = this phone's own Wi-Fi/Ethernet addresses (VPN excluded).
     *  - LAN host on one of our subnets: LAN first (short timeout), then remote.
     *  - otherwise (other network, mobile data): remote first, then LAN as a last resort.
     *  - no remote address: only the LAN route.
     */
    fun plan(lanHost: String, lanPort: Int, remote: Pair<String, Int>?, local: List<LocalAddr>): List<Route> {
        val lanOk = lanHost.isNotBlank()
        val remoteDistinct = remote != null && !(lanOk && remote.first.equals(lanHost, true) && remote.second == lanPort)
        if (!remoteDistinct) {
            return if (lanOk) listOf(Route(RouteKind.LAN, lanHost, lanPort, LAN_ONLY_TIMEOUT_MS)) else
                if (remote != null) listOf(Route(RouteKind.REMOTE, remote.first, remote.second, REMOTE_TIMEOUT_MS)) else emptyList()
        }
        val rem = Route(RouteKind.REMOTE, remote!!.first, remote.second, REMOTE_TIMEOUT_MS)
        if (!lanOk) return listOf(rem)
        return if (onSameSubnet(lanHost, local))
            listOf(Route(RouteKind.LAN, lanHost, lanPort, LAN_FIRST_TIMEOUT_MS), rem)
        else
            listOf(rem, Route(RouteKind.LAN, lanHost, lanPort, LAN_FALLBACK_TIMEOUT_MS))
    }

    fun describe(r: Route): String = when (r.kind) {
        RouteKind.LAN -> "Local network ${r.host}:${r.port}"
        RouteKind.REMOTE -> "Remote (VPN address ${r.host}:${r.port})"
    }
}

/**
 * Walks the routes of one connection cycle: [current] is dialled, [failed] moves to the next (null = all failed),
 * [succeeded] records the route that worked in [connected].
 */
class RouteSession(private val routes: List<Route>) {
    private var index = 0

    var connected: Route? = null
        private set

    fun current(): Route? = routes.getOrNull(index)

    fun failed(): Route? {
        connected = null
        index++
        return current()
    }

    fun succeeded(): Route? {
        connected = current()
        return connected
    }
}
