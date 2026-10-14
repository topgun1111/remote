package com.remotecontrol.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.remotecontrol.shared.TlsPolicy
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Finds Agents on the local Wi-Fi without typing anything: every address of this phone's Wi-Fi subnet is tried on the Agent
 * port, and each open port is tested with a TLS handshake (so the list shows "TLS" / "plain" and the certificate
 * fingerprint). Nothing is sent to the device besides the handshake; pairing still needs the PIN.
 */
class DeviceScanner(private val context: Context) {

    data class Found(val host: String, val port: Int, val tls: Boolean, val fingerprint: String?, val connectMs: Long)

    interface Listener {
        fun onFound(found: Found)
        fun onProgress(done: Int, total: Int)
        fun onFinished(count: Int, message: String)
    }

    private class FpTrust : X509TrustManager {
        @Volatile var fp: String? = null
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            chain?.firstOrNull()?.let { fp = TlsPolicy.fingerprint(it.encoded) }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var pool: ExecutorService? = null

    fun cancel() {
        generation++
        pool?.shutdownNow()
        pool = null
    }

    fun scan(port: Int, listener: Listener) {
        cancel()
        val gen = generation
        Thread {
            val addrs = try { LocalNetwork.addresses(context) } catch (e: Exception) { emptyList() }
            if (addrs.isEmpty()) {
                post(gen) { listener.onFinished(0, "No Wi-Fi network found. Connect this phone to the same Wi-Fi as the Agent.") }
                return@Thread
            }
            val own = addrs.mapNotNull { toLong(it.ip) }.toSet()
            val targets = LinkedHashSet<String>()
            for (a in addrs) {
                val ip = toLong(a.ip) ?: continue
                val prefix = if (a.prefix in 22..30) a.prefix else 24      // never scan more than a /22
                val size = 1L shl (32 - prefix)
                val base = ip and (size - 1).inv() and 0xFFFFFFFFL
                var h = base + 1
                while (h < base + size - 1) {
                    if (h !in own) targets.add(fromLong(h))
                    h++
                }
            }
            val total = targets.size
            val done = AtomicInteger(0)
            val found = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(48)
            pool = executor
            for (host in targets) {
                try {
                    executor.execute {
                        if (gen == generation) {
                            val f = probe(host, port)
                            if (f != null) { found.incrementAndGet(); post(gen) { listener.onFound(f) } }
                        }
                        val d = done.incrementAndGet()
                        if (d % 8 == 0 || d == total) post(gen) { listener.onProgress(d, total) }
                    }
                } catch (e: Exception) { break }   // pool was shut down by cancel()
            }
            executor.shutdown()
            try { executor.awaitTermination(90, TimeUnit.SECONDS) } catch (e: InterruptedException) { return@Thread }
            val n = found.get()
            post(gen) {
                listener.onFinished(
                    n,
                    if (n == 0) "No Agent found on this Wi-Fi. Is the Agent running (port $port) and on the same network?" else "Found $n device(s).",
                )
            }
        }.start()
    }

    private fun post(gen: Int, block: () -> Unit) {
        main.post { if (gen == generation) block() }
    }

    private fun probe(host: String, port: Int): Found? {
        val s = Socket()
        val t0 = System.nanoTime()
        try {
            s.connect(InetSocketAddress(host, port), 700)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            return null
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val trust = FpTrust()
        return try {
            s.soTimeout = 2500
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            val ssl = ctx.socketFactory.createSocket(s, host, port, true) as SSLSocket
            ssl.startHandshake()
            Found(host, port, true, trust.fp, ms)
        } catch (e: Exception) {
            Found(host, port, false, null, ms)     // port open but no TLS: an Agent with encryption off (or another service)
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun toLong(ip: String): Long? {
        val p = ip.split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) { val n = x.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n.toLong() }
        return v
    }

    private fun fromLong(v: Long): String = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
}
