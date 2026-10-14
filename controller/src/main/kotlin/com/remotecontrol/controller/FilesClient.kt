package com.remotecontrol.controller

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.remotecontrol.shared.FileChunk
import com.remotecontrol.shared.FileListResult
import com.remotecontrol.shared.FileOpResult
import com.remotecontrol.shared.FileWriteAck
import com.remotecontrol.shared.FilesLogic
import com.remotecontrol.shared.FilesWire
import com.remotecontrol.shared.FilesWriteWire
import java.io.File
import java.io.FileOutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Folder listing, chunked download (8b), chunked upload and new folder / delete / rename (8c). All callbacks run on
 * the main thread. Transfers are pull/ack based: the next chunk is requested (download) or sent (upload) only after the
 * previous one was written/acknowledged, so a slow link or disk never piles up data. One transfer (download OR upload)
 * at a time. Reading, decoding and writing runs on a worker thread.
 *
 * Session 8d, resume: a download is written to a partial file in the app's own folder (named by a hash of Agent path +
 * size + mtime, so a changed file never continues an old partial) and copied to Downloads/RemoteControl when complete;
 * stopping or losing the link keeps the partial and the next download of the same file continues there. An upload that
 * died on a timeout / lost link keeps the Agent's `.name.rcpart`; starting the same file again (same Uri, size and
 * modified time, remembered in SharedPreferences) asks the Agent how much it has (`partial`) and continues from there.
 *
 * @param send sends one JSON text frame; false when not connected/authenticated (`ConnectionManager.send`).
 * @param onProgress bytes done / total for the running transfer (total <= 0 = unknown); see [uploading] for the direction.
 * @param onDone download: name + local place; upload: name + "Agent: <path>".
 * @param onOp result of a [op] call.
 */
