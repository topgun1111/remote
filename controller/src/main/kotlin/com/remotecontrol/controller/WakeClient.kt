package com.remotecontrol.controller

import android.os.Handler
import android.os.Looper
import com.remotecontrol.shared.WakeTracker
import com.remotecontrol.shared.WakeWire

/**
 * Sends `WAKE` and reports progress through [onState] (always on the main thread). Success needs the Agent's
 * `WAKE_RESULT` (ok) - or, for an Agent that never answers, a frame that arrived after the request.
 *
 * @param send sends one JSON text frame; false when not connected/authenticated (`ConnectionManager.send`).
 */
class WakeClient(
    private val send: (String) -> Boolean,
    private val onState: (WakeTracker.State) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) : WakeHub.Sink {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = WakeTracker()
    @Volatile private var waiting = false

    private val poller = object : Runnable {
        override fun run() {
            if (!waiting) return
            publish(tracker.poll(now()))
            if (waiting) main.postDelayed(this, 500L)
        }
    }

    /** Wake the Agent's screen; [unlock] also asks it to dismiss the keyguard. Main thread. */
    fun wake(unlock: Boolean = true): Boolean = start(WakeWire.wake(unlock))

    /** Turn the Agent's screen off (needs Device Admin on the Agent). Main thread. */
    fun lock(): Boolean = start(WakeWire.lock())

    private fun start(json: String): Boolean {
        tracker.onSent(now())
        if (!send(json)) {
            waiting = false
            onState(WakeTracker.State.Failed("Not connected to the Agent."))
            return false
        }
        waiting = true
        onState(WakeTracker.State.Waiting)
        main.postDelayed(poller, 500L)
        return true
    }

    private fun publish(s: WakeTracker.State) {
        if (s !is WakeTracker.State.Waiting) waiting = false
        onState(s)
    }

    override fun onRawMessage(raw: String): Boolean {
        val r = WakeWire.parseResult(raw) ?: return false
        main.post { tracker.onResult(r); if (tracker.state !is WakeTracker.State.Waiting) publish(tracker.state) }
        return true
    }

    override fun onFrame() {
        if (!waiting) return
        val t = now()
        main.post { tracker.onFrame(t); if (tracker.state !is WakeTracker.State.Waiting) publish(tracker.state) }
    }

    fun destroy() { waiting = false; main.removeCallbacksAndMessages(null) }
}
