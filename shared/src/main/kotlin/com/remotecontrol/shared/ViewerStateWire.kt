package com.remotecontrol.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Session 10b wire format: `{"type":"VIEWER_STATE","visible":false}` Controller -> Agent, no reply. Handled before
 * `Protocol.decode` (routed through `FilesHandler`), `PROTOCOL_VERSION` unchanged; older Agents ignore it.
 */
object ViewerStateWire {
    const val TYPE = "VIEWER_STATE"

    fun build(visible: Boolean): String = buildJsonObject { put("type", TYPE); put("visible", visible) }.toString()

    private fun obj(raw: String): JsonObject? = try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { null }

    /** `true`/`false` for a `VIEWER_STATE` message, null for anything else (including a missing or non-boolean `visible`). */
    fun parse(raw: String): Boolean? {
        if (!raw.contains(TYPE)) return null
        val o = obj(raw) ?: return null
        if ((o["type"] as? JsonPrimitive)?.contentOrNull != TYPE) return null
        return (o["visible"] as? JsonPrimitive)?.booleanOrNull
    }
}
