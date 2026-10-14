package com.remotecontrol.controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import com.remotecontrol.shared.AppIconResult
import com.remotecontrol.shared.AppIconsWire
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * App icons for the Agent's app list (Session 8d). Rows ask for their icon with [bind]; at most [MAX_IN_FLIGHT]
 * `APP_ICON` requests are outstanding, rows that scrolled away (view recycled for another package) are skipped on
 * delivery, decoded icons are cached in memory for the lifetime of the loader (so a list scrolled again is instant).
 * Replies arrive through [FilesHub] (no new hook in `ConnectionManager`). All public methods: main thread.
 *
 * Typical use in the Apps list adapter (see `SESSION8D-INTEGRATION.md`):
 *   `icons.bind(imageView, app.pkg)` in `getView`, `icons.start()` when the panel attaches, `icons.stop()` when it detaches.
 *
 * @param send sends one JSON text frame; false when not connected (`ConnectionManager.send`).
 */
class AppIconLoader(
    private val send: (String) -> Boolean,
    private val sizePx: Int = AppIconsWire.DEFAULT_SIZE,
) : FilesHub.Sink {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "rc-icon-decode").apply { isDaemon = true } }
    private val cache = LruCache<String, Bitmap>(300)
    private val waiting = HashMap<String, MutableList<WeakReference<ImageView>>>()
    private val queue = ArrayDeque<String>()
    private val pending = HashSet<String>()      // requested, not answered yet
    private val failed = HashSet<String>()       // no icon (or no answer): show the placeholder, do not ask again
    private var started = false

    /** Registers for replies. Call when the panel is attached. */
    fun start() { started = true; FilesHub.register(this) }

    /** Unregisters and drops everything queued. Call when the panel is detached. */
    fun stop() {
        started = false
        FilesHub.unregister(this)
        queue.clear(); pending.clear(); waiting.clear()
        main.removeCallbacksAndMessages(null)
    }

    /** Shows the icon of [pkg] in [view] as soon as it is available (immediately if cached, otherwise the view is cleared). */
    fun bind(view: ImageView, pkg: String) {
        view.tag = pkg
        val hit = cache.get(pkg)
        if (hit != null) { view.setImageBitmap(hit); return }
        view.setImageDrawable(null)
        if (failed.contains(pkg) || !started) return
        waiting.getOrPut(pkg) { ArrayList() }.add(WeakReference(view))
        if (!pending.contains(pkg) && !queue.contains(pkg)) queue.addLast(pkg)
        pump()
    }

    private fun pump() {
        while (pending.size < MAX_IN_FLIGHT && queue.isNotEmpty()) {
            val pkg = queue.removeFirst()
            if (waiting[pkg].isNullOrEmpty()) continue                       // nobody waits any more
            if (!send(AppIconsWire.request(pkg, sizePx))) { failed.add(pkg); continue }
            pending.add(pkg)
            main.postDelayed({ onTimeout(pkg) }, REQUEST_TIMEOUT_MS)
        }
    }

    private fun onTimeout(pkg: String) {
        if (pending.remove(pkg)) { failed.add(pkg); waiting.remove(pkg); pump() }
    }

    override fun onRawMessage(raw: String): Boolean {
        val r = AppIconsWire.parseResult(raw) ?: return false
        io.execute {
            val bmp = if (r.ok && r.png.isNotEmpty()) decode(r) else null
            main.post { deliver(r.pkg, bmp) }
        }
        return true
    }

    private fun decode(r: AppIconResult): Bitmap? = try {
        val bytes = Base64.decode(r.png, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) { null }

    private fun deliver(pkg: String, bmp: Bitmap?) {
        pending.remove(pkg)
        val views = waiting.remove(pkg)
        if (bmp == null) failed.add(pkg)
        else {
            cache.put(pkg, bmp)
            views?.forEach { ref -> ref.get()?.let { v -> if (v.tag == pkg) v.setImageBitmap(bmp) } }   // skip recycled rows
        }
        pump()
    }

    private companion object {
        const val MAX_IN_FLIGHT = 3
        const val REQUEST_TIMEOUT_MS = 8_000L
    }
}
