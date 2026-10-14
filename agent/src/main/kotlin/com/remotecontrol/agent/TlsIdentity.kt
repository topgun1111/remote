package com.remotecontrol.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.remotecontrol.shared.TlsPolicy
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * Session 10b: the Agent's TLS identity. One self-signed EC P-256 certificate whose private key lives in the Android
 * Keystore (never exported), created on first use. The Controller pins its SHA-256 fingerprint ([fingerprint], shown in
 * RC Security) the first time it connects. [regenerate] makes a new key: every pinned Controller then refuses the Agent
 * until its pin is reset in the Controller's Settings.
 *
 * Hook in `AgentServer` (see `SESSION10B-INTEGRATION.md`): `if (TlsIdentity.enabled(ctx)) setWebSocketFactory(TlsIdentity.serverFactory())`.
 * Switching it on or off takes effect when the Agent service is restarted.
 */
object TlsIdentity {
    private const val ALIAS = "rc_agent_tls"
    private const val PREFS = "agent_tls"

    fun enabled(context: Context): Boolean = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply() }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun certificate(): X509Certificate {
        val ks = keyStore()
        (ks.getCertificate(ALIAS) as? X509Certificate)?.let { return it }
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // DIGEST_NONE is needed: the TLS stack asks the Keystore for a raw ECDSA signature over its own hash.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=RemoteControl Agent"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 24L * 3600_000))
            .setCertificateNotAfter(Date(now + 25L * 365 * 24 * 3600_000))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        return ks.getCertificate(ALIAS) as X509Certificate
    }

    /** 64 hex characters (`TlsPolicy.display` formats it for people). */
    fun fingerprint(): String = TlsPolicy.fingerprint(certificate().encoded)

    /** Deletes the key and makes a new one. Every pinned Controller must reset its pin afterwards. */
    @Synchronized
    fun regenerate() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        certificate()
    }

    fun sslContext(): SSLContext {
        certificate()                                                   // make sure the key exists
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore(), null) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    /** For `WebSocketServer.setWebSocketFactory`: the server then speaks TLS (wss://) only. */
    fun serverFactory(): DefaultSSLWebSocketServerFactory = DefaultSSLWebSocketServerFactory(sslContext())
}
