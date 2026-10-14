package com.remotecontrol.controller

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.remotecontrol.shared.WakeTracker

/**
 * Ready-made "Wake & unlock" / "Lock" buttons plus a status line (Session 7). Add it to the connection screen and
 * to the viewer's top bar: `parent.addView(WakeBar(context) { json -> connectionManager.send(json) })`.
 * Registers itself with [WakeHub] while attached to a window.
 */
class WakeBar(context: Context, send: (String) -> Boolean) : LinearLayout(context) {
    private val statusView = TextView(context).apply { textSize = 12f; visibility = View.GONE }
    private val wakeBtn = Button(context).apply { text = "Wake & unlock"; isAllCaps = false }
    private val lockBtn = Button(context).apply { text = "Lock"; isAllCaps = false }
    private val client = WakeClient(send, this::render)

    init {
        orientation = VERTICAL
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(wakeBtn); row.addView(lockBtn)
        addView(row); addView(statusView)
        wakeBtn.setOnClickListener { client.wake(unlock = true) }
        lockBtn.setOnClickListener { client.lock() }
    }

    private fun render(s: WakeTracker.State) {
        val busy = s is WakeTracker.State.Waiting
        wakeBtn.isEnabled = !busy; lockBtn.isEnabled = !busy
        statusView.visibility = if (s is WakeTracker.State.Idle) View.GONE else View.VISIBLE
        statusView.text = when (s) {
            is WakeTracker.State.Idle -> ""
            is WakeTracker.State.Waiting -> "Waking..."
            is WakeTracker.State.Success -> "OK: ${s.detail}"
            is WakeTracker.State.Failed -> "Failed: ${s.detail}"
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); WakeHub.register(client) }
    override fun onDetachedFromWindow() { WakeHub.unregister(client); client.destroy(); super.onDetachedFromWindow() }
}
