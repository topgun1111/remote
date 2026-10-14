package com.remotecontrol.agent

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * Transparent activity that shows over the lock screen, turns the screen on and asks the system to dismiss the
 * keyguard. Swipe/none lock: dismissed immediately. Secure lock (PIN/pattern/password): the system shows its
 * unlock prompt and nothing more can be done remotely (documented limitation). Trusted-device / Smart Lock
 * setups complete the dismiss on their own. Finishes itself after at most 20 s.
 */
class WakeUnlockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { Log.i(TAG, "Keyguard dismissed"); finish() }
                override fun onDismissCancelled() { Log.i(TAG, "Keyguard dismiss cancelled"); finish() }
                override fun onDismissError() { Log.w(TAG, "Keyguard dismiss error"); finish() }
            })
        } else {
            finish()
        }
        handler.postDelayed({ finish() }, 20_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object { const val TAG = "RemoteAgent" }
}
