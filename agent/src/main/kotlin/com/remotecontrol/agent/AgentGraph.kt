package com.remotecontrol.agent

import android.content.Context
import android.content.SharedPreferences
import com.remotecontrol.shared.KeyValueStore
import com.remotecontrol.shared.PairingAuthority
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/** SharedPreferences-backed store for [PairingAuthority]; survives app/process restarts. */
class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }
}

/** Process-wide singletons shared by the service and the activity. */
object AgentGraph {
    @Volatile private var authority: PairingAuthority? = null

    @Synchronized
    fun authority(context: Context): PairingAuthority =
        authority ?: PairingAuthority(
            PrefsStore(context.applicationContext.getSharedPreferences("agent_pairing", Context.MODE_PRIVATE))
        ).also { authority = it }

    fun settings(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)

    /** Best-effort LAN IPv4 address of this phone (null if not on a private network). */
    fun localIpv4(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            Collections.list(ifaces)
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}

/** Small in-memory event log shown on the Agent screen. */
object AgentLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        lines.addFirst("${fmt.format(Date())}  $message")
        while (lines.size > 20) lines.removeLast()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
