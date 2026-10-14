package com.remotecontrol.controller

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny bridge so `ConnectionManager` needs only two one-line hooks (Session 7), whatever UI is showing:
 * - in the raw text-message handler, before `Protocol.decode`: `if (WakeHub.onRawMessage(text)) return`
 * - in the frame callback (any thread): `WakeHub.onFrame()`
 * Both are cheap no-ops when no [WakeClient] is registered.
 */
object WakeHub {
    interface Sink {
        fun onRawMessage(raw: String): Boolean
        fun onFrame()
    }

    private val sinks = CopyOnWriteArrayList<Sink>()

    fun register(s: Sink) { if (!sinks.contains(s)) sinks.add(s) }
    fun unregister(s: Sink) { sinks.remove(s) }

    /** True if some sink consumed the message (it was a `WAKE_RESULT`). */
    fun onRawMessage(raw: String): Boolean {
        if (!raw.contains("WAKE_RESULT")) return false
        var consumed = false
        for (s in sinks) if (s.onRawMessage(raw)) consumed = true
        return consumed
    }

    fun onFrame() { for (s in sinks) s.onFrame() }
}
