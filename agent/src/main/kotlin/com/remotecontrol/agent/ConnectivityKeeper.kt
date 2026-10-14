package com.remotecontrol.agent

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the Agent reachable while the screen is off (Session 7): a PARTIAL_WAKE_LOCK keeps the CPU (and the
 * WebSocket server) running, a Wi-Fi lock asks the radio not to sleep. Without this a Controller usually cannot
 * connect to a sleeping phone to send `WAKE`. Costs battery: held only while `AgentService` runs.
 * Best effort: aggressive OEM battery managers may still freeze the app (exempt it from battery optimization).
 */
class ConnectivityKeeper(context: Context) {
    private val app = context.applicationContext
    private val cpu = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteControl:agent").apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION") // FULL_HIGH_PERF is deprecated on recent Android but still the right request on older ones.
    private val wifi = app.getSystemService(Context.WIFI_SERVICE).let { it as WifiManager }
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteControl:agent").apply { setReferenceCounted(false) }

    fun acquire() {
        try {
            if (!cpu.isHeld) cpu.acquire()
            if (!wifi.isHeld) wifi.acquire()
            Log.i("RemoteAgent", "Keep-reachable locks held")
        } catch (e: Exception) {
            Log.w("RemoteAgent", "Could not hold locks: ${e.message}")
        }
    }

    fun release() {
        try {
            if (cpu.isHeld) cpu.release()
            if (wifi.isHeld) wifi.release()
        } catch (e: Exception) {
            Log.w("RemoteAgent", "Could not release locks: ${e.message}")
        }
    }
}
