package com.remotecontrol.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Session 8d wire format: app icons for the app list. Handled before `Protocol.decode`, `PROTOCOL_VERSION` unchanged.
 *
 * `{"type":"APP_ICON","package":"com.example.app","size":96}`   Controller -> Agent (size in px, clamped 32..192)
 * `{"type":"APP_ICON_RESULT","package","ok","png":"<base64 PNG>"}`   Agent -> Controller
 *
 * One request per icon (the Controller keeps a few in flight and asks only for rows that are on screen).
 */
data class AppIconRequest(val pkg: String, val size: Int)

data class AppIconResult(val pkg: String, val ok: Boolean, val png: String)

object AppIconsWire {
    const val TYPE_REQUEST = "APP_ICON"
    const val TYPE_RESULT = "APP_ICON_RESULT"
    const val MIN_SIZE = 32
    const val MAX_SIZE = 192
    const val DEFAULT_SIZE = 96

    fun request(pkg: String, size: Int = DEFAULT_SIZE): String = buildJsonObject {
        put("type", TYPE_REQUEST); put("package", pkg); put("size", size)
    }.toString()

    fun result(r: AppIconResult): String = buildJsonObject {
        put("type", TYPE_RESULT); put("package", r.pkg); put("ok", r.ok); put("png", r.png)
    }.toString()

    private fun parseObject(raw: String): JsonObject? =
        try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { null }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** Agent side: null if [raw] is not an `APP_ICON` (an `APP_ICON_RESULT` does not match). Size is clamped. */
    fun parseRequest(raw: String): AppIconRequest? {
        if (!raw.contains(TYPE_REQUEST)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_REQUEST) return null
        val pkg = o.str("package")?.takeIf { it.isNotBlank() } ?: return null
        val size = ((o["size"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_SIZE.toLong()).coerceIn(MIN_SIZE.toLong(), MAX_SIZE.toLong()).toInt()
        return AppIconRequest(pkg, size)
    }

    /** Controller side: null for anything that is not an `APP_ICON_RESULT`. */
    fun parseResult(raw: String): AppIconResult? {
        if (!raw.contains(TYPE_RESULT)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_RESULT) return null
        return AppIconResult(
            o.str("package") ?: return null,
            (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
            o.str("png") ?: "",
        )
    }
}
