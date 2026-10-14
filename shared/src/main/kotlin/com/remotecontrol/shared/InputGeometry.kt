package com.remotecontrol.shared

/**
 * Pure-JVM maths for input injection (Session 5, Agent side). Controller coordinates are normalized
 * 0..1 of the streamed frame; the frame is the whole real screen scaled uniformly, so normalized
 * values map straight onto the real display in pixels (no letterbox on the Agent side).
 */
object InputGeometry {
    /** Maps normalized (nx, ny) to pixel coordinates inside a [screenW] x [screenH] display (clamped to the last pixel). */
    fun toScreen(nx: Float, ny: Float, screenW: Int, screenH: Int): Pair<Float, Float> {
        val x = nx.coerceIn(0f, 1f) * (screenW - 1).coerceAtLeast(0)
        val y = ny.coerceIn(0f, 1f) * (screenH - 1).coerceAtLeast(0)
        return x to y
    }

    /**
     * Scroll as a finger swipe centred on (cx, cy). [dx]/[dy] are how far the *content* should move
     * in normalized screen units: dy > 0 scrolls down (finger travels up), dx > 0 scrolls right
     * (finger travels left). Returns normalized [startX, startY, endX, endY], clamped to 0..1.
     */
    fun scrollSwipe(cx: Float, cy: Float, dx: Float, dy: Float): FloatArray = floatArrayOf(
        (cx + dx / 2f).coerceIn(0f, 1f),
        (cy + dy / 2f).coerceIn(0f, 1f),
        (cx - dx / 2f).coerceIn(0f, 1f),
        (cy - dy / 2f).coerceIn(0f, 1f),
    )

    /**
     * False when the Controller's frame and the Agent's current screen have different orientations
     * (the phone rotated after that frame was captured), so its coordinates would land wrongly.
     * Unknown frame size (<= 0) is accepted.
     */
    fun orientationMatches(frameW: Int, frameH: Int, screenW: Int, screenH: Int): Boolean {
        if (frameW <= 0 || frameH <= 0 || screenW <= 0 || screenH <= 0) return true
        return (frameW >= frameH) == (screenW >= screenH)
    }
}
