package com.remotecontrol.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.remotecontrol.shared.DEFAULT_PORT

/** Shows how to reach this Agent (IP:port), the current pairing PIN, and controls. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var addressView: TextView
    private lateinit var pinView: TextView
    private lateinit var pairedView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: Button
    private lateinit var captureView: TextView
    private lateinit var captureButton: Button

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Survive app restarts: if the user left the Agent running, bring it back.
        if (AgentGraph.settings(this).getBoolean(AgentService.KEY_ENABLED, false) && !AgentService.running) {
            AgentService.start(this)
        }
    }

    @Deprecated("Plain Activity (no AndroidX)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                if (!AgentService.running) AgentService.start(this) // queued ahead of the capture intent
                AgentService.startCapture(this, resultCode, data)
            } else AgentLog.add("Screen capture permission denied")
            handler.postDelayed({ render() }, 500)
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val authority = AgentGraph.authority(this)
        val running = AgentService.running
        statusView.text = if (running) "Agent running" else "Agent stopped"
        toggleButton.text = if (running) "Stop Agent" else "Start Agent"

        val ip = AgentGraph.localIpv4()
        addressView.text = if (ip != null) "$ip:$DEFAULT_PORT" else "No WiFi/LAN address found"
        pinView.text = authority.currentPin().let { "${it.substring(0, 3)} ${it.substring(3)}" }
        pairedView.text = "Paired controllers: ${authority.pairedCount()}"
        val cap = ScreenCapturer.current
        captureView.text = if (cap != null) "Screen capture: ${cap.describe()}" else "Screen capture: off"
        captureButton.text = if (cap != null) "Stop screen capture" else "Start screen capture"
        logView.text = AgentLog.snapshot().joinToString("\n").ifEmpty { "No activity yet" }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        statusView = text(root, 20f, bold = true)
        label(root, "Address (enter on the Controller)")
        addressView = text(root, 26f, bold = true)
        label(root, "Pairing PIN (single use)")
        pinView = text(root, 40f, bold = true).apply { typeface = Typeface.MONOSPACE }
        pairedView = text(root, 16f)

        toggleButton = Button(this).apply {
            setOnClickListener {
                if (AgentService.running) AgentService.stop(this@MainActivity)
                else AgentService.start(this@MainActivity)
                handler.postDelayed({ render() }, 300)
            }
        }
        root.addView(toggleButton)

        captureView = text(root, 16f).apply { setPadding(0, dp(12), 0, 0) }
        captureButton = Button(this).apply {
            setOnClickListener {
                if (ScreenCapturer.current != null) AgentService.stopCapture(this@MainActivity)
                else startActivityForResult(
                    getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQ_CAPTURE
                )
                handler.postDelayed({ render() }, 500)
            }
        }
        root.addView(captureButton)
        root.addView(Button(this).apply {
            text = "Save test frame"
            setOnClickListener { ScreenCapturer.current?.requestSave() ?: AgentLog.add("Capture is off") }
        })

        root.addView(Button(this).apply {
            text = "New PIN"
            setOnClickListener {
                AgentGraph.authority(this@MainActivity).regeneratePin()
                render()
            }
        })
        root.addView(Button(this).apply {
            text = "Forget all controllers"
            setOnClickListener {
                AgentGraph.authority(this@MainActivity).revokeAll()
                AgentLog.add("All controllers forgotten")
                render()
            }
        })

        label(root, "Setup (one time)")
        for ((name, cls) in listOf(
            "Reliability (battery, start at boot)" to ReliabilitySetupActivity::class.java,
            "Security (encryption, fingerprint)" to SecuritySetupActivity::class.java,
            "Remote access (Tailscale addresses)" to RemoteSetupActivity::class.java,
            "Wake & lock" to WakeSetupActivity::class.java,
            "Launch apps" to LaunchSetupActivity::class.java,
            "File access" to FilesSetupActivity::class.java,
        )) root.addView(Button(this).apply {
            text = name; isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, cls)) }
        })
        label(root, "Input control: Settings > Accessibility > Installed apps > Remote Control Agent input")

        label(root, "Activity")
        logView = text(root, 13f).apply { typeface = Typeface.MONOSPACE }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun label(parent: LinearLayout, s: String) {
        text(parent, 13f).apply {
            this.text = s
            setPadding(0, dp(16), 0, 0)
            alpha = 0.7f
        }
    }

    private fun text(parent: LinearLayout, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
            setTextIsSelectable(true)
            parent.addView(this)
        }

    private companion object {
        const val REQ_CAPTURE = 42
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
