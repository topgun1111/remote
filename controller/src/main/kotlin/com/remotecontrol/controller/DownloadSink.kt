package com.remotecontrol.controller

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.remotecontrol.shared.FilesLogic
import java.io.File
import java.io.OutputStream

/**
 * Where downloaded files go. Android 10+: public `Download/RemoteControl` via MediaStore (no permission, visible in
 * Files/Downloads). Older: the app's own external Downloads folder. Not thread-safe: use from one thread.
 * [finish] on success, [abort] to discard a partial file.
 */
class DownloadSink(private val context: Context, remoteName: String) {
    private val name = FilesLogic.safeLocalName(remoteName)
    private var uri: android.net.Uri? = null
    private var file: File? = null
    val out: OutputStream
    /** Human-readable place the file ends up in. */
    val location: String

    init {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RemoteControl")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val u = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create the download file.")
            uri = u
            out = context.contentResolver.openOutputStream(u) ?: throw java.io.IOException("Could not open the download file.")
            location = "Downloads/RemoteControl/$name"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            dir.mkdirs()
            val f = File(dir, FilesLogic.uniqueName(name) { File(dir, it).exists() })
            file = f
            out = f.outputStream().buffered()
            location = f.path
        }
    }

    fun finish() {
        out.flush(); out.close()
        uri?.let {
            context.contentResolver.update(it, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }

    fun abort() {
        try { out.close() } catch (_: Exception) {}
        uri?.let { try { context.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        file?.delete()
    }
}
