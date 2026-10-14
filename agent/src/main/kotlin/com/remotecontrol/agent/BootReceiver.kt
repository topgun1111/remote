package com.remotecontrol.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Session 10a: after a restart or an app update, start `AgentService` again, but only if the switch is on and the Agent
 * was running (see [AutoStart]). If Android refuses the background start, a "tap to start" notification is posted.
 * Screen capture cannot be restarted (Android asks for consent every time): start it once on the Agent phone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        startAgent(context, a?.substringAfterLast('.') ?: "boot")
    }

    companion object {
        /** Also used by the boot self-test in RC Reliability Setup ([source] only labels the log line). */
        fun startAgent(context: Context, source: String): String {
            val app = context.applicationContext
            val outcome = when {
                !AutoStart.enabled(app) -> "not started: start-at-boot switch is off"
                !AutoStart.wasRunning(app) -> "not started: the Agent was stopped on purpose"
                else -> try {
                    AgentService.start(app)
                    "started the Agent service"
                } catch (e: Exception) {
                    // Android 12+ may refuse a background foreground-service start; the person can still tap to start.
                    Log.w("RemoteAgent", "Boot start refused: $e")
                    AgentNotification.ensureChannels(app, listOf("agent"))
                    AgentNotification.postStartPrompt(app)
                    "start refused by Android (${e.javaClass.simpleName}); posted a tap-to-start notification"
                }
            }
            Log.i("RemoteAgent", "Boot/update ($source): $outcome")
            AutoStart.recordBoot(app, "$source: $outcome")
            return outcome
        }
    }
}