class FilesClient(
    context: Context,
    private val send: (String) -> Boolean,
    private val onList: (FileListResult) -> Unit,
    private val onProgress: (name: String, done: Long, total: Long) -> Unit,
    private val onDone: (name: String, location: String) -> Unit,
    private val onError: (String) -> Unit,
    private val onOp: (FileOpResult) -> Unit,
) : FilesHub.Sink {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "rc-transfer").apply { isDaemon = true } }

    private class Download(val path: String, val name: String, val expected: Long, val part: File) {
        @Volatile var total = -1L
        @Volatile var received = 0L
        var out: FileOutputStream? = null       // worker thread only
    }

    private class Upload(
        val dest: String, val name: String, val total: Long, val input: PushbackInputStream, val overwrite: Boolean,
        val recordKey: String,
    ) {
        @Volatile var sent = 0L                 // bytes the Agent has confirmed
        @Volatile var pendingEnd = 0L           // bytes confirmed once the chunk in flight is acknowledged
    }

    @Volatile private var download: Download? = null
    @Volatile private var upload: Upload? = null
    @Volatile private var probing: Upload? = null          // upload waiting for the Agent's `partial` answer
    private val prefs = app.getSharedPreferences("files_resume", Context.MODE_PRIVATE)
    @Volatile private var listPending = false
    @Volatile private var opPending = false

    /** A transfer (download or upload) is running. */
    val busy: Boolean get() = download != null || upload != null
    val uploading: Boolean get() = upload != null

    private val listTimeout = Runnable {
        if (listPending) { listPending = false; onError("The Agent did not answer the folder request (is it updated to this version?).") }
    }
    private val chunkTimeout = Runnable { failDownload("No answer from the Agent; download stopped.") }
    private val ackTimeout = Runnable { failUpload("No answer from the Agent; upload stopped.", abortRemote = false, keepRecord = true) }
    private val opTimeout = Runnable {
        if (opPending) { opPending = false; onError("The Agent did not answer (is it updated to this version?).") }
    }

    /** Main thread. */
    fun list(path: String): Boolean {
        if (!send(FilesWire.listRequest(path))) { onError("Not connected to the Agent."); return false }
        listPending = true
        main.removeCallbacks(listTimeout); main.postDelayed(listTimeout, 15_000L)
        return true
    }

    // ------------------------------------------------------------------ download (8b)

    private fun partialFile(path: String, size: Long, mtime: Long): File {
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, "partial").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1").digest("$path|$size|$mtime".toByteArray(Charsets.UTF_8))
        return File(dir, hash.joinToString("") { "%02x".format(it) } + ".part")
    }

    /** Bytes of this file already on this phone from an earlier, stopped download (0 = a fresh start). */
    fun partialSize(path: String, size: Long, mtime: Long): Long = partialFile(path, size, mtime).let { if (it.isFile) it.length() else 0L }

    /** Worker thread: deletes partial downloads nobody resumed for a week. */
    fun sweepPartials() = io.execute {
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, "partial")
        val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        dir.listFiles()?.forEach { if (it.name.endsWith(".part") && it.lastModified() < limit) it.delete() }
    }

    /**
     * Main thread. Starts downloading the Agent file at [path]; the name is used for the local file. [size] and [mtime]
     * come from the listing and identify the version of the file: with a matching partial the download resumes.
     */
    fun download(path: String, name: String, size: Long = -1L, mtime: Long = 0L): Boolean {
        if (busy) { onError("A transfer is already running."); return false }
        val part = partialFile(path, size, mtime)
        var have = if (part.isFile) part.length() else 0L
        if (size >= 0 && have > size) { part.delete(); have = 0L }
        val d = Download(path, name, size, part)
        d.received = have
        download = d
        if (!requestNext(d)) { download = null; return false }
        return true
    }

    /** Main thread. Cancels the running download or upload. */
    fun cancel() {
        val d = download
        if (d != null) {
            download = null
            main.removeCallbacks(chunkTimeout)
            closePartial(d, keep = true)
            onError("Download stopped. Tap the file again to resume.")
            return
        }
        if (upload != null) failUpload("Upload cancelled.")
    }

    private fun requestNext(d: Download): Boolean {
        if (!send(FilesWire.readRequest(d.path, d.received, FilesLogic.DEFAULT_CHUNK))) {
            onError("Not connected to the Agent."); return false
        }
        main.removeCallbacks(chunkTimeout); main.postDelayed(chunkTimeout, 20_000L)
        return true
    }

    private fun failDownload(msg: String, keepPartial: Boolean = true) {
        val d = download ?: return
        download = null
        main.removeCallbacks(chunkTimeout)
        closePartial(d, keepPartial)
        onError(if (keepPartial && d.received > 0) "$msg Tap the file again to resume." else msg)
    }

    private fun closePartial(d: Download, keep: Boolean) = io.execute {
        try { d.out?.close() } catch (_: Exception) {}
        d.out = null
        if (!keep) d.part.delete()
    }

    // ------------------------------------------------------------------ upload (8c)

    /**
     * Main thread. Uploads the content behind [uri] into the Agent folder [destDir] as [name] ([size] < 0 = unknown).
     * With [overwrite] an existing file of that name is replaced once the upload is complete. [lastModified] (0 = unknown)
     * helps recognise "the same file again" so an interrupted upload can resume (8d).
     */
    fun upload(uri: Uri, name: String, size: Long, destDir: String, overwrite: Boolean, lastModified: Long = 0L): Boolean {
        if (busy) { onError("A transfer is already running."); return false }
        val input = try { app.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
        if (input == null) { onError("Cannot open the selected file."); return false }
        val dest = FilesLogic.join(destDir, name)
        val record = "$dest|$uri|$size|$lastModified"
        val key = "u" + dest.hashCode()
        val resumable = size > 0 && prefs.getString(key, null) == record
        prefs.edit().putString(key, record).apply()
        val u = Upload(dest, name, size, PushbackInputStream(input, 1), overwrite, key)
        upload = u
        if (resumable) probe(u) else pushNext(u)
        return true
    }

    /** Asks the Agent how much of [u] it still holds; the answer continues in [resumeFrom]. */
    private fun probe(u: Upload) {
        probing = u
        main.removeCallbacks(ackTimeout); main.postDelayed(ackTimeout, 20_000L)
        if (!send(FilesWriteWire.opRequest(FilesWriteWire.OP_PARTIAL, u.dest))) {
            probing = null
            failUpload("Not connected to the Agent.", abortRemote = false, keepRecord = true)
        }
    }

    /** Worker thread. Skips the [s] bytes the Agent already has and continues, or starts over when there is nothing to resume. */
    private fun resumeFrom(u: Upload, s: Long) {
        if (upload !== u) return
        if (s > 0 && s < u.total) {
            var left = s
            try {
                while (left > 0) {
                    val n = u.input.skip(left)
                    if (n > 0) left -= n else if (u.input.read() >= 0) left-- else break
                }
            } catch (_: Exception) {}
            if (left != 0L) { main.post { failUpload("Could not resume: the file is shorter than the partial upload.") }; return }
            u.sent = s
            main.post { if (upload === u) { onProgress(u.name, s, u.total); pushNext(u) } }     // shows "resuming at ..."
        } else {
            main.post { if (upload === u) pushNext(u) }
        }
    }

    /** Reads the next chunk on the worker thread and sends it; the Agent's ack triggers the following one. */
    private fun pushNext(u: Upload) {
        main.removeCallbacks(ackTimeout); main.postDelayed(ackTimeout, 20_000L)    // covers read + send + ack
        io.execute {
            if (upload !== u) return@execute
            try {
                val buf = ByteArray(FilesLogic.DEFAULT_CHUNK)
                var n = 0
                while (n < buf.size) { val r = u.input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                var eof = true
                if (n == buf.size) { val b = u.input.read(); if (b >= 0) { u.input.unread(b); eof = false } }
                u.pendingEnd = u.sent + n
                val json = FilesWriteWire.writeRequest(u.dest, u.sent, Base64.encodeToString(buf, 0, n, Base64.NO_WRAP), eof, u.overwrite)
                main.post { if (upload === u && !send(json)) failUpload("Not connected to the Agent.", abortRemote = false, keepRecord = true) }
            } catch (e: Exception) {
                main.post { failUpload("Reading ${u.name} failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /**
     * [abortRemote]: tell the Agent to delete its partial file. [keepRecord]: remember this upload so the same file can
     * resume later (link lost / timeout); a refusal, a user cancel or a read error forgets it.
     */
    private fun failUpload(msg: String, abortRemote: Boolean = true, keepRecord: Boolean = false) {
        val u = upload ?: return
        upload = null; probing = null
        main.removeCallbacks(ackTimeout)
        if (abortRemote) send(FilesWriteWire.abortRequest(u.dest))     // the Agent deletes its partial file
        if (!keepRecord) prefs.edit().remove(u.recordKey).apply()
        io.execute { try { u.input.close() } catch (_: Exception) {} }
        onError(if (keepRecord) "$msg Pick the same file again to resume." else msg)
    }

    /** Worker thread. */
    private fun handleAck(a: FileWriteAck) {
        val u = upload ?: return
        if (FilesLogic.fileName(a.path) != u.name) return                  // stale ack of an earlier transfer
        if (a.ok && !a.done && a.offset == 0L) return                       // ack of an abort request
        if (!a.ok) { main.post { failUpload(a.error.ifBlank { "The Agent refused the upload." }) }; return }
        if (a.offset != u.pendingEnd) {
            main.post { failUpload("The Agent stored ${a.offset} bytes, expected ${u.pendingEnd}.") }; return
        }
        u.sent = u.pendingEnd
        if (a.done) {
            try { u.input.close() } catch (_: Exception) {}
            main.post {
                if (upload !== u) return@post
                upload = null; main.removeCallbacks(ackTimeout)
                prefs.edit().remove(u.recordKey).apply()
                onDone(u.name, "Agent: ${u.dest}")
            }
        } else {
            main.post {
                if (upload !== u) return@post
                onProgress(u.name, u.sent, u.total)
                pushNext(u)
            }
        }
    }

    // ------------------------------------------------------------------ new folder / delete / rename (8c)

    /**
     * Main thread. [op] is a `FilesWriteWire.OP_*`: mkdir (path = parent folder, name = new folder), delete (path = item),
     * rename (path = item, name = new name). The answer arrives in `onOp`; one operation at a time.
     */
    fun op(op: String, path: String, name: String = ""): Boolean {
        if (opPending) { onError("Another operation is still running."); return false }
        if (!send(FilesWriteWire.opRequest(op, path, name))) { onError("Not connected to the Agent."); return false }
        opPending = true
        main.removeCallbacks(opTimeout); main.postDelayed(opTimeout, 60_000L)    // deleting a big folder takes a while
        return true
    }

    // ------------------------------------------------------------------ incoming

    override fun onRawMessage(raw: String): Boolean {
        FilesWire.parseListResult(raw)?.let { r ->
            main.post { listPending = false; main.removeCallbacks(listTimeout); onList(r) }
            return true
        }
        FilesWire.parseChunk(raw)?.let { c -> io.execute { handleChunk(c) }; return true }
        FilesWriteWire.parseWriteAck(raw)?.let { a -> io.execute { handleAck(a) }; return true }
        FilesWriteWire.parseOpResult(raw)?.let { r ->
            val u = probing
            if (r.op == FilesWriteWire.OP_PARTIAL && u != null) {
                probing = null
                io.execute { resumeFrom(u, if (r.ok) r.size else 0L) }
                return true
            }
            main.post { opPending = false; main.removeCallbacks(opTimeout); onOp(r) }
            return true
        }
        return false
    }

    /** Worker thread. Stale chunks (cancelled download, wrong offset) are ignored. */
    private fun handleChunk(c: FileChunk) {
        val d = download ?: return
        if (c.path.substringAfterLast('/') != d.path.substringAfterLast('/')) return   // the Agent echoes the canonical path
        if (c.offset != d.received) return
        if (!c.ok) { main.post { failDownload(c.error.ifBlank { "The Agent refused the download." }) }; return }
        try {
            if (d.expected >= 0 && c.total != d.expected) {     // the file changed since the listing: the partial is useless
                main.post { failDownload("${d.name} changed on the Agent. Refresh the list and download it again.", keepPartial = false) }
                return
            }
            val out = d.out ?: FileOutputStream(d.part, true).also { d.out = it }
            d.total = c.total
            val bytes = if (c.data.isEmpty()) ByteArray(0) else Base64.decode(c.data, Base64.NO_WRAP)
            out.write(bytes)
            d.received += bytes.size
            if (c.eof) {
                out.flush(); out.close(); d.out = null
                val complete = d.received == d.total
                val sink = DownloadSink(app, d.name)
                try {
                    d.part.inputStream().use { it.copyTo(sink.out, 64 * 1024) }
                    sink.finish()
                } catch (e: Exception) { sink.abort(); throw e }       // the complete partial stays: tapping again finishes it
                d.part.delete()
                main.post {
                    if (download !== d) return@post
                    download = null; main.removeCallbacks(chunkTimeout)
                    if (complete) onDone(d.name, sink.location)
                    else onError("${d.name}: the file changed during the download (${d.received} of ${d.total} bytes). Saved anyway to ${sink.location}.")
                }
            } else if (bytes.isEmpty()) {
                main.post { failDownload("The Agent sent an empty chunk before the end of the file.") }
            } else {
                main.post {
                    if (download !== d) return@post
                    onProgress(d.name, d.received, d.total)
                    requestNext(d)
                }
            }
        } catch (e: Exception) {
            main.post { failDownload("Saving ${d.name} failed: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun destroy() {
        listPending = false; opPending = false
        val d = download; download = null
        val u = upload; upload = null; probing = null
        main.removeCallbacksAndMessages(null)
        // 8d: leave both partials in place (the Agent's .rcpart, our .part + the upload record) so the transfer can resume
        io.execute {
            try { d?.out?.close() } catch (_: Exception) {}
            try { u?.input?.close() } catch (_: Exception) {}
        }
    }
}
