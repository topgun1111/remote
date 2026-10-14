package com.remotecontrol.shared

/**
 * Controller-side confirmation of a `WAKE` request (pure JVM, injectable clock).
 * Success = the Agent reports `ok`, or reports the screen on while a *new* frame has arrived since the request
 * (the "resumed screen stream" check from the plan). Failure = the Agent says not ok, or nothing at all within [timeoutMs].
 */
class WakeTracker(private val timeoutMs: Long = 8_000L) {
    sealed class State {
        object Idle : State()
        object Waiting : State()
        data class Success(val detail: String) : State()
        data class Failed(val detail: String) : State()
    }

    private var sentAt = -1L
    private var frameSeen = false
    private var result: WakeResult? = null
    private var current: State = State.Idle

    val state: State get() = current

    fun onSent(now: Long) {
        sentAt = now; frameSeen = false; result = null; current = State.Waiting
    }

    /** Call for every decoded frame with the *local* receive time (never the Agent's `timestampMs`: the phones' clocks differ). */
    fun onFrame(frameTimeMs: Long) {
        if (current == State.Waiting && frameTimeMs >= sentAt) { frameSeen = true; evaluate() }
    }

    fun onResult(r: WakeResult) {
        if (current != State.Waiting) return
        result = r; evaluate()
    }

    /** Call periodically (e.g. every 500 ms) while Waiting. */
    fun poll(now: Long): State {
        if (current == State.Waiting && now - sentAt >= timeoutMs) {
            val r = result
            current = when {
                r != null && r.screenOn && !r.locked -> State.Success(r.detail)
                r != null -> State.Failed(r.detail)
                frameSeen -> State.Success("Stream resumed (Agent sent no WAKE_RESULT).")
                else -> State.Failed("No answer from the Agent (older Agent version, asleep, or link down).")
            }
        }
        return current
    }

    private fun evaluate() {
        val r = result ?: return
        current = when {
            r.ok -> State.Success(r.detail + if (frameSeen) " Stream resumed." else "")
            // Wake worked but the keyguard is up: report as failure with the explanation; do not wait for frames.
            else -> State.Failed(r.detail)
        }
    }
}
