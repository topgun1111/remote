package com.remotecontrol.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug-only launcher ("RC Input Test") that fires hardcoded [InputCommand]s through the
 * accessibility service, i.e. the Session 5 deliverable, without needing the Controller.
 * Gesture buttons wait 4 s so you can switch to another app (e.g. the home screen) first.
 */
class InputTestActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)
        root.addView(button("1. Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(button("Android 13+: App info > ⋮ > Allow restricted settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(TextView(this).apply { text = "\nImmediate (global actions):" })
        for (k in listOf("HOME", "BACK", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS")) {
            root.addView(button("Key $k") { fire(InputCommand.Key(k), 0) })
        }
        root.addView(TextView(this).apply { text = "\nGestures (run 4 s after tap — switch to a target app):" })
        root.addView(button("Tap centre") { fire(InputCommand.Tap(0.5f, 0.5f), 4000) })
        root.addView(button("Long-press centre") { fire(InputCommand.LongPress(0.5f, 0.5f, 700), 4000) })
        root.addView(button("Swipe up (home screen: opens drawer)") { fire(InputCommand.Swipe(0.5f, 0.85f, 0.5f, 0.25f, 300), 4000) })
        root.addView(button("Swipe left") { fire(InputCommand.Swipe(0.85f, 0.5f, 0.15f, 0.5f, 300), 4000) })
        root.addView(button("Scroll down") { fire(InputCommand.Scroll(0.5f, 0.5f, 0f, 0.4f), 4000) })
        root.addView(button("Scroll up") { fire(InputCommand.Scroll(0.5f, 0.5f, 0f, -0.4f), 4000) })
        root.addView(TextView(this).apply { text = "\nText (tap the box below first; it must keep focus, so type is sent immediately):" })
        field = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; hint = "focus me" }
        root.addView(field)
        root.addView(button("Type \"merhaba\"") { fire(InputCommand.Text("merhaba"), 0) })
        root.addView(button("Backspace") { fire(InputCommand.Key("BACKSPACE"), 0) })
        root.addView(button("Run sequence: Home > swipe up > Back > Notifications > Back") { sequence() })
        log = TextView(this).apply { gravity = Gravity.START; setPadding(0, pad, 0, 0) }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val on = InputAccessibilityService.instance != null
        status.text = if (on) "Accessibility service: ENABLED" else "Accessibility service: NOT enabled — step 1"
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun fire(cmd: InputCommand, delayMs: Long, after: (() -> Unit)? = null) {
        ui.postDelayed({
            val svc = InputAccessibilityService.instance
            if (svc == null) { note("$cmd -> service not enabled"); after?.invoke(); return@postDelayed }
            svc.execute(cmd) { ok -> note("${cmd::class.simpleName} ${if (ok) "OK" else "FAILED"}"); after?.invoke() }
        }, delayMs)
    }

    private fun sequence() {
        fire(InputCommand.Key("HOME"), 3000) {
            ui.postDelayed({
                fire(InputCommand.Swipe(0.5f, 0.85f, 0.5f, 0.25f, 300), 0) {
                    ui.postDelayed({
                        fire(InputCommand.Key("BACK"), 0) {
                            ui.postDelayed({
                                fire(InputCommand.Key("NOTIFICATIONS"), 0) {
                                    ui.postDelayed({ fire(InputCommand.Key("BACK"), 0) }, 1500)
                                }
                            }, 1500)
                        }
                    }, 1500)
                }
            }, 1500)
        }
    }

    private fun note(s: String) {
        log.append(s + "\n")
    }
}
