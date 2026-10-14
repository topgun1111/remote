package com.remotecontrol.controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.remotecontrol.shared.ScreenFrame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Latest-frame-wins decoder. [submit] may be called from any thread (it is called from the network
 * thread); base64 + JPEG decoding happens on one background thread; if frames arrive faster than they
 * can be decoded the older pending frame is replaced and counted as dropped. Nothing queues up.
 * While [enabled] is false frames are discarded without decoding.
 */
class FrameRenderer(
    private val view: ScreenView,
    private val onShown: () -> Unit, // main thread, after each frame is handed to the view
) {
    data class Stats(val received: Int, val decoded: Int, val dropped: Int, val failed: Int, val bytes: Long)

    @Volatile var enabled = false

    private val pending = AtomicReference<ScreenFrame?>(null)
    private val busy = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "frame-decoder").apply { isDaemon = true } }

    private val received = AtomicInteger()
    private val decoded = AtomicInteger()
    private val dropped = AtomicInteger()
    private val failed = AtomicInteger()
    private val bytes = AtomicLong()

    fun submit(frame: ScreenFrame) {
        if (!enabled) return
        received.incrementAndGet()
        if (pending.getAndSet(frame) != null) dropped.incrementAndGet()
        if (busy.compareAndSet(false, true)) executor.execute(::drain)
    }

    /** Returns the counters since the last call and resets them. */
    fun takeStats() = Stats(
        received.getAndSet(0), decoded.getAndSet(0), dropped.getAndSet(0), failed.getAndSet(0), bytes.getAndSet(0)
    )

    private fun drain() {
        try {
            while (true) {
                val f = pending.getAndSet(null) ?: break
                if (enabled) decode(f)
            }
        } finally {
            busy.set(false)
            // A frame may have slipped in between the last getAndSet and busy.set(false).
            if (pending.get() != null && busy.compareAndSet(false, true)) executor.execute(::drain)
        }
    }

    private fun decode(f: ScreenFrame) {
        val raw = try { Base64.decode(f.jpegBase64, Base64.DEFAULT) } catch (e: IllegalArgumentException) { null }
        val bmp: Bitmap? = raw?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp == null) { failed.incrementAndGet(); return }
        decoded.incrementAndGet()
        bytes.addAndGet((raw?.size ?: 0).toLong())
        view.post {
            if (enabled) {
                view.setFrame(bmp)
                onShown()
            }
        }
    }
}
