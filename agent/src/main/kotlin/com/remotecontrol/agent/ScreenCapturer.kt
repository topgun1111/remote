package com.remotecontrol.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import com.remotecontrol.shared.CaptureConfig
import com.remotecontrol.shared.CaptureStatus
import com.remotecontrol.shared.Protocol
import com.remotecontrol.shared.ScreenFrame
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MediaProjection -> VirtualDisplay -> ImageReader -> JPEG -> [FrameSink].
 * Frames are throttled to the configured fps, skipped when nobody is watching, and dropped
 * when the socket is backed up (latest-frame-wins, no queue growth).
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val sink: FrameSink,
    private val onEnded: () -> Unit,
) {
    interface FrameSink {
        fun hasViewers(): Boolean
        fun sendFrame(json: String)
    }

    private val prefs = AgentGraph.settings(context)
    @Volatile private var config = CaptureConfig(
        fps = prefs.getInt(K_FPS, 5),
        quality = prefs.getInt(K_QUALITY, 60),
        maxDimension = prefs.getInt(K_MAXDIM, 720),
    ).clamped()

    private val released = AtomicBoolean(false)
    private val saveRequested = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var lastFrameJson: String? = null // newest encoded SCREEN_FRAME, replayed to joining Controllers
    @Volatile private var forceNext = false             // next real frame bypasses the fps throttle
    private var lastCacheAt = 0L
    private var lastFrameAt = 0L
    private var statFrames = 0
    private var statBytes = 0L
    private var statSince = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AgentLog.add("Screen capture stopped (by system/user)")
            release()
            onEnded()
        }
    }

    fun start() {
        val t = HandlerThread("rc-capture").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        // Android 14+: a callback must be registered before createVirtualDisplay.
        projection.registerCallback(projectionCallback, h)

        val (w, hh, dpi) = computeSize()
        width = w; height = hh
        val r = newReader(w, hh)
        reader = r
        display = projection.createVirtualDisplay(
            "rc-capture", w, hh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, h,
        )
        current = this
        AgentLog.add("Capture started ${w}x$hh @${config.fps}fps q${config.quality}")
    }

    fun stop() {
        if (released.get()) return
        release()
    }

    /** Applies (clamped) settings from the Controller or UI; resizes the virtual display if needed. */
    fun configure(c: CaptureConfig) {
        val n = c.clamped()
        val sizeChanged = n.maxDimension != config.maxDimension
        config = n
        prefs.edit().putInt(K_FPS, n.fps).putInt(K_QUALITY, n.quality).putInt(K_MAXDIM, n.maxDimension).apply()
        if (sizeChanged) onDisplayChanged()
    }

    /** Call on rotation / config change: recomputes the capture size and swaps the ImageReader. */
    fun onDisplayChanged() {
        handler?.post { resize() }
    }

    /**
     * A static screen yields no new images, so a Controller that connects (or reconfigures) would see
     * nothing. Returns the newest frame to replay to it and lets the next real frame through immediately.
     */
    fun requestKeyframe(): String? {
        forceNext = true
        return lastFrameJson
    }

    fun status() = CaptureStatus(!released.get(), width, height, config.fps, config.quality)

    fun describe() = "${width}x$height @${config.fps}fps q${config.quality} max${config.maxDimension}"

    /** Writes the latest frame (or the next one, if none yet) to the app's external files dir. */
    fun requestSave() {
        val jpeg = lastJpeg
        if (jpeg != null) saveJpeg(jpeg) else saveRequested.set(true)
    }

    private fun resize() {
        if (released.get()) return
        val (w, h, dpi) = computeSize()
        if (w == width && h == height) return
        val old = reader
        val nr = newReader(w, h)
        reader = nr
        display?.resize(w, h, dpi)
        display?.surface = nr.surface
        old?.close()
        width = w; height = h
        lastFrameJson = null // old-size frame must not be replayed
        forceNext = true
        AgentLog.add("Capture resized ${w}x$h")
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ onImage(it) }, handler)
        }

    private fun onImage(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            val wantSave = saveRequested.get()
            val viewers = sink.hasViewers()
            val due = forceNext || now - lastFrameAt >= 1000L / config.fps
            // With nobody watching, still refresh the replay cache about once a second.
            val cacheDue = !viewers && now - lastCacheAt >= 1000L
            if (!wantSave && !(viewers && due) && !cacheDue) return
            if (viewers) lastFrameAt = now else lastCacheAt = now

            val jpeg = encode(image, config.quality)
            lastJpeg = jpeg
            if (wantSave && saveRequested.compareAndSet(true, false)) saveJpeg(jpeg)

            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            val json = Protocol.encode(ScreenFrame(image.width, image.height, b64, System.currentTimeMillis()))
            lastFrameJson = json
            if (viewers) {
                forceNext = false
                sink.sendFrame(json)
                stats(now, jpeg.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun encode(image: Image, quality: Int): ByteArray {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val bmp = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val out = ByteArrayOutputStream(96 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bmp !== padded) bmp.recycle()
        padded.recycle()
        return out.toByteArray()
    }

    private fun stats(now: Long, bytes: Int) {
        if (statSince == 0L) statSince = now
        statFrames++; statBytes += bytes
        val dt = now - statSince
        if (dt >= 5000) {
            val msg = "Streaming %.1f fps, %d KB/frame".format(statFrames * 1000f / dt, statBytes / statFrames / 1024)
            Log.i(TAG, msg)
            AgentLog.add(msg)
            statFrames = 0; statBytes = 0; statSince = now
        }
    }

    private fun saveJpeg(bytes: ByteArray) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val f = File(dir, "test_frame.jpg")
            f.writeBytes(bytes)
            AgentLog.add("Saved test frame: ${f.absolutePath}")
            Log.i(TAG, "Saved test frame ${f.absolutePath} (${bytes.size} B)")
        } catch (e: Exception) {
            AgentLog.add("Save failed: ${e.message}")
        }
    }

    /** Returns (width, height, dpi) of the capture surface: real screen scaled so the long side <= maxDimension. */
    private fun computeSize(): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rw: Int
        val rh: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            // maximumWindowMetrics is orientation-independent on some devices; follow the current display config.
            val land = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            rw = if (land) max(b.width(), b.height()) else min(b.width(), b.height())
            rh = if (land) min(b.width(), b.height()) else max(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            rw = dm.widthPixels; rh = dm.heightPixels
        }
        val scale = min(1f, config.maxDimension.toFloat() / max(rw, rh))
        val w = even((rw * scale).roundToInt())
        val h = even((rh * scale).roundToInt())
        return Triple(w, h, context.resources.displayMetrics.densityDpi)
    }

    private fun even(v: Int) = max(2, v and 1.inv())

    private fun release() {
        if (!released.compareAndSet(false, true)) return
        if (current === this) current = null
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection.stop() } catch (_: Exception) {}
        thread?.quitSafely()
        display = null; reader = null
    }

    companion object {
        private const val TAG = "RemoteAgent"
        private const val K_FPS = "cap_fps"
        private const val K_QUALITY = "cap_quality"
        private const val K_MAXDIM = "cap_maxdim"

        /** The active capturer, if any (read by the server and the UI). */
        @Volatile var current: ScreenCapturer? = null
            private set

        private fun CaptureConfig.clamped() = CaptureConfig(
            fps = fps.coerceIn(1, 30),
            quality = quality.coerceIn(20, 95),
            maxDimension = maxDimension.coerceIn(240, 1920),
        )
    }
}
