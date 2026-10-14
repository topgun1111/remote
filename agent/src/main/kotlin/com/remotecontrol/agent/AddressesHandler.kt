package com.remotecontrol.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.remotecontrol.shared.AddressesResult
import com.remotecontrol.shared.AddressesWire
import com.remotecontrol.shared.AgentAddress
import com.remotecontrol.shared.AddressClass
import com.remotecontrol.shared.RouteLogic
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Answers `ADDRESSES` (Session 9b) with the Agent's own IPv4 addresses, VPN ones first. Created and called by
 * [FilesHandler] (like the icon handler), so `AgentServer` needs no extra hook. Cheap: runs inline on the caller's thread.
 *
 * VPN addresses come from Android's VPN networks (`TRANSPORT_VPN`), not from the 100.64.0.0/10 range, because mobile
 * carriers also hand out addresses from that range. Normal interfaces contribute `LAN` (private IPv4) or `OTHER`;
 * interfaces that belong to a VPN network are not counted twice. Needs `ACCESS_NETWORK_STATE`; without it (or on any
 * error) only the plain interface scan is used and `vpn` is false.
 */
class AddressesHandler(context: Context) {
    private val app = context.applicationContext

    /** True (answered via [reply]) if [raw] is an `ADDRESSES` request. */
    fun tryHandle(raw: String, reply: (String) -> Unit): Boolean {
        if (!AddressesWire.isRequest(raw)) return false
        val r = snapshot(app)
        Log.i(TAG, "ADDRESSES -> ${r.addrs.joinToString { it.kind + ":" + it.ip }} port=${r.port}")
        reply(AddressesWire.result(r))
        return true
    }

    companion object {
        private const val TAG = "RemoteAgent"

        /** Port `AgentServer` listens on. Change it here if the server port is ever made configurable. */
        @Volatile var listenPort: Int = RouteLogic.DEFAULT_PORT

        @Suppress("DEPRECATION")
        fun snapshot(context: Context): AddressesResult {
            val vpn = LinkedHashMap<String, String>()          // ip -> interface name
            val vpnIfaces = HashSet<String>()
            try {
                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    val lp = cm.getLinkProperties(n) ?: continue
                    val name = lp.interfaceName ?: ""
                    if (name.isNotEmpty()) vpnIfaces.add(name)
                    for (la in lp.linkAddresses) {
                        val a = la.address
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            val ip = a.hostAddress ?: continue
                            if (RouteLogic.usable(RouteLogic.classify(ip))) vpn[ip] = name
                        }
                    }
                }
            } catch (_: Exception) {
            }

            val out = ArrayList<AgentAddress>()
            for ((ip, name) in vpn) out.add(AgentAddress(ip, name, AddressesWire.KIND_VPN))
            val seen = HashSet<String>(vpn.keys)
            try {
                val ifs = NetworkInterface.getNetworkInterfaces()
                while (ifs != null && ifs.hasMoreElements()) {
                    val ni = ifs.nextElement()
                    if (!ni.isUp || ni.isLoopback || ni.name in vpnIfaces) continue
                    for (ia in ni.interfaceAddresses) {
                        val a = ia.address
                        if (a !is Inet4Address) continue
                        val ip = a.hostAddress ?: continue
                        if (!RouteLogic.usable(RouteLogic.classify(ip)) || !seen.add(ip)) continue
                        val kind = if (RouteLogic.classify(ip) == AddressClass.PRIVATE_LAN) AddressesWire.KIND_LAN else AddressesWire.KIND_OTHER
                        out.add(AgentAddress(ip, ni.name, kind))
                    }
                }
            } catch (_: Exception) {
            }
            return AddressesResult(listenPort, vpn.isNotEmpty(), out.take(AddressesWire.MAX_ADDRS))
        }
    }
}
