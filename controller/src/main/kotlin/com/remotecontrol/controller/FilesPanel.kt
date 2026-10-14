package com.remotecontrol.controller

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.remotecontrol.shared.FileEntry
import com.remotecontrol.shared.FileListResult
import com.remotecontrol.shared.FileOpResult
import com.remotecontrol.shared.FilesLogic
import com.remotecontrol.shared.FilesWriteWire
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Agent's files as a browsable list. Session 8b: tap a folder to open it, tap a file to download it to this phone
 * (Downloads/RemoteControl). Session 8c: **Upload** (picks a file on this phone into the open folder), **New folder**,
 * long-press an item for Open/Download, **Rename** and **Delete** (with confirmation). Plain Views, fills its parent:
 * `setContentView(FilesPanel(this, { json -> connectionManager.send(json) }, onClose = { showConnectionScreen() }, pickFile = { ... }))`.
 *
 * [context] must be an Activity (dialogs). [pickFile] must start the system file picker (`ACTION_OPEN_DOCUMENT`) and
 * hand the result to [onFilePicked]; see `SESSION8C-INTEGRATION.md`.
 *
 * Session 8d, resume: stop a download (Cancel, lost link) and tap the same file again to continue where it stopped;
 * an upload that timed out continues when you pick the same file again into the same folder.
 */
