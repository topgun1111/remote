package com.remotecontrol.shared

import java.util.Locale

enum class LaunchStep { START_ACTIVITY, POST_NOTIFICATION }

/**
 * Pure logic for the app list and `LAUNCH_APP` (Session 8, part 1), so the rules are unit-tested.
 *
 * Android 10+ restricts starting activities from the background and reports nothing when it blocks one. The Agent
 * is exempt when the user granted "Display over other apps" (SYSTEM_ALERT_WINDOW); otherwise it also posts a
 * notification that opens the app (full-screen intent, which can show it even with the screen off) as a fallback.
 */
object AppsLogic {
    private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun isValidPackage(pkg: String) = pkg.length <= 255 && PACKAGE_RE.matches(pkg)

    /** Lower-case, drops Turkish dotted/dotless i differences so "ışık", "Isik" and "İŞİK" all match "isik". */
    fun fold(s: String): String =
        s.lowercase(Locale.ROOT).replace("\u0307", "").replace('ı', 'i')

    /** Search over label and package name, label-sorted (case-insensitive). Empty [query] = everything. */
    fun filter(apps: List<AppEntry>, query: String, hideSystem: Boolean): List<AppEntry> {
        val q = fold(query.trim())
        return apps.asSequence()
            .filter { !(hideSystem && it.system) }
            .filter { q.isEmpty() || fold(it.label).contains(q) || fold(it.pkg).contains(q) }
            .sortedWith(compareBy<AppEntry> { fold(it.label) }.thenBy { it.pkg })
            .toList()
    }

    fun plan(installed: Boolean, hasLauncherIntent: Boolean, canDrawOverlays: Boolean, sdk: Int): List<LaunchStep> {
        if (!installed || !hasLauncherIntent) return emptyList()
        val exempt = sdk < 29 || canDrawOverlays
        return if (exempt) listOf(LaunchStep.START_ACTIVITY)
        else listOf(LaunchStep.START_ACTIVITY, LaunchStep.POST_NOTIFICATION)
    }

    /**
     * @param startError null if `startActivity` did not throw, else its message
     * @param notified   the fallback notification was posted
     */
    fun outcome(
        pkg: String, label: String, installed: Boolean, hasLauncherIntent: Boolean,
        startAttempted: Boolean, startError: String?, notified: Boolean, canDrawOverlays: Boolean,
    ): LaunchResult {
        fun r(ok: Boolean, via: String, detail: String) = LaunchResult(ok, pkg, label.ifBlank { pkg }, via, detail)
        val name = label.ifBlank { pkg }
        return when {
            !isValidPackage(pkg) -> r(false, "NONE", "Invalid package name.")
            !installed -> r(false, "NONE", "$name is not installed on the Agent (refresh the list).")
            !hasLauncherIntent -> r(false, "NONE", "$name has no launchable screen.")
            startAttempted && startError == null && notified -> r(
                true, "DIRECT+NOTIFICATION",
                "Start requested for $name. Android may block background starts: a notification was also posted " +
                    "(tap it on the Agent if the app did not open). Grant 'Display over other apps' in RC Launch Setup to avoid this.",
            )
            startAttempted && startError == null -> r(
                true, "DIRECT",
                "Start requested for $name. Check the live view" +
                    if (canDrawOverlays) "." else " (if nothing opened, Android blocked it).",
            )
            notified -> r(
                true, "NOTIFICATION",
                "Could not start $name directly (${startError ?: "blocked"}); a notification was posted, tap it on the Agent.",
            )
            else -> r(false, "NONE", "Could not start $name: ${startError ?: "unknown error"}.")
        }
    }
}
