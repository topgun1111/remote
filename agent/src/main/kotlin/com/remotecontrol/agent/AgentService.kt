package com.remotecontrol.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.remotecontrol.shared.AgentStatusText
import com.remotecontrol.shared.DEFAULT_PORT

/** Foreground service hosting the WebSocket server. START_STICKY so Android restarts it if killed. */
class AgentService : Service() {

    private var server: AgentServer? = null
    private var capturer: ScreenCapturer? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var keeper: ConnectivityKeeper
    private var controllers = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        keeper = ConnectivityKeeper(this)   // Session 7: CPU + Wi-Fi lock so the phone stays reachable with the screen off
        keeper.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentNotification.ensureChannels(this, listOf("agent"))   // "agent" = the pre-10a channel id, deleted
        AutoStart.markRunning(this, true)                           // cleared only by stop() = stopped on purpose
        AgentNotification.clearStartPrompt(this)
        // Always enter foreground first (also required before getMediaProjection on Android 14+).
        val wantCapture = intent?.action == ACTION_START_CAPTURE
        if (!promote(capturing = wantCapture || capturer != null)) { stopSelf(); return START_NOT_STICKY }
        if (server == null) {
            try {
                server = AgentServer(
                    this,
                    DEFAULT_PORT,
                    AgentGraph.authority(this),
                    Build.MODEL ?: "Agent",
                ).also { srv ->
                    AddressesHandler.listenPort = DEFAULT_PORT
                    srv.onControllersChanged = { n -> main.post { controllers = n; refreshNotification() } }
                    srv.start()
                }
                running = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                AgentLog.add("Failed to start: ${e.message}")
            }
        }

        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data != null) startCapture(code, data) else promote(capturing = capturer != null)
            }
            ACTION_STOP_CAPTURE -> stopCapture()
        }
        return START_STICKY
    }

    /** Enters/refreshes the foreground state. False when Android refused (posts the tap-to-start notification instead). */
    private fun promote(capturing: Boolean): Boolean {
        val n = AgentNotification.buildStatus(this, AgentStatusText.format(controllers, capturing))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (capturing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NOTIFICATION_ID, n, type)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refused: $e")
            AgentLog.add("Android blocked the background start; tap the notification to start the Agent")
            AgentNotification.postStartPrompt(this)
            false
        }
    }

    private fun refreshNotification() {
        AgentNotification.update(this, NOTIFICATION_ID, AgentStatusText.format(controllers, capturer != null))
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val srv = server
        if (srv == null) { AgentLog.add("Start the Agent first"); promote(false); return }
        capturer?.stop()
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection = mpm.getMediaProjection(resultCode, data)
            val c = ScreenCapturer(this, projection, srv) { main.post { onCaptureEnded() } }
            c.start()
            capturer = c
            promote(true)
            srv.broadcastStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Capture start failed", e)
            AgentLog.add("Capture failed: ${e.message}")
            capturer = null
            promote(false)
        }
    }

    private fun stopCapture() {
        capturer?.stop()
        onCaptureEnded()
    }

    private fun onCaptureEnded() {
        capturer = null
        promote(false)
        server?.broadcastStatus()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        capturer?.onDisplayChanged() // rotation -> new capture size
    }

    override fun onDestroy() {
        running = false
        keeper.release()
        StreamGate.onResume = null
        capturer?.stop()
        capturer = null
        val s = server
        server = null
        if (s != null) {
            // stop() blocks briefly; keep it off the main thread
            Thread {
                try { s.stop(500) } catch (e: Exception) { Log.w(TAG, "stop failed", e) }
            }.start()
            AgentLog.add("Agent stopped")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RemoteAgent"
        private const val NOTIFICATION_ID = 1
        const val KEY_ENABLED = "service_enabled"
        private const val ACTION_START_CAPTURE = "com.remotecontrol.agent.START_CAPTURE"
        private const val ACTION_STOP_CAPTURE = "com.remotecontrol.agent.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** Hands the MediaProjection consent result to the service (it must already be running). */
        fun startCapture(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, AgentService::class.java)
                    .setAction(ACTION_START_CAPTURE)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
            )
        }

        fun stopCapture(context: Context) {
            context.startForegroundService(Intent(context, AgentService::class.java).setAction(ACTION_STOP_CAPTURE))
        }

        @Volatile var running = false
            private set

        /** Starts the service and remembers that it should run, so the app restarts it on next launch. */
        fun start(context: Context) {
            AgentGraph.settings(context).edit().putBoolean(KEY_ENABLED, true).apply()
            AutoStart.markRunning(context, true)
            context.startForegroundService(Intent(context, AgentService::class.java))
        }

        fun stop(context: Context) {
            AgentGraph.settings(context).edit().putBoolean(KEY_ENABLED, false).apply()
            AutoStart.markRunning(context, false)
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