class FilesPanel(
    context: Context,
    send: (String) -> Boolean,
    private val onClose: () -> Unit = {},
    private val pickFile: () -> Unit = {},
) : LinearLayout(context) {
    private val pathView = TextView(context).apply { textSize = 13f }
    private val upBtn = Button(context).apply { text = "Up"; isAllCaps = false }
    private val refreshBtn = Button(context).apply { text = "Refresh"; isAllCaps = false }
    private val closeBtn = Button(context).apply { text = "Close"; isAllCaps = false }
    private val uploadBtn = Button(context).apply { text = "Upload"; isAllCaps = false }
    private val newFolderBtn = Button(context).apply { text = "New folder"; isAllCaps = false }
    private val cancelBtn = Button(context).apply { text = "Cancel transfer"; isAllCaps = false; visibility = View.GONE }
    private val statusView = TextView(context).apply { textSize = 12f; visibility = View.GONE }
    private val list = ListView(context)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val adapter = object : ArrayAdapter<FileEntry>(context, android.R.layout.simple_list_item_2, android.R.id.text1) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            val e = getItem(position)!!
            v.findViewById<TextView>(android.R.id.text1).text = if (e.dir) "[ ${e.name} ]" else e.name
            v.findViewById<TextView>(android.R.id.text2).text =
                if (e.dir) (if (e.mtime > 0) dateFmt.format(Date(e.mtime)) else "folder")
                else FilesLogic.humanSize(e.size) + if (e.mtime > 0) "   " + dateFmt.format(Date(e.mtime)) else ""
            return v
        }
    }
    private val client = FilesClient(
        context, send, this::showList, this::showProgress, this::showDone, this::showError, this::showOp,
    )

    private var current = ""
    private var roots: List<String> = emptyList()
    private var lastWasUpload = false
    private var note = ""                      // one-shot text put in front of the next listing's status

    init {
        orientation = VERTICAL
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(upBtn); top.addView(refreshBtn); top.addView(closeBtn)
        addView(top)
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(uploadBtn); actions.addView(newFolderBtn)
        addView(actions)
        addView(pathView)
        addView(statusView)
        addView(cancelBtn)
        list.adapter = adapter
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        upBtn.setOnClickListener { if (current.isNotEmpty()) open(FilesLogic.up(current, roots)) }
        refreshBtn.setOnClickListener { open(current) }
        closeBtn.setOnClickListener { onClose() }
        cancelBtn.setOnClickListener { client.cancel() }
        uploadBtn.setOnClickListener {
            when {
                current.isEmpty() -> status("Open a folder first, then tap Upload.")
                client.busy -> status("A transfer is already running.")
                else -> pickFile()
            }
        }
        newFolderBtn.setOnClickListener {
            when {
                current.isEmpty() -> status("Open a folder first.")
                client.busy -> status("Wait for the transfer to finish, or cancel it.")
                else -> askNewFolder()
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos) ?: return@setOnItemClickListener
            val path = e.path.ifEmpty { FilesLogic.join(current, e.name) }
            if (e.dir) open(path) else startDownload(path, e)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos) ?: return@setOnItemLongClickListener true
            if (e.path.isEmpty()) showItemMenu(e)       // storage roots (entries with a path) have no menu
            true
        }
    }

    // ------------------------------------------------------------------ browsing + download (8b)

    private fun open(path: String) {
        if (client.busy) { status("Wait for the transfer to finish, or cancel it."); return }
        status("Loading...")
        client.list(path)
    }

    private fun startDownload(path: String, e: FileEntry) {
        if (client.busy) { status("A transfer is already running."); return }
        val have = client.partialSize(path, e.size, e.mtime)
        status(
            if (have > 0 && have <= e.size) "Resuming ${e.name} at ${FilesLogic.humanSize(have)} of ${FilesLogic.humanSize(e.size)}..."
            else "Starting ${e.name} (${FilesLogic.humanSize(e.size)})...",
        )
        lastWasUpload = false
        if (client.download(path, e.name, e.size, e.mtime)) cancelBtn.visibility = View.VISIBLE
    }

    private fun showList(r: FileListResult) {
        if (!r.ok) { note = ""; status(r.error.ifBlank { "Could not list this folder." }); return }
        current = r.path
        if (r.path.isEmpty()) roots = r.entries.map { it.path }.filter { it.isNotEmpty() }
        pathView.text = if (r.path.isEmpty()) "Storage" else r.path
        adapter.setNotifyOnChange(false)
        adapter.clear(); adapter.addAll(r.entries)
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        status(
            note + (if (r.entries.isEmpty()) "Empty folder. " else "${r.entries.size} items. ") +
                (if (r.truncated) "List cut at ${FilesLogic.MAX_ENTRIES} entries. " else "") +
                "Tap a file to download, long-press for more.",
        )
        note = ""
    }

    private fun showProgress(name: String, done: Long, total: Long) {
        status((if (client.uploading) "Uploading " else "Downloading ") + FilesLogic.progressText(name, done, total))
    }

    private fun showDone(name: String, location: String) {
        cancelBtn.visibility = View.GONE
        if (lastWasUpload) { note = "Uploaded $name. "; client.list(current) }   // refresh so the new file shows up
        else status("Saved $name to $location")
    }

    private fun showError(msg: String) { cancelBtn.visibility = View.GONE; status(msg) }

    // ------------------------------------------------------------------ upload (8c)

    /** Call from the host Activity's `onActivityResult` with the picked file's Uri (null = picker cancelled). */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) return
        if (current.isEmpty()) { status("Open a folder first, then tap Upload."); return }
        val (rawName, size, lastMod) = queryMeta(uri)
        var name = rawName
        if (!FilesLogic.validName(name)) name = FilesLogic.safeLocalName(name)
        if (!FilesLogic.validName(name)) { status("This file name cannot be used on the Agent."); return }
        val existing = (0 until adapter.count).mapNotNull { adapter.getItem(it) }.firstOrNull { it.name == name }
        when {
            existing == null -> startUpload(uri, name, size, false, lastMod)
            existing.dir -> status("A folder named \"$name\" already exists here.")
            else -> AlertDialog.Builder(context)
                .setTitle("Replace file?")
                .setMessage("\"$name\" already exists in this folder on the Agent. Replace it?")
                .setPositiveButton("Replace") { _, _ -> startUpload(uri, name, size, true, lastMod) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startUpload(uri: Uri, name: String, size: Long, overwrite: Boolean, lastMod: Long) {
        if (client.busy) { status("A transfer is already running."); return }
        status("Starting upload of $name...")
        lastWasUpload = true
        if (client.upload(uri, name, size, current, overwrite, lastMod)) cancelBtn.visibility = View.VISIBLE
    }

    private data class Meta(val name: String, val size: Long, val lastModified: Long)

    /** Display name, size (-1 = unknown) and last-modified time (0 = unknown) of a picked document. */
    private fun queryMeta(uri: Uri): Meta {
        var name = ""; var size = -1L; var mod = 0L
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "last_modified"), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    val mi = c.getColumnIndex("last_modified")
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    if (mi >= 0 && !c.isNull(mi)) mod = c.getLong(mi)
                }
            }
        } catch (_: Exception) {}
        if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        return Meta(name, size, mod)
    }

    // ------------------------------------------------------------------ new folder / rename / delete (8c)

    private fun showItemMenu(e: FileEntry) {
        val path = FilesLogic.join(current, e.name)
        val items = arrayOf(if (e.dir) "Open" else "Download", "Rename", "Delete")
        AlertDialog.Builder(context).setTitle(e.name).setItems(items) { _, which ->
            when (which) {
                0 -> if (e.dir) open(path) else startDownload(path, e)
                1 -> askRename(path, e)
                else -> askDelete(path, e)
            }
        }.show()
    }

    private fun askNewFolder() = askName("New folder", "Create", "") { name ->
        note = "Folder \"$name\" created. "
        client.op(FilesWriteWire.OP_MKDIR, current, name)
    }

    private fun askRename(path: String, e: FileEntry) = askName("Rename", "Rename", e.name) { name ->
        if (name == e.name) return@askName
        note = "Renamed to \"$name\". "
        client.op(FilesWriteWire.OP_RENAME, path, name)
    }

    private fun askName(title: String, ok: String, initial: String, action: (String) -> Unit) {
        if (client.busy) { status("Wait for the transfer to finish, or cancel it."); return }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initial); setSelection(initial.length); setSingleLine(true)
        }
        AlertDialog.Builder(context).setTitle(title).setView(input)
            .setPositiveButton(ok) { _, _ ->
                val name = input.text.toString().trim()
                if (FilesLogic.validName(name)) action(name)
                else status("That name is not allowed (no slashes, not empty, not too long).")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askDelete(path: String, e: FileEntry) {
        if (client.busy) { status("Wait for the transfer to finish, or cancel it."); return }
        val what = if (e.dir) "the folder \"${e.name}\" and everything in it" else "the file \"${e.name}\""
        AlertDialog.Builder(context)
            .setTitle("Delete?")
            .setMessage("Delete $what from the Agent phone? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                note = "Deleted \"${e.name}\". "
                client.op(FilesWriteWire.OP_DELETE, path)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOp(r: FileOpResult) {
        if (!r.ok) { note = ""; status(r.error.ifBlank { "The operation failed." }); return }
        client.list(current)                    // refresh; the note is shown with the new listing
    }

    // ------------------------------------------------------------------ plumbing

    private fun status(text: String) {
        statusView.text = text
        statusView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        FilesHub.register(client)
        client.sweepPartials()
        open("")
    }

    override fun onDetachedFromWindow() {
        FilesHub.unregister(client); client.destroy(); super.onDetachedFromWindow()
    }
}
