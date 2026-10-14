package com.remotecontrol.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Session 8b wire format: file browser + chunked DOWNLOAD (Agent -> Controller). Handled before `Protocol.decode`
 * like WAKE/APP_LIST, so `Protocol.kt` and `PROTOCOL_VERSION` stay untouched. Upload is Session 8c.
 *
 * `{"type":"FILE_LIST","path":"/storage/emulated/0"}`  Controller -> Agent; path "" = the list of roots
 * `{"type":"FILE_LIST_RESULT","ok","path","error","truncated","entries":[{"name","dir","size","mtime","path"?}]}`
 * `{"type":"FILE_READ","path","offset","len"}`         Controller -> Agent: one chunk, pull-based (flow control + resume)
 * `{"type":"FILE_CHUNK","ok","path","offset","total","data"(base64),"eof","error"}`  Agent -> Controller
 *
 * Paths are absolute on the Agent. `entries[].path` is only set for roots (otherwise: join(path, name)).
 */
data class FileEntry(val name: String, val dir: Boolean, val size: Long, val mtime: Long, val path: String = "")

data class FileListResult(
    val ok: Boolean, val path: String, val error: String,
    val entries: List<FileEntry>, val truncated: Boolean = false,
)

data class FileReadRequest(val path: String, val offset: Long, val len: Int)

data class FileChunk(
    val ok: Boolean, val path: String, val offset: Long, val total: Long,
    val data: String, val eof: Boolean, val error: String,
)

object FilesWire {
    const val TYPE_LIST = "FILE_LIST"
    const val TYPE_LIST_RESULT = "FILE_LIST_RESULT"
    const val TYPE_READ = "FILE_READ"
    const val TYPE_CHUNK = "FILE_CHUNK"

    fun listRequest(path: String): String = buildJsonObject { put("type", TYPE_LIST); put("path", path) }.toString()

    fun readRequest(path: String, offset: Long, len: Int): String = buildJsonObject {
        put("type", TYPE_READ); put("path", path); put("offset", offset); put("len", len)
    }.toString()

    fun listResult(r: FileListResult): String = buildJsonObject {
        put("type", TYPE_LIST_RESULT)
        put("ok", r.ok); put("path", r.path); put("error", r.error); put("truncated", r.truncated)
        put("entries", buildJsonArray {
            for (e in r.entries) add(buildJsonObject {
                put("name", e.name); put("dir", e.dir); put("size", e.size); put("mtime", e.mtime)
                if (e.path.isNotEmpty()) put("path", e.path)
            })
        })
    }.toString()

    fun chunk(c: FileChunk): String = buildJsonObject {
        put("type", TYPE_CHUNK)
        put("ok", c.ok); put("path", c.path); put("offset", c.offset); put("total", c.total)
        put("data", c.data); put("eof", c.eof); put("error", c.error)
    }.toString()

    private fun parseObject(raw: String): JsonObject? =
        try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { null }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    /** Agent side: the requested path ("" = roots), or null if [raw] is not a `FILE_LIST`. */
    fun parseListRequest(raw: String): String? {
        if (!raw.contains(TYPE_LIST)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_LIST) return null
        return o.str("path") ?: ""
    }

    /** Agent side: null if [raw] is not a `FILE_READ`. Missing offset = 0, missing/invalid len = [FilesLogic.DEFAULT_CHUNK]; len is clamped. */
    fun parseReadRequest(raw: String): FileReadRequest? {
        if (!raw.contains(TYPE_READ)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_READ) return null
        return FileReadRequest(
            path = o.str("path") ?: "",
            offset = (o.long("offset") ?: 0L).coerceAtLeast(0L),
            len = FilesLogic.clampLen(o.long("len")?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: FilesLogic.DEFAULT_CHUNK),
        )
    }

    /** Controller side: null for anything that is not a `FILE_LIST_RESULT`. Entries without a name are skipped. */
    fun parseListResult(raw: String): FileListResult? {
        if (!raw.contains(TYPE_LIST_RESULT)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_LIST_RESULT) return null
        val arr = try { o["entries"]?.jsonArray } catch (_: Exception) { null }
        val out = ArrayList<FileEntry>(arr?.size ?: 0)
        if (arr != null) for (e in arr) {
            val a = try { e.jsonObject } catch (_: Exception) { continue }
            val name = a.str("name")?.takeIf { it.isNotEmpty() } ?: continue
            out += FileEntry(name, a.bool("dir") ?: false, a.long("size") ?: 0L, a.long("mtime") ?: 0L, a.str("path") ?: "")
        }
        return FileListResult(o.bool("ok") ?: false, o.str("path") ?: "", o.str("error") ?: "", out, o.bool("truncated") ?: false)
    }

    /** Controller side: null for anything that is not a `FILE_CHUNK`. */
    fun parseChunk(raw: String): FileChunk? {
        if (!raw.contains(TYPE_CHUNK)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_CHUNK) return null
        return FileChunk(
            ok = o.bool("ok") ?: false, path = o.str("path") ?: "", offset = o.long("offset") ?: 0L,
            total = o.long("total") ?: 0L, data = o.str("data") ?: "", eof = o.bool("eof") ?: false,
            error = o.str("error") ?: "",
        )
    }
}
