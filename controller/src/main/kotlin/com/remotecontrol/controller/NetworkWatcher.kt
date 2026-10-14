package com.remotecontrol.controller

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * Session 10a: reports changes of the default network (Wi-Fi <-> mobile data, VPN on/off, network lost/back) to
 * [onChange] on the main thread: `online=false` when there is no network, `online=true` when a (new) network is
 * available. Bursts are merged (debounce [DEBOUNCE_MS]); the state present at [start] is not reported.
 */
class NetworkWatcher(context: Context, private val onChange: (online: Boolean) -> Unit) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registered = false

    // Touched on the main thread only.
    private var current: Network? = null
    private var reported: Network? = null
    private var initialised = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { current = network; schedule() } }
        override fun onLost(network: Network) { main.post { if (current == network) { current = null; schedule() } } }
    }

    private val fire = Runnable {
        if (current != reported) {
            reported = current
            onChange(current != null)
        }
    }

    private fun schedule() {
        if (!initialised) { initialised = true; reported = current; return }   // first callback = the state at start()
        main.removeCallbacks(fire)
        main.postDelayed(fire, DEBOUNCE_MS)
    }

    fun start() {
        if (registered) return
        try {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: Exception) { /* no connectivity info: the reconnect timer still works */ }
    }

    fun stop() {
        main.removeCallbacks(fire)
        if (registered) try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        registered = false
    }

    private companion object { const val DEBOUNCE_MS = 400L }
}
