package com.remotecontrol.controller

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.remotecontrol.shared.CaptureConfig
import com.remotecontrol.shared.CaptureStatus
import com.remotecontrol.shared.InputWire

/**
 * Full-screen live view of the Agent: the frame, a status overlay (not connected / capture off /
 * waiting), a stats line (with the `LAN · TLS` badge), touch control of the Agent (Session 6), a Back / Home /
 * Recents bar, and a collapsible Controls panel with a text field, Wake & unlock, and fps / JPEG quality / frame size.
 * All methods run on the main thread, except `renderer.submit`, which is thread-safe.
 *
 * @param send   sends one JSON text frame to the Agent (`ConnectionManager.send`); false when not connected
 * @param badge  current link badge, e.g. `LAN · TLS` (`ConnectionManager.badge`)
 */
class ViewerScreen(
    private val activity: Activity,
    private val onBack: () -> Unit,
    private val sendConfig: (CaptureConfig) -> Boolean,
    private val send: (String) -> Boolean = { false },
    private val badge: () -> String = { "" },
) {
    val screen = ScreenView(activity)
    val renderer = FrameRenderer(screen) { updateOverlay() }
    val root = FrameLayout(activity)

    private val handler = Handler(Looper.getMainLooper())
    private val overlay = TextView(activity)
    private val statsView = TextView(activity)
    private val panel = LinearLayout(activity)
    private val sizeButton = Button(activity)
    private val hintView = TextView(activity)
    private val textInput = EditText(activity)
    private val bottomBox = LinearLayout(activity)

    private var connState = ConnectionManager.State.IDLE
    private var connDetail = ""
    private var status: CaptureStatus? = null
    private var active = false

    private var fps = 5
    private var quality = 60
    private var maxDim = 720

    private val fpsSlider = Slider("FPS", 1, 30, "") { fps = it; commit() }
    private val qualitySlider = Slider("JPEG quality", 20, 95, "%") { quality = it; commit() }

    private val hideHint = Runnable { hintView.visibility = View.GONE }

    private val touch = TouchInputHandler(
        slopPx = 8 * activity.resources.displayMetrics.density,
        toNormalized = { x, y -> screen.toNormalized(x, y) },
        frameSize = { screen.frameSize },
        send = { send(it) },
        onDropped = { flash(if (screen.hasFrame) "Touch not sent (outside the screen or not connected)" else "No frame yet") },
    )

    private val ticker = object : Runnable {
        override fun run() {
            updateStats()
            if (active) handler.postDelayed(this, 1000)
        }
    }

    init {
        root.setBackgroundColor(Color.BLACK)
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        screen.setOnTouchListener { _, e -> touch.onTouch(e) }

        overlay.apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(2))
            isClickable = true // touches on the bar must not reach the remote screen
        }
        top.addView(Button(activity).apply { text = "‹ Exit"; isAllCaps = false; setOnClickListener { onBack() } })
        statsView.apply { setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER }
        top.addView(statsView, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Button(activity).apply {
            text = "Controls"
            isAllCaps = false
            setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        panel.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(12))
            visibility = View.GONE
            isClickable = true
        }
        panel.addView(textRow())
        panel.addView(WakeBar(activity, send).apply {
            // WakeBar uses the system button colours; its status line needs a light colour on the dark panel.
            (getChildAt(1) as? TextView)?.setTextColor(Color.WHITE)
        })
        panel.addView(fpsSlider.row)
        panel.addView(qualitySlider.row)
        sizeButton.isAllCaps = false
        sizeButton.setOnClickListener {
            maxDim = SIZES[(SIZES.indexOf(maxDim).takeIf { it >= 0 } ?: 0).let { (it + 1) % SIZES.size }]
            refreshSizeButton()
            commit()
        }
        panel.addView(sizeButton)

        bottomBox.orientation = LinearLayout.VERTICAL
        bottomBox.addView(panel, LinearLayout.LayoutParams(-1, -2))
        bottomBox.addView(navBar(), LinearLayout.LayoutParams(-1, -2))
        root.addView(bottomBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        hintView.apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        root.addView(hintView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(72)
        })

        fpsSlider.set(fps); qualitySlider.set(quality); refreshSizeButton()
        updateOverlay()
    }

    /** Back / Home / Recents of the Agent's phone (always visible, slim). */
    private fun navBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x88000000.toInt())
            isClickable = true
        }
        fun nav(label: String, key: String) = bar.addView(
            Button(activity).apply {
                text = label; isAllCaps = false
                setOnClickListener { if (!send(InputWire.key(key))) flash("Not connected to the Agent") }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        nav("◀ Back", "BACK")
        nav("● Home", "HOME")
        nav("▣ Recents", "RECENTS")
        return bar
    }

    /** Text field that types into the focused input field on the Agent, plus Backspace / Enter. */
    private fun textRow(): View {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        textInput.apply {
            hint = "Type text for the Agent"
            setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false
            }
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(textInput, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(activity).apply { text = "Send"; isAllCaps = false; setOnClickListener { sendText() } })
        val keys = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun key(label: String, k: String) = keys.addView(
            Button(activity).apply {
                text = label; isAllCaps = false
                setOnClickListener { if (!send(InputWire.key(k))) flash("Not connected to the Agent") }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        key("⌫ Backspace", "BACKSPACE")
        key("↵ Enter", "ENTER")
        col.addView(row)
        col.addView(keys)
        return col
    }

    private fun sendText() {
        val t = textInput.text.toString()
        if (t.isEmpty()) return
        if (t.length > MAX_TEXT) { flash("Text too long (max $MAX_TEXT characters)"); return }
        if (send(InputWire.text(t))) textInput.setText("") else flash("Not connected to the Agent")
    }

    /** Short message at the bottom of the view; disappears after two seconds. */
    private fun flash(text: String) {
        hintView.text = text
        hintView.visibility = View.VISIBLE
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, 2000)
    }

    /** Frames are only decoded while the viewer is on screen and the activity is resumed. */
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        renderer.enabled = on
        handler.removeCallbacks(ticker)
        if (!on) { handler.removeCallbacks(hideHint); hintView.visibility = View.GONE }
        if (on) { renderer.takeStats(); handler.postDelayed(ticker, 1000) }
        updateOverlay()
    }

    fun onConnection(state: ConnectionManager.State, detail: String) {
        connState = state
        connDetail = detail
        if (state == ConnectionManager.State.IDLE || state == ConnectionManager.State.REJECTED) {
            status = null
            screen.clearFrame()
        }
        updateOverlay()
    }

    fun onCaptureStatus(s: CaptureStatus) {
        status = s
        if (!s.active) screen.clearFrame()
        else {
            // Sliders follow the Agent's real values; size is inferred from the long side of the frame.
            fps = s.fps.coerceIn(1, 30); quality = s.quality.coerceIn(20, 95)
            val long = maxOf(s.width, s.height)
            if (long > 0) maxDim = SIZES.minByOrNull { kotlin.math.abs(it - long) } ?: maxDim
            fpsSlider.set(fps); qualitySlider.set(quality); refreshSizeButton()
        }
        updateOverlay()
    }

    private fun commit() {
        sendConfig(CaptureConfig(fps = fps, quality = quality, maxDimension = maxDim))
    }

    private fun refreshSizeButton() {
        sizeButton.text = "Frame size: $maxDim px (tap to change)"
    }

    private fun updateOverlay() {
        val msg: String? = when {
            connState != ConnectionManager.State.CONNECTED -> "${connState.name}\n$connDetail"
            status?.active != true -> "The Agent isn't sharing its screen.\nOn the Agent tap “Start screen capture”."
            !screen.hasFrame -> "Waiting for the first frame…"
            else -> null
        }
        overlay.text = msg ?: ""
        overlay.visibility = if (msg == null) View.GONE else View.VISIBLE
        // Dim the last frame while the link is down so it's obvious it isn't live.
        screen.alpha = if (connState == ConnectionManager.State.CONNECTED) 1f else 0.4f
    }

    private fun updateStats() {
        val s = renderer.takeStats()
        val st = status
        val size = if (st != null && st.active) "${st.width}×${st.height}" else "—"
        val perFrame = if (s.decoded > 0) s.bytes / s.decoded / 1024 else 0
        val b = badge()
        statsView.text = (if (b.isNotEmpty()) "$b · " else "") +
            "$size · ${s.decoded} fps · $perFrame KB/frame · drop ${s.dropped}" +
            if (s.failed > 0) " · bad ${s.failed}" else ""
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    /** Labelled SeekBar that applies its value when the finger lifts (not on every tick). */
    private inner class Slider(
        private val label: String,
        private val min: Int,
        private val max: Int,
        private val unit: String,
        private val onCommit: (Int) -> Unit,
    ) {
        private val text = TextView(activity).apply { setTextColor(Color.WHITE); textSize = 13f }
        private val bar = SeekBar(activity).apply { this.max = this@Slider.max - this@Slider.min }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        init {
            row.addView(text)
            row.addView(bar)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) = show(min + p)
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) = onCommit(min + b.progress)
            })
        }

        fun set(v: Int) { bar.progress = v - min; show(v) }
        private fun show(v: Int) { text.text = "$label: $v$unit" }
    }

    private companion object {
        val SIZES = listOf(480, 720, 1080, 1440)
        const val MAX_TEXT = 2000 // same limit as the Agent's InputCommand.MAX_TEXT
    }
}
