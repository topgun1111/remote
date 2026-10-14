package com.remotecontrol.controller

import android.content.Context
import com.remotecontrol.shared.Protocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** An Agent this Controller has paired with. [token] is the long-lived secret issued at pairing. */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
) {
    /** `host:port`, the per-device key of `RouteStore` and `SettingsStore`. */
    val key: String get() = "$host:$port"
}

/**
 * Persists paired Agents in app-private SharedPreferences. Session 10b: the token is stored encrypted ([TokenVault], Keystore key);
 * tokens saved before that are read as they are and encrypted the next time the list is written. A token that cannot be
 * decrypted (Keystore key lost, e.g. restored on a new phone) drops that device: it has to be paired again.
 */
class DeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("paired_devices", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(PairedDevice.serializer())

    fun list(): List<PairedDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            Protocol.json.decodeFromString(serializer, raw).mapNotNull { d -> TokenVault.decrypt(d.token)?.let { d.copy(token = it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The device with `host:port` equal to [key] (the key used by `RouteStore` and `SettingsStore`). */
    fun byKey(key: String): PairedDevice? = list().firstOrNull { it.key == key }

    /** Adds the device, replacing any existing entry for the same host:port. */
    fun save(device: PairedDevice) {
        val updated = list().filterNot { it.host == device.host && it.port == device.port } + device
        write(updated)
        setLastUsed(device.id)
    }

    fun remove(id: String) {
        write(list().filterNot { it.id == id })
        if (lastUsedId() == id) prefs.edit().remove(KEY_LAST).commit()
    }

    fun lastUsed(): PairedDevice? {
        val id = lastUsedId() ?: return null
        return list().firstOrNull { it.id == id }
    }

    fun setLastUsed(id: String) {
        prefs.edit().putString(KEY_LAST, id).commit()
    }

    private fun lastUsedId(): String? = prefs.getString(KEY_LAST, null)

    private fun write(devices: List<PairedDevice>) {
        prefs.edit().putString(KEY_DEVICES, Protocol.json.encodeToString(serializer, devices.map { it.copy(token = TokenVault.encrypt(it.token)) })).commit()
    }

    private companion object {
        const val KEY_DEVICES = "devices"
        const val KEY_LAST = "last_used"
    }
}
