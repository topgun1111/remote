package com.remotecontrol.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Session 8 (part 1) wire format: app list + launch. Like `INPUT_EVENT` and `WAKE`, these messages are handled
 * *before* `Protocol.decode`, so `Protocol.kt` (and `PROTOCOL_VERSION`) stay untouched.
 *
 * `{"type":"APP_LIST"}`                                   Controller -> Agent: send me the launchable apps
 * `{"type":"APP_LIST_RESULT","apps":[{"pkg","label","system"}]}`   Agent -> Controller
 * `{"type":"LAUNCH_APP","package":"com.example.app"}`     Controller -> Agent
 * `{"type":"LAUNCH_RESULT","ok","package","label","via","detail"}` Agent -> Controller
 *
 * `LAUNCH_RESULT.ok` means "the start was dispatched without error". Android silently blocks some background
 * activity starts and gives no error, so the live view is the real confirmation.
 */
data class AppEntry(val pkg: String, val label: String, val system: Boolean)

data class LaunchResult(
    val ok: Boolean,
    val pkg: String,
    val label: String,
    /** NONE (nothing started), DIRECT, DIRECT+NOTIFICATION */
    val via: String,
    val detail: String,
)

object AppsWire {
    const val TYPE_APP_LIST = "APP_LIST"
    const val TYPE_APP_LIST_RESULT = "APP_LIST_RESULT"
    const val TYPE_LAUNCH_APP = "LAUNCH_APP"
    const val TYPE_LAUNCH_RESULT = "LAUNCH_RESULT"

    fun listRequest() = """{"type":"APP_LIST"}"""

    fun launch(pkg: String): String = buildJsonObject {
        put("type", TYPE_LAUNCH_APP)
        put("package", pkg)
    }.toString()

    fun listResult(apps: List<AppEntry>): String = buildJsonObject {
        put("type", TYPE_APP_LIST_RESULT)
        put("apps", buildJsonArray {
            for (a in apps) add(buildJsonObject {
                put("pkg", a.pkg); put("label", a.label); put("system", a.system)
            })
        })
    }.toString()

    fun launchResult(r: LaunchResult): String = buildJsonObject {
        put("type", TYPE_LAUNCH_RESULT)
        put("ok", r.ok); put("package", r.pkg); put("label", r.label); put("via", r.via); put("detail", r.detail)
    }.toString()

    private fun parseObject(raw: String): JsonObject? =
        try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { null }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    /** Agent side: true only for a well-formed `APP_LIST` request. */
    fun isListRequest(raw: String): Boolean {
        if (!raw.contains("\"APP_LIST\"")) return false
        return parseObject(raw)?.str("type") == TYPE_APP_LIST
    }

    /** Agent side: the requested package, or null if [raw] is not a `LAUNCH_APP` message. May be invalid: check [AppsLogic.isValidPackage]. */
    fun parseLaunchRequest(raw: String): String? {
        if (!raw.contains("LAUNCH_APP")) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_LAUNCH_APP) return null
        return o.str("package") ?: ""
    }

    /** Controller side: null for anything that is not an `APP_LIST_RESULT`. Entries without a package are skipped. */
    fun parseListResult(raw: String): List<AppEntry>? {
        if (!raw.contains("APP_LIST_RESULT")) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_APP_LIST_RESULT) return null
        val arr: JsonArray = try { o["apps"]?.jsonArray } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<AppEntry>(arr.size)
        for (e in arr) {
            val a = try { e.jsonObject } catch (_: Exception) { continue }
            val pkg = a.str("pkg") ?: continue
            out += AppEntry(pkg, a.str("label")?.takeIf { it.isNotBlank() } ?: pkg, a.bool("system") ?: false)
        }
        return out
    }

    /** Controller side: null for anything that is not a `LAUNCH_RESULT`. */
    fun parseLaunchResult(raw: String): LaunchResult? {
        if (!raw.contains("LAUNCH_RESULT")) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_LAUNCH_RESULT) return null
        return LaunchResult(
            ok = o.bool("ok") ?: false,
            pkg = o.str("package") ?: "",
            label = o.str("label") ?: "",
            via = o.str("via") ?: "NONE",
            detail = o.str("detail") ?: "",
        )
    }
}
