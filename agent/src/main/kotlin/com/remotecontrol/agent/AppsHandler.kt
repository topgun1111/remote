package com.remotecontrol.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.remotecontrol.shared.AppsLogic
import com.remotecontrol.shared.AppsWire
import com.remotecontrol.shared.LaunchResult
import com.remotecontrol.shared.LaunchStep
import java.util.concurrent.Executors

/**
 * Handles `APP_LIST` and `LAUNCH_APP` (Session 8, part 1). Decisions come from the pure [AppsLogic]; this class
 * only does the Android calls. Create one instance with the application context and call [tryHandle] from
 * `AgentServer.onMessage` for authenticated clients, before `Protocol.decode` (next to the WAKE hook).
 */
class AppsHandler(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rc-apps").apply { isDaemon = true } }
    private val lister = AppLister(app)

    /** True (and answered via [reply]) if [raw] is `APP_LIST` or `LAUNCH_APP`; false for any other message. [reply] may be called from any thread. */
    fun tryHandle(raw: String, reply: (String) -> Unit): Boolean {
        if (AppsWire.isListRequest(raw)) {
            worker.execute {
                try {
                    val apps = lister.list()
                    Log.i(TAG, "APP_LIST -> ${apps.size} apps")
                    reply(AppsWire.listResult(apps))
                } catch (e: Exception) {
                    Log.w(TAG, "APP_LIST failed: ${e.message}")
                    reply(AppsWire.listResult(emptyList()))
                }
            }
            return true
        }
        val pkg = AppsWire.parseLaunchRequest(raw) ?: return false
        launch(pkg) { reply(AppsWire.launchResult(it)) }
        return true
    }

    /** Runs on the main thread; [done] is called on the main thread. Also used by the setup activity's self-test. */
    fun launch(pkg: String, done: (LaunchResult) -> Unit) {
        main.post {
            val valid = AppsLogic.isValidPackage(pkg)
            val installed = valid && lister.isInstalled(pkg)
            val intent = if (installed) lister.launchIntent(pkg) else null
            val label = if (installed) lister.label(pkg) else pkg
            val canDraw = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app)
            val steps = AppsLogic.plan(installed, intent != null, canDraw, Build.VERSION.SDK_INT)
            Log.i(TAG, "LAUNCH_APP $pkg installed=$installed overlay=$canDraw steps=$steps")

            var attempted = false
            var startError: String? = null
            var notified = false
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                for (s in steps) when (s) {
                    LaunchStep.START_ACTIVITY -> {
                        attempted = true
                        try { app.startActivity(intent) } catch (e: Exception) {
                            startError = e.message ?: e.javaClass.simpleName
                            Log.w(TAG, "startActivity failed: $startError")
                        }
                    }
                    LaunchStep.POST_NOTIFICATION -> notified = postFallback(pkg, label, intent)
                }
            }
            val result = AppsLogic.outcome(pkg, label, installed, intent != null, attempted, startError, notified, canDraw)
            Log.i(TAG, "LAUNCH_RESULT ok=${result.ok} via=${result.via} ${result.detail}")
            done(result)
        }
    }

    /**
     * Notification that opens the app: `contentIntent` (tap) plus a full-screen intent, which can start it with
     * the screen off/locked (Android 14+: needs the "full-screen notifications" permission from RC Wake Setup).
     */
    private fun postFallback(pkg: String, label: String, launch: Intent): Boolean = try {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Remote app launch", NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false); setSound(null, null); enableVibration(false)
                },
            )
        }
        val pi = PendingIntent.getActivity(
            app, pkg.hashCode(), Intent(launch), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Open $label")
            .setContentText("Requested from the Controller. Tap if it did not open by itself.")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
        main.postDelayed({ nm.cancel(NOTIF_ID) }, 8_000L)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Fallback notification failed: ${e.message}")
        false
    }

    private companion object {
        const val TAG = "RemoteAgent"
        const val CHANNEL = "rc_launch"
        const val NOTIF_ID = 7008
    }
}
