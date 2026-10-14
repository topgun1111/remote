package com.remotecontrol.shared

/**
 * Session 10b: pause the screen stream while the Controller's viewer is hidden (saves Agent battery and data).
 * The Controller reports `VIEWER_STATE visible=false` and repeats it every [HEARTBEAT_MS] while hidden. The Agent treats
 * the pause as valid for [TTL_MS] after the last report, so a Controller that died or disconnected while hidden can never
 * leave the stream paused for the next one. Times are monotonic milliseconds (`SystemClock.elapsedRealtime`).
 */
object StreamGateLogic {
    const val HEARTBEAT_MS = 10_000L
    const val TTL_MS = 30_000L

    /** [lastReportMs] = 0 means "never reported". */
    fun paused(visible: Boolean, lastReportMs: Long, nowMs: Long): Boolean =
        !visible && lastReportMs > 0 && nowMs - lastReportMs in 0..TTL_MS
}
