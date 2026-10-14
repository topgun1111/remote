package com.remotecontrol.agent

import android.content.Context

/**
 * Session 10a: "start at boot" state in its own prefs `agent_autostart`.
 *  - [enabled]: the switch in RC Reliability Setup (default on).
 *  - [wasRunning]: the Agent was running when the phone shut down / the app was updated. Set by `AgentService` when it
 *    starts, cleared ONLY when the person stops the Agent on purpose (never in `onDestroy`: the system killing the
 *    service at reboot must not count as "stopped on purpose").
 *  - [lastBoot]: what the last boot/update attempt did, shown in RC Reliability Setup.
 */
object AutoStart {
    private const val PREFS = "agent_autostart"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = p(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, on: Boolean) { p(c).edit().putBoolean("enabled", on).apply() }

    fun markRunning(c: Context, running: Boolean) { p(c).edit().putBoolean("running", running).apply() }
    fun wasRunning(c: Context): Boolean = p(c).getBoolean("running", false)

    /** True when a boot / app update should bring the Agent back. */
    fun shouldStart(c: Context): Boolean = enabled(c) && wasRunning(c)

    fun recordBoot(c: Context, outcome: String) {
        p(c).edit().putString("lastBoot", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + outcome).apply()
    }

    fun lastBoot(c: Context): String = p(c).getString("lastBoot", null) ?: "No boot or update seen yet"
}
