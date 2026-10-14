package com.remotecontrol.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.remotecontrol.shared.ScreenState
import com.remotecontrol.shared.WakeLogic
import com.remotecontrol.shared.WakeRequest
import com.remotecontrol.shared.WakeResult
import com.remotecontrol.shared.WakeStep
import com.remotecontrol.shared.WakeWire

/**
 * Executes `WAKE` (Session 7). Decisions come from the pure [WakeLogic]; this class only does the Android calls:
 * wake lock with ACQUIRE_CAUSES_WAKEUP, [WakeUnlockActivity] (+ full-screen-intent notification as the way to start
 * an activity from the background while locked), and `DevicePolicyManager.lockNow()` for the lock command.
 *
 * Create one instance with the application context and call [tryHandle] from `AgentServer.onMessage` for
 * authenticated clients, before `Protocol.decode`.
 */
class WakeHandler(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pm = app.getSystemService(PowerManager::class.java)
    private val km = app.getSystemService(KeyguardManager::class.java)
    private val dpm = app.getSystemService(DevicePolicyManager::class.java)
    private val admin = RcDeviceAdminReceiver.component(app)

    fun state() = ScreenState(
        screenOn = pm.isInteractive,
        keyguardLocked = km.isKeyguardLocked,
        deviceSecure = km.isDeviceSecure,
        adminActive = dpm.isAdminActive(admin),
    )

    /** True (and answered via [reply] with a `WAKE_RESULT` JSON) if [raw] is a `WAKE` message; false otherwise. */
    fun tryHandle(raw: String, reply: (String) -> Unit): Boolean {
        val req = WakeWire.parseRequest(raw) ?: return false
        handle(req) { reply(WakeWire.result(it)) }
        return true
    }

    /** Runs on the main thread; [done] is called on the main thread after the screen state has settled. */
    fun handle(req: WakeRequest, done: (WakeResult) -> Unit) {
        main.post {
            val before = state()
            val steps = WakeLogic.plan(req, before)
            Log.i(TAG, "WAKE unlock=${req.unlock} lock=${req.lock} state=$before steps=$steps")
            for (s in steps) try {
                when (s) {
                    WakeStep.ACQUIRE_WAKE_LOCK -> wakeScreen()
                    WakeStep.DISMISS_KEYGUARD -> launchUnlock()
                    WakeStep.LOCK_NOW -> dpm.lockNow()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wake step $s failed: ${e.message}")
            }
            main.postDelayed({
                val result = WakeLogic.outcome(req, before, state())
                Log.i(TAG, "WAKE_RESULT ok=${result.ok} ${result.detail}")
                done(result)
            }, if (req.lock) 800L else 1_500L)
        }
    }

    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK is deprecated but is still the way to turn the screen on from a service.
    private fun wakeScreen() {
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "RemoteControl:wake",
        )
        wl.acquire(10_000L) // auto-released; the system screen timeout takes over afterwards
    }

    private fun launchUnlock() {
        val intent = Intent(app, WakeUnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        // Direct start: allowed when the app has a visible window or another background-start exemption.
        try { app.startActivity(intent) } catch (e: Exception) { Log.w(TAG, "startActivity blocked: ${e.message}") }
        // Fallback that works while locked: full-screen-intent notification (Android 14+: user must allow it in setup).
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Remote wake", NotificationManager.IMPORTANCE_HIGH).apply {
                        setShowBadge(false); setSound(null, null); enableVibration(false)
                    },
                )
            }
            val pi = PendingIntent.getActivity(app, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Remote Control")
                .setContentText("Waking screen")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
            main.postDelayed({ nm.cancel(NOTIF_ID) }, 5_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Full-screen intent failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "RemoteAgent"
        const val CHANNEL = "rc_wake"
        const val NOTIF_ID = 7007
    }
}
