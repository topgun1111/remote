package com.remotecontrol.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Session 10a: the Agent's persistent notification. A minimised, silent, lock-screen-hidden channel (`agent_status_min`)
 * with one line of status (`AgentStatusText`). Android requires a visible notification for a foreground service, but
 * this one stays out of the way. A channel's importance cannot be lowered once created, hence the new id; old ids passed
 * to [ensureChannels] are deleted.
 */
object AgentNotification {
    const val CHANNEL_STATUS = "agent_status_min"
    const val CHANNEL_PROMPT = "agent_start_prompt"
    private const val PROMPT_ID = 2

    fun ensureChannels(context: Context, oldChannelIds: List<String> = emptyList()) {
        val nm = context.getSystemService(NotificationManager::class.java)
        for (id in oldChannelIds) if (id != CHANNEL_STATUS && id != CHANNEL_PROMPT) nm.deleteNotificationChannel(id)
        if (nm.getNotificationChannel(CHANNEL_STATUS) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Agent status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "One quiet line while the Agent runs"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
        }
        if (nm.getNotificationChannel(CHANNEL_PROMPT) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROMPT, "Agent start prompt", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown only when Android blocked the automatic start after a restart"
            })
        }
    }

    fun buildStatus(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_STATUS)
            .setContentTitle("Remote Control Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    fun update(context: Context, id: Int, text: String) {
        context.getSystemService(NotificationManager::class.java).notify(id, buildStatus(context, text))
    }

    /** "Tap to start the Agent": a tap is a user action, so Android allows the foreground-service start from it. */
    fun postStartPrompt(context: Context) {
        val start = PendingIntent.getForegroundService(
            context, 1, Intent(context, AgentService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL_PROMPT)
            .setContentTitle("Remote Control Agent is not running")
            .setContentText("Tap to start it again")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(start)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(PROMPT_ID, n)
    }

    fun clearStartPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)
    }
}
