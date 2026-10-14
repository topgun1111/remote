package com.remotecontrol.agent

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * "RC Reliability Setup" (Session 10a): everything that keeps the Agent alive and reachable.
 * Battery exemption, vendor auto-start screens, the start-at-boot switch, notification permission and channel settings,
 * a boot self-test and the honest cold-start checklist. Plain Activity, no AndroidX.
 */
class ReliabilitySetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var bootSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "RC Reliability Setup"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, pad) }
        col.addView(status)

        fun button(label: String, onClick: () -> Unit) = col.addView(Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        })

        button("1. Battery: allow running in background (Unrestricted)") { BatteryHelper.requestExemption(this) }
        button("Open vendor auto-start / protected apps screen") {
            if (!BatteryHelper.openVendorAutoStart(this)) {
                Toast.makeText(this, "No vendor screen found; opening App info instead", Toast.LENGTH_LONG).show()
                BatteryHelper.openAppDetails(this)
            }
        }
        bootSwitch = Switch(this).apply {
            text = "2. Start the Agent after a restart or app update (if it was running)"
            setPadding(0, pad / 2, 0, pad / 2)
            isChecked = AutoStart.enabled(this@ReliabilitySetupActivity)
            setOnCheckedChangeListener { _, v -> AutoStart.setEnabled(this@ReliabilitySetupActivity, v); refresh() }
        }
        col.addView(bootSwitch)
        button("3. Allow notifications") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        button("Notification channel settings (status line)") {
            AgentNotification.ensureChannels(this, listOf("agent"))
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, AgentNotification.CHANNEL_STATUS),
            )
        }
        button("Test: run the boot logic now") {
            val outcome = BootReceiver.startAgent(this, "self-test")
            Toast.makeText(this, outcome, Toast.LENGTH_LONG).show()
            refresh()
        }
        col.addView(TextView(this).apply {
            textSize = 13f; setPadding(0, pad, 0, 0)
            text = "Cold start, honestly:\n" +
                "- After a restart the Agent service can start itself, but screen capture cannot: Android asks for consent " +
                "every time. Start screen capture once on this phone.\n" +
                "- With a PIN, pattern or password nothing runs before the first unlock after boot.\n" +
                "- Android may refuse the background start; the Agent then shows a \"tap to start\" notification.\n" +
                "- Stopping the Agent on purpose keeps it stopped after a restart.\n" +
                "- The self-test above runs from the foreground; only a real restart verifies the boot path."
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    @Deprecated("Plain Activity (no AndroidX)")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun refresh() {
        val notif = if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    else getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        status.text = "Battery: " + (if (BatteryHelper.isExempt(this)) "Unrestricted (OK)" else "optimised: the system may freeze the Agent") + "\n" +
            "Start at boot: " + (if (AutoStart.enabled(this)) "on" else "off") +
            (if (AutoStart.wasRunning(this)) ", Agent is marked as running" else ", Agent is marked as stopped") + "\n" +
            "Notifications: " + (if (notif) "allowed" else "blocked") + "\n" +
            "Last boot/update: " + AutoStart.lastBoot(this)
    }
}
