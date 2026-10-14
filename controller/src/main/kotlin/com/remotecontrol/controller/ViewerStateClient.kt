package com.remotecontrol.controller

import android.os.Handler
import android.os.Looper
import com.remotecontrol.shared.StreamGateLogic
import com.remotecontrol.shared.ViewerStateWire

/**
 * Session 10b: tells the Agent whether the live view is on screen, so it stops sending frames while it is hidden.
 * [setVisible] when the viewer is shown/hidden or the app goes to the background; [onConnected] after each accepted
 * handshake (a new link starts with the Agent assuming "visible"). While hidden it repeats the message every 10 s
 * (the Agent lets a pause expire after 30 s, see `StreamGateLogic`). [send] returns true when the message went out.
 * Call from the main thread.
 */
class ViewerStateClient(private val send: (String) -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private var visible = true
    private val beat = object : Runnable {
        override fun run() {
            if (visible) return
            send(ViewerStateWire.build(false))
            main.postDelayed(this, StreamGateLogic.HEARTBEAT_MS)
        }
    }

    fun setVisible(v: Boolean) {
        visible = v
        main.removeCallbacks(beat)
        send(ViewerStateWire.build(v))
        if (!v) main.postDelayed(beat, StreamGateLogic.HEARTBEAT_MS)
    }

    fun onConnected() { if (!visible) send(ViewerStateWire.build(false)) }

    fun stop() { main.removeCallbacks(beat) }
}
