package com.remotecontrol.shared

import java.util.Locale

/** Pure logic for the file browser (Session 8b): path rules, sorting, sizes, local file names. Unit-tested. */
object FilesLogic {
    const val DEFAULT_CHUNK = 96 * 1024
    const val MAX_CHUNK = 256 * 1024
    const val MAX_ENTRIES = 5000

    /** Longest base64 text of one upload chunk the Agent accepts (4 chars per 3 bytes of [MAX_CHUNK]). */
    const val MAX_CHUNK_B64 = (MAX_CHUNK + 2) / 3 * 4

    /** Longest file/folder name (UTF-8 bytes) the Agent creates; below the usual 255-byte limit so the `.name.rcpart` temp name fits. */
    const val MAX_NAME_BYTES = 240

    fun clampLen(requested: Int) = requested.coerceIn(1, MAX_CHUNK)

    /** Lexical normalisation of an absolute path ("." and ".." resolved, no trailing slash). Null if relative, has NUL, or ".." climbs above "/". */
    fun normalize(path: String): String? {
        if (!path.startsWith("/") || path.contains('\u0000')) return null
        val out = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> {}
            ".." -> { if (out.isEmpty()) return null; out.removeAt(out.size - 1) }
            else -> out += p
        }
        return "/" + out.joinToString("/")
    }

    /** [path] equals [root] or is inside it (a sibling like "/a/b1" is NOT inside "/a/b"). Both must be normalised. */
    fun isUnder(root: String, path: String) = root == "/" || path == root || path.startsWith("$root/")

    fun isUnderAny(roots: List<String>, path: String) = roots.any { isUnder(it, path) }

    /** Normalised [requested] if it is inside one of [roots], else null. The Agent re-checks the canonical (symlink-resolved) path too. */
    fun resolve(roots: List<String>, requested: String): String? {
        val n = normalize(requested) ?: return null
        return if (isUnderAny(roots, n)) n else null
    }

    fun parent(path: String): String {
        val n = normalize(path) ?: return "/"
        return if (n == "/") "/" else n.substringBeforeLast('/').ifEmpty { "/" }
    }

    fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

    /** "Up" target: from a root or "" it is "" (the roots list), otherwise the parent folder. */
    fun up(path: String, roots: List<String>): String =
        if (path.isEmpty() || roots.contains(path)) "" else parent(path)

    /** Folders first, then files, each by Turkish-folded name (reuses [AppsLogic.fold]). */
    fun sort(entries: List<FileEntry>): List<FileEntry> =
        entries.sortedWith(compareBy<FileEntry> { !it.dir }.thenBy { AppsLogic.fold(it.name) }.thenBy { it.name })

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.ROOT, "%.1f %s", v, units[i])
    }

    fun progressText(name: String, received: Long, total: Long): String {
        val pct = if (total > 0) " (${(received * 100 / total).coerceIn(0, 100)}%)" else ""
        return "$name: ${humanSize(received)} / ${humanSize(total)}$pct"
    }

    /** A file name that is safe to create locally (no separators or reserved characters, no leading dots, bounded length). */
    fun safeLocalName(name: String): String {
        val cleaned = name.map { if (it.code < 32 || it in "/\\:*?\"<>|") '_' else it }.joinToString("")
            .trim().trimStart('.').trim()
        return cleaned.ifBlank { "file" }.take(120)
    }

    /** "a.txt" -> "a (1).txt", "a (2).txt", ... until [exists] says no. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 10_000) { val c = "$base ($i)$ext"; if (!exists(c)) return c; i++ }
        return name
    }

    /** Session 8c: a name the Agent may create/rename to: not blank, not "."/"..", no separators or control characters, bounded UTF-8 length. */
    fun validName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        if (name.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) return false
        return name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES
    }

    /** Last path component ("/a/b/" -> "b"). */
    fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** Hidden temp name used while an upload is in progress; renamed to the real name when it is complete. */
    fun partialName(name: String): String = ".$name.rcpart"
}
