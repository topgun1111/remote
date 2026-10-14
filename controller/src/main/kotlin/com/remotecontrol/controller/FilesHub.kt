package com.remotecontrol.controller

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Same idea as [AppsHub] (Session 8b): `ConnectionManager` needs one more one-line hook in its raw text-message
 * handler, before `Protocol.decode`: `if (FilesHub.onRawMessage(text)) return`. A cheap no-op when no client is registered.
 * Session 8c: also routes `FILE_WRITE_ACK` and `FILE_OP_RESULT`; 8d: and `APP_ICON_RESULT` for [AppIconLoader]
 * (the hook in `ConnectionManager` stays the same).
 */
object FilesHub {
    interface Sink {
        fun onRawMessage(raw: String): Boolean
    }

    private val sinks = CopyOnWriteArrayList<Sink>()

    fun register(s: Sink) { if (!sinks.contains(s)) sinks.add(s) }
    fun unregister(s: Sink) { sinks.remove(s) }

    /** True if some sink consumed the message (`FILE_LIST_RESULT`, `FILE_CHUNK`, `FILE_WRITE_ACK`, `FILE_OP_RESULT` or `APP_ICON_RESULT`). */
    fun onRawMessage(raw: String): Boolean {
        if (!raw.contains("FILE_LIST_RESULT") && !raw.contains("FILE_CHUNK") &&
            !raw.contains("FILE_WRITE_ACK") && !raw.contains("FILE_OP_RESULT") && !raw.contains("APP_ICON_RESULT")) return false
        var consumed = false
        for (s in sinks) if (s.onRawMessage(raw)) consumed = true
        return consumed
    }
}
