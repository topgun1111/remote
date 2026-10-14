package com.remotecontrol.agent

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.remotecontrol.shared.TlsPolicy

/**
 * "RC Security" launcher (Session 10b): encryption on/off, the certificate fingerprint to compare with the Controller
 * (Controller Settings shows the first 8 bytes of the pinned fingerprint), and certificate regeneration.
 * Changes apply when the Agent service is restarted. Plain Activity, no AndroidX.
 */
class SecuritySetupActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "RC Security"
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        box.addView(TextView(this).apply {
            text = "With encryption on, the Controller connects over TLS (wss://) and remembers this Agent's certificate " +
                "on the first connection. Compare the fingerprint below with the one in the Controller's Settings, " +
                "ideally while both phones are on your own Wi-Fi. Restart the Agent after changing anything here."
            textSize = 13f; setPadding(0, 0, 0, pad / 2)
        })
        info = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, pad / 2); setTextIsSelectable(true) }
        box.addView(info)
        toggle = Button(this).apply { setOnClickListener { TlsIdentity.setEnabled(this@SecuritySetupActivity, !TlsIdentity.enabled(this@SecuritySetupActivity)); refresh() } }
        box.addView(toggle)
        box.addView(Button(this).apply {
            text = "Make a new certificate"
            setOnClickListener {
                AlertDialog.Builder(this@SecuritySetupActivity)
                    .setMessage("Every Controller that already connected will refuse this Agent until you reset its pin in the Controller's Settings. Continue?")
                    .setPositiveButton("New certificate") { _, _ -> try { TlsIdentity.regenerate() } catch (_: Exception) { }; refresh() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        setContentView(ScrollView(this).apply { addView(box) })
        refresh()
    }

    override fun onResume() { super.onResume(); if (::info.isInitialized) refresh() }

    private fun refresh() {
        val on = TlsIdentity.enabled(this)
        val fp = try { TlsIdentity.fingerprint() } catch (e: Exception) { "" }
        info.text = "Encryption: " + (if (on) "ON" else "OFF (plain ws://)") + "\n\nFingerprint (SHA-256):\n" +
            (if (fp.isEmpty()) "not available on this phone" else TlsPolicy.display(fp).chunked(24).joinToString("\n"))
        toggle.text = if (on) "Turn encryption off" else "Turn encryption on"
    }
}
