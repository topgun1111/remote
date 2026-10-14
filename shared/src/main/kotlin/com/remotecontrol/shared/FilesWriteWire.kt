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
 * Session 8c wire format: chunked UPLOAD (Controller -> Agent) and folder/file operations. Handled before
 * `Protocol.decode` like the 8b messages, so `Protocol.kt` and `PROTOCOL_VERSION` stay untouched.
 *
 * `{"type":"FILE_WRITE","path":"/dir/name","offset":N,"data":"<base64>","eof":bool,"overwrite":bool,"abort":bool}`
 *     Controller -> Agent. The Agent keeps the upload in a hidden `.name.rcpart` file next to the target; `offset` must
 *     equal the bytes already stored (0 = start over). `eof` renames it to `name`. `abort` deletes the partial file.
 * `{"type":"FILE_WRITE_ACK","ok","path","offset","done","error"}`
 *     Agent -> Controller, one per chunk. `offset` = bytes now stored (the next expected offset); `done` after `eof`.
 *     On an offset mismatch `ok=false` and `offset` = what the Agent really has (basis for a later resume).
 * `{"type":"FILE_OP","op":"mkdir|delete|rename","path","name"}`
 *     mkdir: `path` = parent folder, `name` = new folder. delete: `path` = file or folder (folders recursively).
 *     rename: `path` = item, `name` = new name (same folder).
 *     partial (8d): `path` = upload target; asks how many bytes of an interrupted upload the Agent still holds.
 * `{"type":"FILE_OP_RESULT","ok","op","path","error","size"}`   `path` = the created / renamed item when ok;
 *     `size` only for `partial` (bytes in the hidden `.name.rcpart`, 0 = none).
 */
data class FileWriteRequest(
    val path: String, val offset: Long, val data: String,
    val eof: Boolean, val overwrite: Boolean, val abort: Boolean,
)

data class FileWriteAck(val ok: Boolean, val path: String, val offset: Long, val done: Boolean, val error: String)

data class FileOpRequest(val op: String, val path: String, val name: String)

data class FileOpResult(val ok: Boolean, val op: String, val path: String, val error: String, val size: Long = 0L)

object FilesWriteWire {
    const val TYPE_WRITE = "FILE_WRITE"
    const val TYPE_WRITE_ACK = "FILE_WRITE_ACK"
    const val TYPE_OP = "FILE_OP"
    const val TYPE_OP_RESULT = "FILE_OP_RESULT"

    const val OP_MKDIR = "mkdir"
    const val OP_DELETE = "delete"
    const val OP_RENAME = "rename"
    const val OP_PARTIAL = "partial"

    fun writeRequest(path: String, offset: Long, data: String, eof: Boolean, overwrite: Boolean): String = buildJsonObject {
        put("type", TYPE_WRITE); put("path", path); put("offset", offset); put("data", data)
        put("eof", eof); put("overwrite", overwrite); put("abort", false)
    }.toString()

    fun abortRequest(path: String): String = buildJsonObject {
        put("type", TYPE_WRITE); put("path", path); put("offset", 0L); put("data", "")
        put("eof", false); put("overwrite", false); put("abort", true)
    }.toString()

    fun writeAck(a: FileWriteAck): String = buildJsonObject {
        put("type", TYPE_WRITE_ACK)
        put("ok", a.ok); put("path", a.path); put("offset", a.offset); put("done", a.done); put("error", a.error)
    }.toString()

    fun opRequest(op: String, path: String, name: String = ""): String = buildJsonObject {
        put("type", TYPE_OP); put("op", op); put("path", path); put("name", name)
    }.toString()

    fun opResult(r: FileOpResult): String = buildJsonObject {
        put("type", TYPE_OP_RESULT)
        put("ok", r.ok); put("op", r.op); put("path", r.path); put("error", r.error); put("size", r.size)
    }.toString()

    private fun parseObject(raw: String): JsonObject? =
        try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { null }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    /** Agent side: null if [raw] is not a `FILE_WRITE` (a `FILE_WRITE_ACK` does not match). Negative offset is clamped to 0. */
    fun parseWriteRequest(raw: String): FileWriteRequest? {
        if (!raw.contains(TYPE_WRITE)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_WRITE) return null
        return FileWriteRequest(
            path = o.str("path") ?: "", offset = (o.long("offset") ?: 0L).coerceAtLeast(0L),
            data = o.str("data") ?: "", eof = o.bool("eof") ?: false,
            overwrite = o.bool("overwrite") ?: false, abort = o.bool("abort") ?: false,
        )
    }

    /** Controller side: null for anything that is not a `FILE_WRITE_ACK`. */
    fun parseWriteAck(raw: String): FileWriteAck? {
        if (!raw.contains(TYPE_WRITE_ACK)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_WRITE_ACK) return null
        return FileWriteAck(
            ok = o.bool("ok") ?: false, path = o.str("path") ?: "", offset = o.long("offset") ?: 0L,
            done = o.bool("done") ?: false, error = o.str("error") ?: "",
        )
    }

    /** Agent side: null if [raw] is not a `FILE_OP` (a `FILE_OP_RESULT` does not match). */
    fun parseOpRequest(raw: String): FileOpRequest? {
        if (!raw.contains(TYPE_OP)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_OP) return null
        return FileOpRequest(o.str("op") ?: "", o.str("path") ?: "", o.str("name") ?: "")
    }

    /** Controller side: null for anything that is not a `FILE_OP_RESULT`. */
    fun parseOpResult(raw: String): FileOpResult? {
        if (!raw.contains(TYPE_OP_RESULT)) return null
        val o = parseObject(raw) ?: return null
        if (o.str("type") != TYPE_OP_RESULT) return null
        return FileOpResult(o.bool("ok") ?: false, o.str("op") ?: "", o.str("path") ?: "", o.str("error") ?: "", o.long("size") ?: 0L)
    }
}
