package com.remotecontrol.agent

import android.os.SystemClock
import com.remotecontrol.shared.StreamGateLogic

/**
 * Session 10b: set by [ViewerStateHandler] when the Controller reports that its viewer is hidden/visible. `ScreenCapturer`
 * treats a paused gate like "no viewer connected": it keeps its cheap ~1/s cached frame but sends nothing. The pause
 * expires by itself ([StreamGateLogic.TTL_MS]) so a vanished Controller cannot leave the stream stopped.
 * [onResume] (optional, set by `AgentServer`) replays the cached frame when the viewer comes back.
 */
object StreamGate {
    @Volatile private var visible = true
    @Volatile private var lastMs = 0L
    @Volatile var onResume: (() -> Unit)? = null

    fun report(v: Boolean) {
        val was = paused()
        visible = v
        lastMs = SystemClock.elapsedRealtime()
        if (was && v) onResume?.invoke()
    }

    fun paused(): Boolean = StreamGateLogic.paused(visible, lastMs, SystemClock.elapsedRealtime())
}
