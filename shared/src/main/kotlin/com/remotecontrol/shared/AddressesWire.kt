package com.remotecontrol.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Session 9b wire format: the Agent tells the Controller which addresses it can be reached at, so the Controller can
 * fill in the remote (VPN) address by itself. Handled before `Protocol.decode`, `PROTOCOL_VERSION` unchanged.
 *
 * `{"type":"ADDRESSES"}`   Controller -> Agent (send after the handshake was accepted)
 * `{"type":"ADDRESSES_RESULT","port":8765,"vpn":true,"addrs":[{"ip":"192.168.1.20","iface":"wlan0","kind":"LAN"},
 *   {"ip":"100.90.1.2","iface":"tun0","kind":"VPN"}]}`   Agent -> Controller
 *
 * `kind` is decided by the Agent from Android's own network info: `VPN` = address of a VPN network (Tailscale), `LAN` =
 * private IPv4 on a normal interface, `OTHER` = anything else (carrier addresses, public IPs). A carrier-grade-NAT address
 * on mobile data looks like a Tailscale address (100.64.0.0/10), which is why the Agent, not the range, decides.
 */
data class AgentAddress(val ip: String, val iface: String, val kind: String)

data class AddressesResult(val port: Int, val vpnUp: Boolean, val addrs: List<AgentAddress>)

object AddressesWire {
    const val TYPE_REQUEST = "ADDRESSES"
    const val TYPE_RESULT = "ADDRESSES_RESULT"
    const val KIND_LAN = "LAN"
    const val KIND_VPN = "VPN"
    const val KIND_OTHER = "OTHER"
    const val MAX_ADDRS = 16

    fun request(): String = buildJsonObject { put("type", TYPE_REQUEST) }.toString()

    fun result(r: AddressesResult): String = buildJsonObject {
        put("type", TYPE_RESULT)
        put("port", r.port)
        put("vpn", r.vpnUp)
        put("addrs", buildJsonArray {
            for (a in r.addrs.take(MAX_ADDRS)) add(buildJsonObject {
                put("ip", a.ip); put("iface", a.iface); put("kind", a.kind)
            })
        })
    }.toString()

    private fun parseObject(raw: String): JsonObject? =
        try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { null }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** Agent side: true only for an `ADDRESSES` request (an `ADDRESSES_RESULT` does not match). */
    fun isRequest(raw: String): Boolean {
        if (!raw.contains(TYPE_REQUEST)) return false
        return parseObject(raw)?.str("type") == TYPE_REQUEST
    }

    /**
     * Controller side: null for anything that is not an `ADDRESSES_RESULT`. Entries whose address is not a usable plain
     * IPv4 are dropped, unknown kinds become `OTHER`, at most [MAX_ADDRS] entries are kept, port outside 1..65535 -> default.
     */
    fun parseResult(raw: String): AddressesResult? {
        if (!raw.contains(TYPE_RESULT)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_RESULT) return null
        val port = (o["port"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..65535 }?.toInt() ?: RouteLogic.DEFAULT_PORT
        val vpn = (o["vpn"] as? JsonPrimitive)?.booleanOrNull ?: false
        val list = ArrayList<AgentAddress>()
        for (e in (o["addrs"] as? JsonArray) ?: JsonArray(emptyList())) {
            val a = e as? JsonObject ?: continue
            val ip = a.str("ip") ?: continue
            if (RouteLogic.parseIpv4(ip) == null || !RouteLogic.usable(RouteLogic.classify(ip))) continue
            val kind = a.str("kind").let { if (it == KIND_LAN || it == KIND_VPN) it else KIND_OTHER }
            list.add(AgentAddress(ip, a.str("iface") ?: "", kind!!))
            if (list.size >= MAX_ADDRS) break
        }
        return AddressesResult(port, vpn, list)
    }

    /** The endpoint the Controller should use as remote address: the Agent's first VPN address, or null if it has none. */
    fun suggestRemote(r: AddressesResult): Pair<String, Int>? =
        r.addrs.firstOrNull { it.kind == KIND_VPN }?.let { it.ip to r.port }
}
