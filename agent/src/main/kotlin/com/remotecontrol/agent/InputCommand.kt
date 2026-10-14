package com.remotecontrol.agent

import org.json.JSONObject

/**
 * A decoded `INPUT_EVENT`. Coordinates are normalized 0..1 of the streamed frame.
 *
 * Wire format (JSON text frame; the Controller sends this in Session 6):
 * ```
 * {"type":"INPUT_EVENT","action":"TAP","x":0.5,"y":0.5,"frameW":720,"frameH":1600}
 * TAP         x y
 * LONG_PRESS  x y [durationMs=700]
 * SWIPE       x y x2 y2 [durationMs=300]
 * SCROLL      [x=0.5 y=0.5] dx dy      (content movement, normalized; dy>0 = scroll down)
 * KEY         key = BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS|LOCK_SCREEN|POWER_DIALOG|SCREENSHOT|BACKSPACE|ENTER
 * TEXT        text                     (appended to the focused input field)
 * ```
 * `frameW`/`frameH` are optional; if given and the Agent has rotated since, the event is dropped.
 */
sealed class InputCommand {
    data class Tap(val x: Float, val y: Float) : InputCommand()
    data class LongPress(val x: Float, val y: Float, val durationMs: Long) : InputCommand()
    data class Swipe(val x: Float, val y: Float, val x2: Float, val y2: Float, val durationMs: Long) : InputCommand()
    data class Scroll(val x: Float, val y: Float, val dx: Float, val dy: Float) : InputCommand()
    data class Key(val key: String) : InputCommand()
    data class Text(val text: String) : InputCommand()

    /** Controller's frame size when the event was made; 0 = unknown. */
    var frameW: Int = 0
    var frameH: Int = 0

    companion object {
        val KEYS = setOf(
            "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS",
            "LOCK_SCREEN", "POWER_DIALOG", "SCREENSHOT", "BACKSPACE", "ENTER",
        )
        const val MAX_TEXT = 2000

        /** Cheap pre-check so ordinary messages skip JSON parsing. */
        fun looksLikeInput(message: String) = message.contains("\"INPUT_EVENT\"")

        /** Returns null for anything that is not a well-formed INPUT_EVENT (never throws). */
        fun parse(message: String): InputCommand? = try {
            val o = JSONObject(message)
            if (o.optString("type") != "INPUT_EVENT") null else build(o)?.also {
                it.frameW = o.optInt("frameW", 0)
                it.frameH = o.optInt("frameH", 0)
            }
        } catch (_: Exception) {
            null
        }

        private fun JSONObject.f(name: String): Float? =
            if (has(name) && !isNull(name)) optDouble(name, Double.NaN).takeIf { it.isFinite() }?.toFloat() else null

        private fun JSONObject.coord(name: String): Float? = f(name)?.takeIf { it in 0f..1f }

        private fun build(o: JSONObject): InputCommand? {
            return when (o.optString("action").uppercase()) {
                "TAP" -> Tap(o.coord("x") ?: return null, o.coord("y") ?: return null)
                "LONG_PRESS" -> LongPress(
                    o.coord("x") ?: return null, o.coord("y") ?: return null,
                    o.optLong("durationMs", 700L).coerceIn(400L, 3000L),
                )
                "SWIPE" -> Swipe(
                    o.coord("x") ?: return null, o.coord("y") ?: return null,
                    o.coord("x2") ?: return null, o.coord("y2") ?: return null,
                    o.optLong("durationMs", 300L).coerceIn(50L, 5000L),
                )
                "SCROLL" -> Scroll(
                    o.coord("x") ?: 0.5f, o.coord("y") ?: 0.5f,
                    (o.f("dx") ?: 0f).coerceIn(-1f, 1f), (o.f("dy") ?: 0f).coerceIn(-1f, 1f),
                ).takeIf { it.dx != 0f || it.dy != 0f }
                "KEY" -> o.optString("key").uppercase().takeIf { it in KEYS }?.let { Key(it) }
                "TEXT" -> o.optString("text").takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }?.let { Text(it) }
                else -> null
            }
        }
    }
}
