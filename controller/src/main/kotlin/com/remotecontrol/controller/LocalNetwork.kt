package com.remotecontrol.controller

import android.content.Context
import android.net.ConnectivityManager
import android.net.Inet4Address
import android.net.NetworkCapabilities
import com.remotecontrol.shared.LocalAddr

/**
 * Session 9a: this phone's own IPv4 addresses on Wi-Fi / Ethernet, with prefix lengths, so `RouteLogic.plan` can tell
 * whether the Agent's LAN address is on the network we are on. VPN networks are skipped on purpose: with Tailscale on,
 * `activeNetwork` is the VPN, which says nothing about the Wi-Fi underneath. Mobile data is not a LAN, so it is skipped too.
 */
object LocalNetwork {
    @Suppress("DEPRECATION") // allNetworks is deprecated since API 31 but still the only way to see the Wi-Fi under a VPN.
    fun addresses(context: Context): List<LocalAddr> = try {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val out = ArrayList<LocalAddr>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val props = cm.getLinkProperties(n) ?: continue
            for (la in props.linkAddresses) {
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress) a.hostAddress?.let { out.add(LocalAddr(it, la.prefixLength)) }
            }
        }
        out
    } catch (_: Exception) { emptyList() }
}
