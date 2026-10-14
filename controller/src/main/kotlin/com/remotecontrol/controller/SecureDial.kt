package com.remotecontrol.controller

import android.util.Log
import com.remotecontrol.shared.FailureClassifier
import com.remotecontrol.shared.FailureKind
import com.remotecontrol.shared.Route
import com.remotecontrol.shared.TlsPolicy
import okhttp3.OkHttpClient
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** The Agent's certificate differs from the pinned one. (The name is matched by `FailureClassifier`.) */
class PinMismatchException(message: String) : CertificateException(message)

/**
 * Trust manager that trusts exactly one certificate: [expected] (SHA-256 hex) if there is a pin, otherwise the first one
 * it sees (trust on first use; the fingerprint is kept in [seen] and only saved after the handshake was accepted).
 * Hostname checks are off on purpose: the connection goes to IP addresses, and the pin is the identity.
 */
class PinTrust(private val expected: String?) : X509TrustManager {
    @Volatile var seen: String? = null

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("client certificates are not used") }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val fp = TlsPolicy.fingerprint(leaf.encoded)
        seen = fp
        if (expected != null && !TlsPolicy.matches(expected, fp)) throw PinMismatchException("certificate pin mismatch")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Session 10b: one dial attempt's TLS state for `ConnectionManager`, built by [SecureDial.begin]. [secure] false =
 * plain ws:// (only after a permitted fallback, see `TlsPolicy.afterFailure`).
 */
class DialAttempt(val key: String, val secure: Boolean, val trust: PinTrust?)

/**
 * Session 10b: wss:// with certificate pinning, plus the plain fallback for Agents from before 10b.
 * Usage (details in `SESSION10B-INTEGRATION.md`):
 *  - [begin] per connection cycle -> [DialAttempt] (secure)
 *  - [client] / [url] for the route being dialled
 *  - handshake accepted -> [accepted] (saves the pin on first use)
 *  - transport failure -> [onFailure]: a non-null result is a NEW attempt to dial now (plain fallback) or the person-readable text of a terminal TLS stop
 */
class SecureDial(private val settings: SettingsStore) {
    sealed class Outcome {
        /** Dial again with this attempt right now (the route does not change). */
        class Retry(val attempt: DialAttempt) : Outcome()
        /** Stop for good, show [text] (no retries: it will not fix itself). */
        class Terminal(val text: String) : Outcome()
        /** Not a TLS matter: normal failover and backoff. */
        object Normal : Outcome()
    }

    fun begin(key: String): DialAttempt = DialAttempt(key, true, PinTrust(settings.pin(key)))

    fun client(base: OkHttpClient, route: Route, a: DialAttempt): OkHttpClient {
        val c = RouteConnector.clientFor(base, route)
        val t = a.trust ?: return c
        if (!a.secure) return c
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(t), null) }
        return c.newBuilder().sslSocketFactory(ctx.socketFactory, t).hostnameVerifier { _, _ -> true }.build()
    }

    fun url(route: Route, a: DialAttempt): String = (if (a.secure) "wss" else "ws") + "://${route.host}:${route.port}"

    /** Call when the handshake was accepted. Saves the pin on first use. Returns `true` if the link is encrypted. */
    fun accepted(a: DialAttempt): Boolean {
        if (!a.secure) { Log.w(TAG, "connected WITHOUT encryption (Agent has no TLS)"); return false }
        val seen = a.trust?.seen
        if (seen != null && settings.pin(a.key) == null) {
            settings.setPin(a.key, seen)
            Log.i(TAG, "pinned Agent certificate ${TlsPolicy.short(seen)}")
        }
        return true
    }

    fun onFailure(a: DialAttempt, t: Throwable?, remote: Boolean): Outcome {
        val kind = FailureClassifier.classify(t, remote).kind
        val pin = settings.pin(a.key)
        return when (TlsPolicy.afterFailure(TlsPolicy.Attempt(a.secure), pin, kind, allowPlain = !settings.requireTls)) {
            TlsPolicy.Next.PLAIN_FALLBACK -> Outcome.Retry(DialAttempt(a.key, false, null))
            TlsPolicy.Next.TERMINAL_TLS -> Outcome.Terminal(TlsPolicy.terminalText(kind, pin != null))
            TlsPolicy.Next.NORMAL -> Outcome.Normal
        }
    }

    /** Short text for the status line / viewer top bar: `TLS`, `TLS (new)` on the first pin, `PLAIN`. */
    fun badge(a: DialAttempt, firstUse: Boolean = false): String = if (!a.secure) "PLAIN" else if (firstUse) "TLS (new)" else "TLS"

    private companion object { const val TAG = "RemoteControl" }
}
