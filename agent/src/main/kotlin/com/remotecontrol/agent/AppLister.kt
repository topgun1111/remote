package com.remotecontrol.agent

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.remotecontrol.shared.AppEntry

/**
 * Lists the apps that have a launcher icon (Session 8, part 1). Uses a MAIN/LAUNCHER intent query, which works
 * on Android 11+ through the `<queries>` entry in the manifest (no QUERY_ALL_PACKAGES permission needed).
 */
class AppLister(context: Context) {
    private val app = context.applicationContext
    private val pm: PackageManager = app.packageManager

    private fun launcherQuery() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** Blocking (loads one label per app, typically 100-400 ms): call from a background thread. */
    fun list(): List<AppEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (ri in pm.queryIntentActivities(launcherQuery(), 0)) {
            val info = ri.activityInfo ?: continue
            val pkg = info.packageName
            if (!seen.add(pkg)) continue
            val flags = info.applicationInfo?.flags ?: 0
            // "system" = preinstalled and never updated by the user; an updated Chrome/YouTube counts as a normal app.
            val system = flags and ApplicationInfo.FLAG_SYSTEM != 0 && flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            out += AppEntry(pkg, label.ifBlank { pkg }, system)
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun isInstalled(pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Launch intent for [pkg], or null if it has no launchable activity (or is not visible to us). */
    fun launchIntent(pkg: String): Intent? = pm.getLaunchIntentForPackage(pkg)

    fun label(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
