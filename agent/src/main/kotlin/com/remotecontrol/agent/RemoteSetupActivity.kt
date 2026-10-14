package com.remotecontrol.agent

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * "RC Remote Setup" launcher (Session 9b): everything needed on the Agent phone for access from outside the home Wi-Fi.
 * Shows the Agent's addresses (VPN / LAN / other, same data the Controller receives), whether Tailscale is installed,
 * and a self-test that connects to the Agent's own port through every listed address: a VPN address that is refused
 * means the server is bound to a single interface or the Agent is not running. Plain Activity, no AndroidX.
 */
class RemoteSetupActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "rc-remote-setup").apply { isDaemon = true } }
    private lateinit var info: TextView
    private lateinit var test: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "RC Remote Setup"
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun button(label: String, action: () -> Unit) = box.addView(Button(this).apply { text = label; setOnClickListener { action() } })

        info = TextView(this).apply { textSize = 14f }
        test = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        box.addView(TextView(this).apply {
            text = "Remote access uses Tailscale: install it on this phone and on the Controller phone, sign in to the same account, " +
                "keep it connected. Then copy the VPN address below into the Controller (it also receives it automatically)."
            textSize = 13f; setPadding(0, 0, 0, pad / 2)
        })
        box.addView(info)
        button("Refresh") { refresh() }
        button("Test: connect to the Agent through each address") { runTest() }
        box.addView(test)
        button("Open Tailscale") { openTailscale() }
        button("VPN settings (Always-on VPN)") { startSafe(Intent(Settings.ACTION_VPN_SETTINGS)) }
        button("Battery settings (set Tailscale and this app to Unrestricted)") { startSafe(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        setContentView(ScrollView(this).apply { addView(box) })
        refresh()
    }

    override fun onResume() { super.onResume(); if (::info.isInitialized) refresh() }

    @Suppress("DEPRECATION")
    private fun tailscaleInstalled(): Boolean = try {
        packageManager.getPackageInfo(TAILSCALE_PKG, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun refresh() {
        val r = AddressesHandler.snapshot(this)
        val sb = StringBuilder()
        sb.append("Tailscale app: ").append(if (tailscaleInstalled()) "installed" else "not found (or hidden from this app)").append('\n')
        sb.append("VPN active: ").append(if (r.vpnUp) "yes" else "no").append('\n')
        sb.append("Agent port: ").append(r.port).append("\n\n")
        if (r.addrs.isEmpty()) sb.append("No usable IPv4 address (no network?).")
        for (a in r.addrs) sb.append(a.kind).append("  ").append(a.ip).append("  (").append(a.iface).append(")\n")
        if (r.vpnUp.not()) sb.append("\nNo VPN address yet: turn Tailscale on.")
        info.text = sb.toString()
    }

    private fun runTest() {
        val r = AddressesHandler.snapshot(this)
        if (r.addrs.isEmpty()) { test.text = "Nothing to test."; return }
        test.text = "Testing ..."
        io.execute {
            val sb = StringBuilder()
            for (a in r.addrs) {
                val res = try {
                    Socket().use { it.connect(InetSocketAddress(a.ip, r.port), 3000) }
                    "OK"
                } catch (e: java.net.ConnectException) { "refused (Agent not running, or bound to another interface)" }
                catch (e: java.net.SocketTimeoutException) { "timeout" }
                catch (e: Exception) { e.javaClass.simpleName }
                sb.append(a.kind).append(' ').append(a.ip).append(": ").append(res).append('\n')
            }
            runOnUiThread { test.text = sb.toString() }
        }
    }

    private fun openTailscale() {
        val i = packageManager.getLaunchIntentForPackage(TAILSCALE_PKG)
        if (i != null) startSafe(i) else startSafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://tailscale.com/download/android")))
    }

    private fun startSafe(i: Intent) { try { startActivity(i) } catch (_: Exception) { test.text = "Cannot open that screen on this phone." } }

    private companion object { const val TAILSCALE_PKG = "com.tailscale.ipn" }
}
