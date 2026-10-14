package com.remotecontrol.controller

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Same idea as [WakeHub] (Session 8, part 1): `ConnectionManager` needs one more one-line hook in its raw
 * text-message handler, before `Protocol.decode`: `if (AppsHub.onRawMessage(text)) return`.
 * A cheap no-op when no [AppsClient] is registered.
 */
object AppsHub {
    interface Sink {
        fun onRawMessage(raw: String): Boolean
    }

    private val sinks = CopyOnWriteArrayList<Sink>()

    fun register(s: Sink) { if (!sinks.contains(s)) sinks.add(s) }
    fun unregister(s: Sink) { sinks.remove(s) }

    /** True if some sink consumed the message (an `APP_LIST_RESULT` or `LAUNCH_RESULT`). */
    fun onRawMessage(raw: String): Boolean {
        if (!raw.contains("APP_LIST_RESULT") && !raw.contains("LAUNCH_RESULT")) return false
        var consumed = false
        for (s in sinks) if (s.onRawMessage(raw)) consumed = true
        return consumed
    }
}
