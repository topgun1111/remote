package com.remotecontrol.controller

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.remotecontrol.shared.AddressClass
import com.remotecontrol.shared.RouteLogic

/**
 * Session 9a: set or clear the remote (VPN) address of one paired device, with a live hint. The address is the Agent's
 * Tailscale address (`100.x.y.z`) or its machine name, `host:port` if the port is not 8765. Clear switches auto-fill
 * from the Agent's address report (9b) off for this device until an address is saved again.
 */
object RouteDialog {
    fun show(activity: Activity, store: RouteStore, key: String, title: String, onChanged: () -> Unit = {}) {
        val dp = activity.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val input = EditText(activity).apply {
            hint = "100.x.y.z or machine name"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(store.remoteText(key))
        }
        val hint = TextView(activity).apply { textSize = 12f; setPadding(0, pad / 2, 0, 0) }
        box.addView(input); box.addView(hint)

        fun describe(raw: String): Pair<Boolean, String> {
            if (raw.isBlank()) return true to "Empty = no remote address (only the local network is used)."
            val ep = RouteLogic.parseEndpoint(raw) ?: return false to "Not a valid address. Use an IPv4 address or a name, optionally :port."
            val cls = RouteLogic.classify(ep.first)
            val extra = when (cls) {
                AddressClass.CGNAT -> "Looks like a Tailscale address."
                AddressClass.PRIVATE_LAN -> "This is a local address; a remote address is normally the Agent's Tailscale address (100.x.y.z)."
                else -> ""
            }
            return true to "Will connect to ${ep.first} port ${ep.second}. $extra".trim()
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Remote address: $title")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNeutralButton("Clear") { _, _ -> store.setRemote(key, ""); onChanged() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun refresh() {
                val (ok, text) = describe(input.text.toString())
                hint.text = text
                save.isEnabled = ok
            }
            save.setOnClickListener {
                if (store.setRemote(key, input.text.toString().trim())) { onChanged(); dialog.dismiss() }
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = refresh()
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
            refresh()
        }
        dialog.show()
    }
}
