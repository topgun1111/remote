package com.remotecontrol.controller

import android.content.Context
import android.content.SharedPreferences
import com.remotecontrol.shared.RouteKind
import com.remotecontrol.shared.RouteLogic

/**
 * Session 9a: per-device remote endpoint and last route that worked, in SharedPreferences `routes`.
 *
 * The device key is `host:port` of the address the device was paired with (the same pair the Controller dials today),
 * built with [key]. The remote endpoint is stored normalised (`host` or `host:port`, see `RouteLogic.formatEndpoint`);
 * anything that does not parse is never stored. Thread-safe (SharedPreferences is).
 */
class RouteStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("routes", Context.MODE_PRIVATE)

    fun key(host: String, port: Int): String = "$host:$port"

    /** The remote endpoint of [key] as `host to port`, or null if none is set. */
    fun remote(key: String): Pair<String, Int>? =
        prefs.getString("remote.$key", null)?.let { RouteLogic.parseEndpoint(it) }

    /** The remote endpoint of [key] as typed back to the person (empty when none). */
    fun remoteText(key: String): String = prefs.getString("remote.$key", null) ?: ""

    /**
     * Saves [raw] as the remote endpoint of [key]; blank clears it (and switches auto-fill off for [key]). [auto] = saved by the Agent's address report, which keeps the auto-fill switch as it is. Returns false (nothing changed) when [raw] is not a
     * usable endpoint. The Agent's port is the default when [raw] has none.
     */
    fun setRemote(key: String, raw: String, auto: Boolean = false): Boolean {
        if (raw.isBlank()) {
            // Cleared by the person: do not auto-fill it again (9b) until they save an address.
            prefs.edit().remove("remote.$key").putBoolean("noauto.$key", true).apply()
            return true
        }
        val ep = RouteLogic.parseEndpoint(raw) ?: return false
        val e = prefs.edit().putString("remote.$key", RouteLogic.formatEndpoint(ep.first, ep.second))
        if (!auto) e.remove("noauto.$key")
        e.apply()
        return true
    }

    /** False after the person cleared the remote address on purpose (Session 9b auto-fill respects that). */
    fun autoFillAllowed(key: String): Boolean = !prefs.getBoolean("noauto.$key", false)

    fun lastGood(key: String): RouteKind? =
        prefs.getString("last.$key", null)?.let { s -> RouteKind.values().firstOrNull { it.name == s } }

    fun setLastGood(key: String, kind: RouteKind) { prefs.edit().putString("last.$key", kind.name).apply() }

    /** Forget everything stored for [key] (call from the Controller's Forget). */
    fun forget(key: String) { prefs.edit().remove("remote.$key").remove("last.$key").remove("noauto.$key").apply() }
}
