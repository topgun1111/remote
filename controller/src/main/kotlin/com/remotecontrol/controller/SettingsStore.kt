package com.remotecontrol.controller

import android.content.Context
import com.remotecontrol.shared.SettingsLogic
import com.remotecontrol.shared.TlsPolicy

/**
 * Session 10b: Controller settings in SharedPreferences `controller_settings`.
 *  - [lanOnly]: never dial the remote/VPN address (filter in `RouteConnector`).
 *  - [requireTls]: refuse plain ws:// even for a device without a pin (an Agent from before 10b). Default off = migration friendly.
 *  - capture defaults: [captureDefaults] are applied after each handshake only when [applyCaptureOnConnect] is on.
 *  - per-device certificate pin (TOFU), key = `host:port` like `RouteStore`.
 */
class SettingsStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("controller_settings", Context.MODE_PRIVATE)

    var lanOnly: Boolean
        get() = p.getBoolean("lanOnly", false)
        set(v) { p.edit().putBoolean("lanOnly", v).apply() }

    var requireTls: Boolean
        get() = p.getBoolean("requireTls", false)
        set(v) { p.edit().putBoolean("requireTls", v).apply() }

    var applyCaptureOnConnect: Boolean
        get() = p.getBoolean("capApply", false)
        set(v) { p.edit().putBoolean("capApply", v).apply() }

    data class CaptureDefaults(val fps: Int, val quality: Int, val maxDimension: Int)

    fun captureDefaults() = CaptureDefaults(
        SettingsLogic.clampFps(p.getInt("capFps", SettingsLogic.DEFAULT_FPS)),
        SettingsLogic.clampQuality(p.getInt("capQuality", SettingsLogic.DEFAULT_QUALITY)),
        SettingsLogic.snapSize(p.getInt("capSize", SettingsLogic.DEFAULT_SIZE)),
    )

    fun setCaptureDefaults(d: CaptureDefaults) {
        p.edit().putInt("capFps", SettingsLogic.clampFps(d.fps)).putInt("capQuality", SettingsLogic.clampQuality(d.quality))
            .putInt("capSize", SettingsLogic.snapSize(d.maxDimension)).apply()
    }

    /** Pinned fingerprint (64 hex) of [key], or null. */
    fun pin(key: String): String? = p.getString("pin.$key", null)?.takeIf { TlsPolicy.valid(it) }
    fun setPin(key: String, fp: String) { if (TlsPolicy.valid(fp)) p.edit().putString("pin.$key", TlsPolicy.normalise(fp)).apply() }
    fun clearPin(key: String) { p.edit().remove("pin.$key").apply() }

    /** Call from the Controller's Forget together with `RouteStore.forget`. */
    fun forget(key: String) { clearPin(key) }
}
