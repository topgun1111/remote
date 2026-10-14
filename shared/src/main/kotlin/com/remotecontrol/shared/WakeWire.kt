package com.remotecontrol.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Session 7 wire format for `WAKE` (Controller -> Agent) and `WAKE_RESULT` (Agent -> Controller).
 * Like `INPUT_EVENT`, both are handled *before* `Protocol.decode` so `Protocol.kt` is untouched.
 *
 * `{"type":"WAKE","unlock":true}`   wake the screen and try to dismiss the keyguard
 * `{"type":"WAKE","unlock":false}`  wake the screen only
 * `{"type":"WAKE","lock":true}`     turn the screen off / lock (needs Device Admin)
 * `{"type":"WAKE_RESULT","ok":..,"screenOn":..,"locked":..,"secure":..,"admin":..,"detail":".."}`
 */
data class WakeRequest(val unlock: Boolean = true, val lock: Boolean = false)

data class WakeResult(
    val ok: Boolean,
    val screenOn: Boolean,
    val locked: Boolean,
    val secure: Boolean,
    val admin: Boolean,
    val detail: String,
)

object WakeWire {
    const val TYPE_WAKE = "WAKE"
    const val TYPE_WAKE_RESULT = "WAKE_RESULT"

    fun wake(unlock: Boolean = true) = """{"type":"WAKE","unlock":$unlock}"""
    fun lock() = """{"type":"WAKE","lock":true}"""

    fun result(r: WakeResult): String = buildString {
        append("""{"type":"WAKE_RESULT","ok":${r.ok},"screenOn":${r.screenOn},"locked":${r.locked},""")
        append(""""secure":${r.secure},"admin":${r.admin},"detail":""")
        append(JsonPrimitive(r.detail).toString())
        append("}")
    }

    private fun parseObject(raw: String): JsonObject? =
        try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { null }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    /** Cheap check used in the Agent's `onMessage`; true only for a well-formed WAKE message. */
    fun parseRequest(raw: String): WakeRequest? {
        if (!raw.contains("\"WAKE\"")) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_WAKE) return null
        return WakeRequest(unlock = o.bool("unlock") ?: true, lock = o.bool("lock") ?: false)
    }

    /** Used in the Controller's raw-message hook; null for anything that is not a WAKE_RESULT. */
    fun parseResult(raw: String): WakeResult? {
        if (!raw.contains("WAKE_RESULT")) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_WAKE_RESULT) return null
        return WakeResult(
            ok = o.bool("ok") ?: false,
            screenOn = o.bool("screenOn") ?: false,
            locked = o.bool("locked") ?: true,
            secure = o.bool("secure") ?: true,
            admin = o.bool("admin") ?: false,
            detail = o.str("detail") ?: "",
        )
    }
}
