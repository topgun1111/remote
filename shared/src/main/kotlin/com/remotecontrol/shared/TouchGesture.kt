package com.remotecontrol.shared

import java.util.Locale
import kotlin.math.hypot

/** A finished one-finger gesture in *view pixels* (Session 6, Controller side). */
sealed class Gesture {
    data class Tap(val x: Float, val y: Float) : Gesture()
    data class LongPress(val x: Float, val y: Float, val durationMs: Long) : Gesture()
    data class Swipe(val x: Float, val y: Float, val x2: Float, val y2: Float, val durationMs: Long) : Gesture()
}

/**
 * Pure-JVM single-finger gesture classifier. Feed it down/move/up with timestamps (ms); [up] returns the
 * gesture. Gestures are classified on release (no timers), so a long press fires when the finger lifts.
 *
 * - finger stays within [slopPx] of the start: Tap, or LongPress when held >= [longPressMs]
 * - finger travelled beyond [slopPx] and ended beyond it: Swipe (drag path is reduced to start -> end)
 * - travelled out and came back inside the slop: ignored (null)
 */
class TouchGestureClassifier(private val slopPx: Float, private val longPressMs: Long = 500L) {
    private var active = false
    private var moved = false
    private var sx = 0f
    private var sy = 0f
    private var st = 0L

    val isActive get() = active

    fun down(x: Float, y: Float, t: Long) {
        active = true; moved = false; sx = x; sy = y; st = t
    }

    fun move(x: Float, y: Float) {
        if (active && !moved && hypot(x - sx, y - sy) > slopPx) moved = true
    }

    fun up(x: Float, y: Float, t: Long): Gesture? {
        if (!active) return null
        active = false
        val dur = (t - st).coerceAtLeast(0L)
        val endFar = hypot(x - sx, y - sy) > slopPx
        return when {
            endFar -> Gesture.Swipe(sx, sy, x, y, dur.coerceAtLeast(50L))
            moved -> null
            dur >= longPressMs -> Gesture.LongPress(sx, sy, dur)
            else -> Gesture.Tap(sx, sy)
        }
    }

    fun cancel() {
        active = false; moved = false
    }
}

/** Builds the `INPUT_EVENT` JSON text frames the Agent parses in `InputCommand.kt`. Coordinates are normalized 0..1. */
object InputWire {
    private fun n(v: Float) = String.format(Locale.US, "%.4f", v.coerceIn(0f, 1f))

    private fun frame(action: String, body: String, fw: Int, fh: Int): String {
        val size = if (fw > 0 && fh > 0) ""","frameW":$fw,"frameH":$fh""" else ""
        return """{"type":"INPUT_EVENT","action":"$action"$body$size}"""
    }

    fun tap(x: Float, y: Float, fw: Int = 0, fh: Int = 0) =
        frame("TAP", ""","x":${n(x)},"y":${n(y)}""", fw, fh)

    fun longPress(x: Float, y: Float, durationMs: Long, fw: Int = 0, fh: Int = 0) =
        frame("LONG_PRESS", ""","x":${n(x)},"y":${n(y)},"durationMs":${durationMs.coerceIn(400L, 3000L)}""", fw, fh)

    fun swipe(x: Float, y: Float, x2: Float, y2: Float, durationMs: Long, fw: Int = 0, fh: Int = 0) =
        frame(
            "SWIPE",
            ""","x":${n(x)},"y":${n(y)},"x2":${n(x2)},"y2":${n(y2)},"durationMs":${durationMs.coerceIn(50L, 5000L)}""",
            fw, fh,
        )

    /** [key] is one of BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, POWER_DIALOG, SCREENSHOT, BACKSPACE, ENTER. */
    fun key(key: String) = frame("KEY", ""","key":"${key.uppercase()}"""", 0, 0)

    fun text(text: String) = frame("TEXT", ""","text":"${escape(text)}"""", 0, 0)

    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append(String.format("\\u%04x", c.code))
            else -> append(c)
        }
    }

    /** Wire event for a gesture; [map] converts view pixels to normalized frame coords (null = outside the frame). */
    fun fromGesture(g: Gesture, fw: Int, fh: Int, map: (Float, Float) -> Pair<Float, Float>?): String? = when (g) {
        is Gesture.Tap -> map(g.x, g.y)?.let { tap(it.first, it.second, fw, fh) }
        is Gesture.LongPress -> map(g.x, g.y)?.let { longPress(it.first, it.second, g.durationMs, fw, fh) }
        is Gesture.Swipe -> {
            val a = map(g.x, g.y)
            val b = map(g.x2, g.y2)
            if (a == null || b == null) null else swipe(a.first, a.second, b.first, b.second, g.durationMs, fw, fh)
        }
    }
}
