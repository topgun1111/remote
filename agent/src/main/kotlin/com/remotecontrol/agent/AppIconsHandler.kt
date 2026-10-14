package com.remotecontrol.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import com.remotecontrol.shared.AppIconRequest
import com.remotecontrol.shared.AppIconResult
import com.remotecontrol.shared.AppIconsWire
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Answers `APP_ICON` (Session 8d) with the app's launcher icon as a small PNG. Created and called by [FilesHandler],
 * so no extra hook is needed in `AgentServer`. Rendering runs on one worker thread; adaptive icons are drawn flattened.
 * Only packages the Agent can see (the 8a `<queries>` / launcher list) resolve; anything else answers `ok=false`.
 */
class AppIconsHandler(context: Context) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rc-icons").apply { isDaemon = true } }

    /** True (answered via [reply], from any thread) if [raw] is an `APP_ICON` request. */
    fun tryHandle(raw: String, reply: (String) -> Unit): Boolean {
        val r = AppIconsWire.parseRequest(raw) ?: return false
        worker.execute { reply(AppIconsWire.result(render(r))) }
        return true
    }

    private fun render(r: AppIconRequest): AppIconResult = try {
        val d = app.packageManager.getApplicationIcon(r.pkg)
        val bmp = Bitmap.createBitmap(r.size, r.size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, r.size, r.size)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        AppIconResult(r.pkg, true, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    } catch (e: Exception) {
        Log.w(TAG, "APP_ICON ${r.pkg} failed: ${e.message}")
        AppIconResult(r.pkg, false, "")
    }

    private companion object { const val TAG = "RemoteAgent" }
}
