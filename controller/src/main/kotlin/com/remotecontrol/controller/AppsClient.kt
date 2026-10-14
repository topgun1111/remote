package com.remotecontrol.controller

import android.os.Handler
import android.os.Looper
import com.remotecontrol.shared.AppEntry
import com.remotecontrol.shared.AppsLogic
import com.remotecontrol.shared.AppsWire
import com.remotecontrol.shared.LaunchResult

/**
 * Requests the Agent's app list and launches apps (Session 8, part 1). All callbacks run on the main thread.
 * The last list is kept in a process-wide cache, so re-opening the panel shows it instantly while a refresh runs.
 *
 * @param send sends one JSON text frame; false when not connected/authenticated (`ConnectionManager.send`).
 */
class AppsClient(
    private val send: (String) -> Boolean,
    private val onList: (List<AppEntry>) -> Unit,
    private val onLaunch: (LaunchResult) -> Unit,
    private val onError: (String) -> Unit,
) : AppsHub.Sink {
    private val main = Handler(Looper.getMainLooper())
    val apps: List<AppEntry> get() = cache
    @Volatile private var listPending = false
    @Volatile private var launchPending: String? = null

    private val listTimeout = Runnable {
        if (listPending) { listPending = false; onError("The Agent did not send its app list (is it updated to this version?).") }
    }
    private val launchTimeout = Runnable {
        val p = launchPending
        if (p != null) { launchPending = null; onError("No answer from the Agent for $p.") }
    }

    /** Main thread. */
    fun refresh(): Boolean {
        if (!send(AppsWire.listRequest())) { onError("Not connected to the Agent."); return false }
        listPending = true
        main.removeCallbacks(listTimeout)
        main.postDelayed(listTimeout, 10_000L)
        return true
    }

    /** Main thread. */
    fun launch(pkg: String): Boolean {
        if (!AppsLogic.isValidPackage(pkg)) { onError("Invalid package name."); return false }
        if (!send(AppsWire.launch(pkg))) { onError("Not connected to the Agent."); return false }
        launchPending = pkg
        main.removeCallbacks(launchTimeout)
        main.postDelayed(launchTimeout, 8_000L)
        return true
    }

    override fun onRawMessage(raw: String): Boolean {
        AppsWire.parseListResult(raw)?.let { list ->
            main.post {
                listPending = false; main.removeCallbacks(listTimeout)
                cache = list
                onList(list)
            }
            return true
        }
        AppsWire.parseLaunchResult(raw)?.let { r ->
            main.post {
                launchPending = null; main.removeCallbacks(launchTimeout)
                onLaunch(r)
            }
            return true
        }
        return false
    }

    companion object {
        @Volatile private var cache: List<AppEntry> = emptyList()
        /** Forget the cached list (e.g. when switching to another Agent). */
        fun clearCache() { cache = emptyList() }
    }

    fun destroy() { listPending = false; launchPending = null; main.removeCallbacksAndMessages(null) }
}
