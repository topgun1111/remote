package com.remotecontrol.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.remotecontrol.shared.LaunchResult

/**
 * Setup + self-test for remote app launching (Session 8, part 1). Launcher icon "RC Launch Setup".
 * 1. Allow "Display over other apps": exempts the Agent from Android's background-activity-start limits.
 * 2. Self-test: lists the launchable apps, then launches the system Settings app 6 s later (press Home first).
 */
class LaunchSetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var handler: AppsHandler
    private val main = Handler(Looper.getMainLooper())
    private var lastResult: LaunchResult? = null
    private var listCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "RC Launch Setup"
        handler = AppsHandler(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, pad) }
        col.addView(status)

        fun button(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label; isAllCaps = false; gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            })
        }

        button("1. Allow 'Display over other apps'") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("Test: count launchable apps") {
            Thread {
                val n = AppLister(this).list().size
                main.post { listCount = n; refresh() }
            }.start()
        }
        button("Test: open Settings in 6 s (go Home now)") {
            lastResult = null
            main.postDelayed({ handler.launch("com.android.settings") { lastResult = it } }, 6_000L)
        }
        button("Refresh status") { refresh() }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val draw = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val r = lastResult
        status.text = buildString {
            appendLine("Display over other apps: $draw")
            if (listCount >= 0) appendLine("Launchable apps found: $listCount")
            appendLine()
            appendLine(
                if (draw) "Remote launch can start apps directly."
                else "Without this permission Android may block remote launches; the Agent then also posts a notification to tap.",
            )
            if (r != null) { appendLine(); append("Last test: ${if (r.ok) "OK" else "FAILED"} (${r.via}) - ${r.detail}") }
        }
    }
}
