package com.remotecontrol.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.remotecontrol.shared.FitRect
import com.remotecontrol.shared.FrameGeometry

/**
 * Draws the latest Agent frame scaled to fit (aspect ratio preserved, centred on black).
 * Main-thread only. [frameFit] and [toNormalized] are what Session 6 uses to map touches.
 */
class ScreenView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    var frameFit: FitRect? = null
        private set

    val hasFrame: Boolean get() = bitmap != null

    /** (width, height) of the frame currently shown, or null when there is none yet. Sent with every touch event. */
    val frameSize: Pair<Int, Int>? get() = bitmap?.let { it.width to it.height }

    fun setFrame(b: Bitmap) {
        // The previous bitmap is not recycled on purpose: a hardware-accelerated draw may still hold it.
        bitmap = b
        recompute()
        invalidate()
    }

    fun clearFrame() {
        bitmap = null
        frameFit = null
        invalidate()
    }

    /** View-space point -> 0..1 of the frame; null in the letterbox bars unless [clamp]. */
    fun toNormalized(x: Float, y: Float, clamp: Boolean = false): Pair<Float, Float>? =
        frameFit?.let { FrameGeometry.toNormalized(it, x, y, clamp) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recompute()
    }

    private fun recompute() {
        val b = bitmap
        frameFit = if (b == null) null else FrameGeometry.fit(b.width, b.height, width, height)
        frameFit?.let { dst.set(it.left, it.top, it.right, it.bottom) }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = bitmap ?: return
        canvas.drawBitmap(b, null, dst, paint)
    }
}
