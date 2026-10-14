package com.remotecontrol.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Base64
import android.util.Log
import com.remotecontrol.shared.FileChunk
import com.remotecontrol.shared.FileEntry
import com.remotecontrol.shared.FileListResult
import com.remotecontrol.shared.FileOpRequest
import com.remotecontrol.shared.FileOpResult
import com.remotecontrol.shared.FileWriteAck
import com.remotecontrol.shared.FileWriteRequest
import com.remotecontrol.shared.FilesLogic
import com.remotecontrol.shared.FilesWire
import com.remotecontrol.shared.FilesWriteWire
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * Handles `FILE_LIST`, `FILE_READ` (Session 8b) and `FILE_WRITE`, `FILE_OP` (Session 8c). Browsing covers the shared
 * "Internal storage" plus any mounted SD card / USB volume (8c). Every path is normalised and its canonical
 * (symlink-resolved) form must stay inside a root; delete/rename act on the link itself and never follow symlinks.
 * All disk work runs on one worker thread (so chunk writes of one upload can never interleave). Create one instance
 * with the application context and call [tryHandle] from `AgentServer.onMessage` for authenticated clients, before
 * `Protocol.decode` (next to the apps hook).
 */
class FilesHandler(context: Context) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rc-files").apply { isDaemon = true } }

    private val icons = AppIconsHandler(app)

    private class Root(val label: String, val dir: File)

    /**
     * True (and answered via [reply], from any thread) if [raw] is one of the four file requests or (8d) an `APP_ICON`
     * request, so the existing `AgentServer` hook needs no change.
     */
    fun tryHandle(raw: String, reply: (String) -> Unit): Boolean {
        if (raw.contains("APP_ICON") && icons.tryHandle(raw, reply)) return true
        if (!raw.contains("FILE_")) return false
        FilesWire.parseListRequest(raw)?.let { path ->
            worker.execute { reply(FilesWire.listResult(safely(path) { list(path) })) }
            return true
        }
        FilesWire.parseReadRequest(raw)?.let { r ->
            worker.execute {
                val c = try { read(r.path, r.offset, r.len) } catch (e: Exception) {
                    Log.w(TAG, "FILE_READ failed: ${e.message}")
                    FileChunk(false, r.path, r.offset, 0, "", false, "Read failed: ${e.message ?: e.javaClass.simpleName}")
                }
                reply(FilesWire.chunk(c))
            }
            return true
        }
        FilesWriteWire.parseWriteRequest(raw)?.let { r ->
            worker.execute {
                val a = try { write(r) } catch (e: Exception) {
                    Log.w(TAG, "FILE_WRITE failed: ${e.message}")
                    FileWriteAck(false, r.path, r.offset, false, "Write failed: ${e.message ?: e.javaClass.simpleName}")
                }
                reply(FilesWriteWire.writeAck(a))
            }
            return true
        }
        FilesWriteWire.parseOpRequest(raw)?.let { r ->
            worker.execute {
                val res = try { op(r) } catch (e: Exception) {
                    Log.w(TAG, "FILE_OP ${r.op} failed: ${e.message}")
                    FileOpResult(false, r.op, r.path, "Operation failed: ${e.message ?: e.javaClass.simpleName}")
                }
                reply(FilesWriteWire.opResult(res))
            }
            return true
        }
        return false
    }

    private fun safely(path: String, block: () -> FileListResult): FileListResult = try { block() } catch (e: Exception) {
        Log.w(TAG, "FILE_LIST failed: ${e.message}")
        FileListResult(false, path, "List failed: ${e.message ?: e.javaClass.simpleName}", emptyList())
    }

    /** True when the Agent may read shared storage (Android 11+: "All files access"; older: storage permission). */
    fun hasAccess(): Boolean = Companion.hasAccess(app)

    fun hasWriteAccess(): Boolean = Companion.hasWriteAccess(app)

    // ---------------------------------------------------------------- roots + path checks

    /** Internal storage first, then every other mounted volume that exposes an app folder (SD card, USB). */
    private fun roots(): List<Root> {
        val primary = Environment.getExternalStorageDirectory().canonicalFile
        val out = ArrayList<Root>()
        out += Root("Internal storage", primary)
        try {
            for (d in app.getExternalFilesDirs(null)) {
                if (d == null) continue
                val idx = d.path.indexOf("/Android/data/")
                if (idx <= 0) continue
                val vol = try { File(d.path.substring(0, idx)).canonicalFile } catch (_: Exception) { continue }
                if (!vol.isDirectory || !vol.canRead()) continue
                if (out.any { it.dir.path == vol.path }) continue
                out += Root(volumeLabel(vol), vol)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Listing extra volumes failed: ${e.message}")
        }
        return out
    }

    private fun volumeLabel(dir: File): String = try {
        val sm = if (Build.VERSION.SDK_INT >= 24) app.getSystemService(StorageManager::class.java) else null
        sm?.getStorageVolume(dir)?.getDescription(app)?.takeIf { it.isNotBlank() } ?: "SD card ${dir.name}"
    } catch (_: Exception) { "SD card ${dir.name}" }

    private fun rootPaths(): List<String> = roots().map { it.dir.path }

    /** Canonical file for [requested] if it lies inside a root, else null. Follows symlinks (read/list/mkdir targets). */
    private fun allowed(requested: String): File? {
        val rp = rootPaths()
        val n = FilesLogic.resolve(rp, requested) ?: return null
        val canon = File(n).canonicalFile
        return if (FilesLogic.isUnderAny(rp, canon.path)) canon else null
    }

    private class Target(val parent: File, val file: File)

    /**
     * An item that may not exist yet (upload target) or must not be symlink-resolved (delete/rename): canonical parent
     * inside a root + a validated last name. Never a root itself.
     */
    private fun target(path: String): Target? {
        val n = FilesLogic.normalize(path) ?: return null
        if (n == "/") return null
        val name = n.substringAfterLast('/')
        if (!FilesLogic.validName(name)) return null
        val parent = allowed(FilesLogic.parent(n)) ?: return null
        if (!parent.isDirectory) return null
        val f = File(parent, name)
        if (rootPaths().contains(f.path)) return null
        return Target(parent, f)
    }

    /** True if [f] itself is a symlink (its canonical path differs from canonical parent + name). */
    private fun isSymlink(f: File): Boolean {
        val parent = f.parentFile ?: return false
        return try { File(parent.canonicalFile, f.name).path != f.canonicalPath } catch (_: Exception) { false }
    }

    // ---------------------------------------------------------------- list / read (8b, now multi-root)

    private fun list(path: String): FileListResult {
        if (!hasAccess()) return FileListResult(false, path, NO_ACCESS, emptyList())
        if (path.isEmpty()) {
            val entries = roots().map { FileEntry(it.label, true, 0, it.dir.lastModified(), it.dir.path) }
            return FileListResult(true, "", "", entries)
        }
        val dir = allowed(path) ?: return FileListResult(false, path, BAD_PATH, emptyList())
        if (!dir.isDirectory) return FileListResult(false, path, "Not a folder.", emptyList())
        val files = dir.listFiles() ?: return FileListResult(false, path, "Cannot read this folder.", emptyList())
        sweepPartials(files)
        val entries = files.take(FilesLogic.MAX_ENTRIES).map {
            FileEntry(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified())
        }
        Log.i(TAG, "FILE_LIST ${dir.path} -> ${entries.size}/${files.size}")
        return FileListResult(true, dir.path, "", FilesLogic.sort(entries), truncated = files.size > entries.size)
    }

    /** 8d: removes upload leftovers (`.name.rcpart`) that nobody resumed for a week. */
    private fun sweepPartials(files: Array<File>) {
        val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        for (f in files) if (f.isFile && f.name.endsWith(".rcpart") && f.lastModified() < limit) f.delete()
    }

    private fun read(path: String, offset: Long, len: Int): FileChunk {
        fun err(m: String) = FileChunk(false, path, offset, 0, "", false, m)
        if (!hasAccess()) return err(NO_ACCESS)
        val f = allowed(path) ?: return err(BAD_PATH)
        if (!f.isFile) return err("Not a file.")
        if (!f.canRead()) return err("Cannot read this file.")
        RandomAccessFile(f, "r").use { raf ->
            val total = raf.length()
            if (offset > total) return err("Offset is past the end of the file.")
            val want = minOf(len.toLong(), total - offset).toInt()
            val buf = ByteArray(want)
            var got = 0
            if (want > 0) {
                raf.seek(offset)
                while (got < want) { val n = raf.read(buf, got, want - got); if (n < 0) break; got += n }
            }
            val eof = offset + got >= total
            if (offset == 0L) Log.i(TAG, "FILE_READ start ${f.path} total=$total")
            return FileChunk(true, f.path, offset, total, Base64.encodeToString(buf, 0, got, Base64.NO_WRAP), eof, "")
        }
    }

    // ---------------------------------------------------------------- upload (8c)

    /**
     * One upload chunk. The data goes to a hidden `.name.rcpart` file in the target folder; the final rename happens on
     * `eof`, so a half-finished upload never appears under the real name and an existing file is only replaced once the
     * new one is complete. The Agent keeps no state: the partial file's length IS the state.
     */
    private fun write(r: FileWriteRequest): FileWriteAck {
        fun err(m: String, at: Long = r.offset) = FileWriteAck(false, r.path, at, false, m)
        if (!hasWriteAccess()) return err(NO_ACCESS)
        val t = target(r.path) ?: return err(BAD_PATH)
        val tmp = File(t.parent, FilesLogic.partialName(t.file.name))
        if (r.abort) { tmp.delete(); return FileWriteAck(true, t.file.path, 0, false, "") }
        if (r.offset == 0L) {
            if (t.file.isDirectory) return err("A folder with this name already exists.")
            if (t.file.exists() && !r.overwrite) return err(EXISTS)
        }
        if (r.data.length > FilesLogic.MAX_CHUNK_B64) return err("Chunk too large.")
        val bytes = try {
            if (r.data.isEmpty()) ByteArray(0) else Base64.decode(r.data, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) { return err("The chunk is not valid base64.") }
        RandomAccessFile(tmp, "rw").use { raf ->
            if (r.offset == 0L) raf.setLength(0)
            else if (raf.length() != r.offset) return err("Offset mismatch: the Agent has ${raf.length()} bytes.", raf.length())
            raf.seek(r.offset)
            raf.write(bytes)
        }
        val stored = r.offset + bytes.size
        if (r.offset == 0L) Log.i(TAG, "FILE_WRITE start ${t.file.path}")
        if (!r.eof) return FileWriteAck(true, t.file.path, stored, false, "")
        if (t.file.exists() || isSymlink(t.file)) {
            if (!r.overwrite) { tmp.delete(); return err(EXISTS) }
            if (t.file.isDirectory) { tmp.delete(); return err("A folder with this name already exists.") }
            if (!t.file.delete()) { tmp.delete(); return err("Could not replace the existing file.") }
        }
        if (!tmp.renameTo(t.file)) { tmp.delete(); return err("Could not finish the file.") }
        Log.i(TAG, "FILE_WRITE done ${t.file.path} $stored bytes")
        return FileWriteAck(true, t.file.path, stored, true, "")
    }

    // ---------------------------------------------------------------- new folder / delete / rename (8c)

    private fun op(r: FileOpRequest): FileOpResult {
        fun err(m: String) = FileOpResult(false, r.op, r.path, m)
        if (!hasWriteAccess()) return err(NO_ACCESS)
        return when (r.op) {
            FilesWriteWire.OP_MKDIR -> {
                if (!FilesLogic.validName(r.name)) return err(BAD_NAME)
                val parent = allowed(r.path) ?: return err(BAD_PATH)
                if (!parent.isDirectory) return err("Not a folder.")
                val f = File(parent, r.name)
                if (f.exists() || isSymlink(f)) return err(EXISTS)
                if (f.mkdir()) { Log.i(TAG, "FILE_OP mkdir ${f.path}"); FileOpResult(true, r.op, f.path, "") }
                else err("Could not create the folder.")
            }
            FilesWriteWire.OP_DELETE -> {
                val t = target(r.path) ?: return err(BAD_PATH)
                if (!t.file.exists() && !isSymlink(t.file)) return err("Not found.")
                if (deleteTree(t.file)) { Log.i(TAG, "FILE_OP delete ${t.file.path}"); FileOpResult(true, r.op, t.file.path, "") }
                else err("Could not delete everything (some items are protected or in use).")
            }
            FilesWriteWire.OP_RENAME -> {
                if (!FilesLogic.validName(r.name)) return err(BAD_NAME)
                val t = target(r.path) ?: return err(BAD_PATH)
                if (!t.file.exists() && !isSymlink(t.file)) return err("Not found.")
                val dest = File(t.parent, r.name)
                if (dest.exists() || isSymlink(dest)) return err(EXISTS)
                if (t.file.renameTo(dest)) { Log.i(TAG, "FILE_OP rename ${t.file.path} -> ${dest.name}"); FileOpResult(true, r.op, dest.path, "") }
                else err("Rename failed.")
            }
            FilesWriteWire.OP_PARTIAL -> {
                val t = target(r.path) ?: return err(BAD_PATH)
                val tmp = File(t.parent, FilesLogic.partialName(t.file.name))
                FileOpResult(true, r.op, t.file.path, "", if (tmp.isFile) tmp.length() else 0L)
            }
            else -> err("Unknown operation.")
        }
    }

    /** Deletes [f]; folders recursively. A symlink is removed as a link, its target is never entered. */
    private fun deleteTree(f: File): Boolean {
        if (isSymlink(f)) return f.delete()
        var ok = true
        if (f.isDirectory) {
            val kids = f.listFiles() ?: return false
            for (k in kids) if (!deleteTree(k)) ok = false
        }
        return f.delete() && ok
    }

    // ---------------------------------------------------------------- self-test (RC Files Setup)

    /** Blocking (call off the main thread): new folder -> 2-chunk upload -> rename -> delete in the root of internal storage. */
    fun selfTestWrite(): String {
        if (!hasWriteAccess()) return NO_ACCESS
        val steps = ArrayList<String>()
        fun step(name: String, ok: Boolean, extra: String = "") { steps += (if (ok) "ok    " else "FAIL  ") + name + (if (extra.isNotEmpty()) ": $extra" else "") }
        try {
            val base = Environment.getExternalStorageDirectory().canonicalPath
            val dir = "$base/RC_selftest"
            val mk = op(FileOpRequest(FilesWriteWire.OP_MKDIR, base, "RC_selftest"))
            step("new folder", mk.ok, mk.error)
            if (mk.ok) {
                val a1 = write(FileWriteRequest("$dir/t.txt", 0, "aGVs", eof = false, overwrite = false, abort = false)) // "hel"
                step("chunk 1", a1.ok && a1.offset == 3L, a1.error)
                val a2 = write(FileWriteRequest("$dir/t.txt", 3, "bG8=", eof = true, overwrite = false, abort = false))  // "lo"
                step("chunk 2 + finish", a2.ok && a2.done && a2.offset == 5L, a2.error)
                step("file is 5 bytes", File(dir, "t.txt").length() == 5L)
                val rn = op(FileOpRequest(FilesWriteWire.OP_RENAME, "$dir/t.txt", "u.txt"))
                step("rename", rn.ok && File(dir, "u.txt").exists(), rn.error)
                val dl = op(FileOpRequest(FilesWriteWire.OP_DELETE, dir, ""))
                step("delete folder", dl.ok && !File(dir).exists(), dl.error)
            }
        } catch (e: Exception) {
            step("exception", false, e.message ?: e.javaClass.simpleName)
        }
        val volumes = roots().joinToString { it.label }
        return steps.joinToString("\n") + "\nRoots: $volumes"
    }

    companion object {
        private const val TAG = "RemoteAgent"
        const val NO_ACCESS = "The Agent has no file access. On the Agent phone open RC Files Setup and grant it."
        private const val BAD_PATH = "That path is outside the allowed storage."
        private const val BAD_NAME = "That name is not allowed (no slashes, not empty, not too long)."
        private const val EXISTS = "An item with this name already exists."

        fun hasAccess(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        fun hasWriteAccess(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}
