package com.remotecontrol.controller

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.remotecontrol.shared.SettingsLogic
import com.remotecontrol.shared.TlsPolicy

/**
 * Session 10b: full-screen settings panel (a plain view, like the other panels: the host Activity shows it in place of
 * the connection screen and removes it again in [Host.close]). Takes a [Host] instead of depending on `DeviceStore` /
 * `ConnectionManager`, whose sources are not part of the upload.
 *
 *  - LAN only / Require encryption switches (live, stored in [SettingsStore]; they apply to the next connection cycle)
 *  - capture defaults (fps, JPEG quality, frame size), applied after each handshake when the switch is on
 *  - per device: certificate pin (first 8 bytes, to compare with RC Security on the Agent), Reset pin, Re-pair, Forget
 */
class SettingsPanel(context: Context, private val host: Host) : ScrollView(context) {
    /** One paired device. [key] = `host:port` as used by `RouteStore` / `SettingsStore`. */
    data class Device(val key: String, val title: String)

    interface Host {
        fun devices(): List<Device>
        /** Existing Forget of the device list (the panel also clears the route and pin data of that key). */
        fun forget(key: String)
        /** Drop the stored token of [key] and show the add-device form (IP, port, PIN) so the person pairs again. */
        fun repair(key: String)
        fun close()
    }

    private val settings = SettingsStore(context)
    private val routes = RouteStore(context)
    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val devicesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val dp = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    init {
        box.setPadding(px(16), px(16), px(16), px(16))
        addView(box)
        box.addView(title("Settings"))

        box.addView(switchRow("LAN only (never use the remote / VPN address)", settings.lanOnly) { settings.lanOnly = it })
        box.addView(switchRow("Require encryption (refuse Agents that have none)", settings.requireTls) { settings.requireTls = it })

        box.addView(title("Screen defaults"))
        val d = settings.captureDefaults()
        var fps = d.fps; var quality = d.quality; var size = d.maxDimension
        val fpsText = label(); val qText = label(); val sizeBtn = Button(context)
        fun save() = settings.setCaptureDefaults(SettingsStore.CaptureDefaults(fps, quality, size))
        fun texts() { fpsText.text = "Frames per second: $fps"; qText.text = "JPEG quality: $quality"; sizeBtn.text = "Frame size: $size px (tap to change)" }
        box.addView(switchRow("Apply these when connecting", settings.applyCaptureOnConnect) { settings.applyCaptureOnConnect = it })
        box.addView(fpsText)
        box.addView(seek(SettingsLogic.FPS_MIN, SettingsLogic.FPS_MAX, fps, { save() }) { fps = it; texts() })
        box.addView(qText)
        box.addView(seek(SettingsLogic.QUALITY_MIN, SettingsLogic.QUALITY_MAX, quality, { save() }) { quality = it; texts() })
        sizeBtn.setOnClickListener { size = SettingsLogic.nextSize(size); texts(); save() }
        box.addView(sizeBtn)
        texts()

        box.addView(title("Devices"))
        box.addView(devicesBox)
        box.addView(Button(context).apply { text = "Close"; setOnClickListener { host.close() } })
        refreshDevices()
    }

    private fun title(t: String) = TextView(context).apply { text = t; textSize = 18f; setPadding(0, px(14), 0, px(6)) }
    private fun label() = TextView(context).apply { textSize = 14f; setPadding(0, px(8), 0, 0) }

    private fun switchRow(text: String, on: Boolean, change: (Boolean) -> Unit) = Switch(context).apply {
        this.text = text; isChecked = on; setPadding(0, px(8), 0, px(8))
        setOnCheckedChangeListener { _, v -> change(v) }
    }

    private fun seek(min: Int, max: Int, value: Int, onStop: () -> Unit, changed: (Int) -> Unit) = SeekBar(context).apply {
        this.max = max - min; progress = value - min
        setOnSeekBarChangeListener(stop(onStop) { changed(it + min) })
    }

    /** Seekbar listener: [onValue] on every move, [onStop] when the finger is lifted. */
    private fun stop(onStop: (() -> Unit)?, onValue: ((Int) -> Unit)? = null) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onValue?.invoke(p) }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) { onStop?.invoke() }
    }

    private fun refreshDevices() {
        devicesBox.removeAllViews()
        val list = host.devices()
        if (list.isEmpty()) devicesBox.addView(label().apply { text = "No paired devices." })
        for (dev in list) devicesBox.addView(deviceRow(dev))
    }

    private fun deviceRow(dev: Device): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(8), 0, px(8)) }
        val pin = settings.pin(dev.key)
        row.addView(TextView(context).apply {
            textSize = 14f
            text = dev.title + "\n" + (if (pin == null) "Encryption: not pinned yet (pinned on the next encrypted connection)"
                                       else "Encryption: pinned ${TlsPolicy.short(pin)}")
        })
        val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(t: String, a: () -> Unit) = btns.addView(Button(context).apply { text = t; setOnClickListener { a() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        b("Reset pin") { confirm("Forget this Agent's certificate? The next connection pins whatever certificate the Agent presents, so do it only on your own network.", "Reset pin") { settings.clearPin(dev.key); refreshDevices() } }
        b("Re-pair") { confirm("Pair ${dev.title} again with a new PIN?", "Re-pair") { settings.clearPin(dev.key); host.repair(dev.key) } }
        b("Forget") { confirm("Forget ${dev.title}?", "Forget") { host.forget(dev.key); routes.forget(dev.key); settings.forget(dev.key); refreshDevices() } }
        row.addView(btns)
        return row
    }

    private fun confirm(msg: String, ok: String, action: () -> Unit) {
        AlertDialog.Builder(context).setMessage(msg).setPositiveButton(ok) { _, _ -> action() }.setNegativeButton("Cancel", null).show()
    }
}
