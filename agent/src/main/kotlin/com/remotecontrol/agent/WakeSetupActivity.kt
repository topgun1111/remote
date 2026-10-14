package com.remotecontrol.agent

import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
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
import android.widget.Toast
import com.remotecontrol.shared.WakeRequest
import com.remotecontrol.shared.WakeResult

/**
 * Setup + self-test for remote wake (Session 7). Launcher icon "RC Wake Setup" (plain Activity, no AndroidX).
 * 1. Enable Device Admin (needed only for the remote Lock command).
 * 2. Allow full-screen notifications (Android 14+) so the Agent can show the unlock screen from the background.
 * 3. Disable battery optimization so the Agent stays reachable while the screen is off.
 * 4. Self-test: locks the screen, then wakes it 6 s later; the result shows when you come back to this screen.
 */
class WakeSetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var wake: WakeHandler
    private val main = Handler(Looper.getMainLooper())
    private var lastResult: WakeResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "RC Wake Setup"
        wake = WakeHandler(this)
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

        button("1. Enable Device Admin") {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, RcDeviceAdminReceiver.component(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets your Controller phone lock this phone's screen remotely."),
            )
        }
        button("Disable Device Admin (needed before uninstalling)") {
            getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(RcDeviceAdminReceiver.component(this))
            refresh()
        }
        button("2. Allow full-screen notifications") {
            val uri = Uri.parse("package:$packageName")
            val i = if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, uri)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(i)
        }
        button("3. Battery: set Remote Control Agent to Unrestricted") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        button("Test: lock now, wake in 6 s") {
            val dpmActive = getSystemService(DevicePolicyManager::class.java).isAdminActive(RcDeviceAdminReceiver.component(this))
            if (!dpmActive) {
                Toast.makeText(this, "Enable Device Admin first", Toast.LENGTH_LONG).show()
            } else {
                lastResult = null
                wake.handle(WakeRequest(unlock = false, lock = true)) { }
                main.postDelayed({ wake.handle(WakeRequest(unlock = true)) { lastResult = it } }, 6_000L)
            }
        }
        button("Test: wake only (lock the phone yourself first)") {
            lastResult = null
            main.postDelayed({ wake.handle(WakeRequest(unlock = true)) { lastResult = it } }, 6_000L)
        }
        button("Refresh status") { refresh() }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val s = wake.state()
        val nm = getSystemService(NotificationManager::class.java)
        val fsi = if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent().toString() else "n/a (<14)"
        val r = lastResult
        status.text = buildString {
            appendLine("Screen on: ${s.screenOn}   Keyguard locked: ${s.keyguardLocked}")
            appendLine("Secure lock (PIN/pattern/password): ${s.deviceSecure}")
            appendLine("Device Admin active: ${s.adminActive}")
            appendLine("Full-screen notifications allowed: $fsi")
            appendLine()
            appendLine(if (s.deviceSecure) "Secure lock set: remote wake turns the screen on but cannot unlock it (Smart Lock / trusted device can)." else "No secure lock: remote unlock can work.")
            if (r != null) { appendLine(); append("Last test: ${if (r.ok) "OK" else "FAILED"} - ${r.detail}") }
        }
    }
}
