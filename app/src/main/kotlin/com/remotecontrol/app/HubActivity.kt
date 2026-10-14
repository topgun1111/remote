package com.remotecontrol.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The only launcher screen of the app. Pick what this phone does:
 *  - Controller: see and operate another phone that runs the Agent
 *  - Agent: let this phone be seen and controlled (shows the PIN, shares the screen)
 * Back from either screen returns here. A phone can use both roles (e.g. Agent in the background, Controller in front).
 */
class HubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "Remote Control"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "What should this phone do?"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(32))
        })

        fun choice(title: String, info: String, target: Class<*>) {
            col.addView(Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 18f
                setOnClickListener { startActivity(Intent(this@HubActivity, target)) }
            }, LinearLayout.LayoutParams(-1, dp(64)))
            col.addView(TextView(this).apply {
                text = info
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(28))
            })
        }
        choice(
            "Control another phone",
            "Controller: shows the screen of a phone that runs the Agent and lets you touch, type, open apps and move files.",
            com.remotecontrol.controller.MainActivity::class.java,
        )
        choice(
            "Let this phone be controlled",
            "Agent: shows a PIN for pairing, shares this phone's screen and carries out what the Controller sends.",
            com.remotecontrol.agent.MainActivity::class.java,
        )
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
