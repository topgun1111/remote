package com.remotecontrol.agent

import android.util.Log
import com.remotecontrol.shared.ViewerStateWire

/** Session 10b: consumes `VIEWER_STATE` (no reply). Called by [FilesHandler], so `AgentServer` needs no extra hook. */
class ViewerStateHandler {
    fun tryHandle(raw: String): Boolean {
        val v = ViewerStateWire.parse(raw) ?: return false
        if (v == StreamGate.paused()) { /* repeat of the same state (heartbeat) */ } else Log.i("RemoteAgent", "VIEWER_STATE visible=$v")
        StreamGate.report(v)
        return true
    }
}
