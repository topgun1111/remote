package com.remotecontrol.shared

/** A rectangle in view coordinates (pixels), origin top-left. */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
}

/**
 * Pure-JVM letterbox math shared by rendering (Session 4) and touch mapping (Session 6):
 * a frame is scaled uniformly to fit the view and centred; touches map back to 0..1 of the frame.
 */
object FrameGeometry {
    /** Where the frame is drawn inside the view, or null if any dimension is not positive. */
    fun fit(frameW: Int, frameH: Int, viewW: Int, viewH: Int): FitRect? {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0 || viewH <= 0) return null
        val scale = minOf(viewW.toFloat() / frameW, viewH.toFloat() / frameH)
        val w = frameW * scale
        val h = frameH * scale
        return FitRect((viewW - w) / 2f, (viewH - h) / 2f, w, h)
    }

    /**
     * Maps a view-space point to normalized 0..1 frame coordinates. Points in the letterbox bars
     * return null, or are clamped to the frame edge when [clamp] is true.
     */
    fun toNormalized(rect: FitRect, x: Float, y: Float, clamp: Boolean = false): Pair<Float, Float>? {
        if (rect.width <= 0f || rect.height <= 0f) return null
        if (!clamp && !rect.contains(x, y)) return null
        val nx = ((x - rect.left) / rect.width).coerceIn(0f, 1f)
        val ny = ((y - rect.top) / rect.height).coerceIn(0f, 1f)
        return nx to ny
    }
}
