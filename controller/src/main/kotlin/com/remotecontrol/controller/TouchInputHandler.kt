package com.remotecontrol.controller

import android.view.MotionEvent
import com.remotecontrol.shared.InputWire
import com.remotecontrol.shared.TouchGestureClassifier

/**
 * Turns touches on the live view into `INPUT_EVENT` messages (Session 6).
 *
 * @param slopPx        movement tolerance for tap vs swipe, e.g. `8 * resources.displayMetrics.density`
 * @param toNormalized  view pixels -> normalized 0..1 frame coords, or null when outside the frame
 *                      (wrap `ScreenView.toNormalized(x, y, clamp = false)`)
 * @param frameSize     (width, height) of the frame currently shown, or null if none yet
 * @param send          sends one JSON text frame to the Agent; false if not connected
 * @param onSent        optional hook (e.g. flash a ripple); onDropped: touch ignored (outside frame / link down)
 */
class TouchInputHandler(
    slopPx: Float,
    private val toNormalized: (Float, Float) -> Pair<Float, Float>?,
    private val frameSize: () -> Pair<Int, Int>?,
    private val send: (String) -> Boolean,
    private val onSent: ((String) -> Unit)? = null,
    private val onDropped: (() -> Unit)? = null,
) {
    private val classifier = TouchGestureClassifier(slopPx)
    private var multiTouch = false

    /** Call from `View.setOnTouchListener` / `onTouchEvent`; always consumes the event. */
    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { multiTouch = false; classifier.down(e.x, e.y, e.eventTime) }
            MotionEvent.ACTION_MOVE -> if (!multiTouch) classifier.move(e.x, e.y)
            // Second finger: pinch/multi-touch is not supported yet, so drop the whole gesture.
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; classifier.cancel() }
            MotionEvent.ACTION_UP -> {
                if (!multiTouch) classifier.up(e.x, e.y, e.eventTime)?.let { g ->
                    val (fw, fh) = frameSize() ?: (0 to 0)
                    val json = InputWire.fromGesture(g, fw, fh, toNormalized)
                    if (json != null && send(json)) onSent?.invoke(json) else onDropped?.invoke()
                }
                multiTouch = false
            }
            MotionEvent.ACTION_CANCEL -> { classifier.cancel(); multiTouch = false }
        }
        return true
    }
}
