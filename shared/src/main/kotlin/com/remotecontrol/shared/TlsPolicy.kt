package com.remotecontrol.shared

import java.security.MessageDigest

/**
 * Session 10b: pure TLS pinning rules (no Android, no sockets).
 *
 * The Agent has a self-signed certificate (EC P-256, generated once in the Android Keystore). The Controller pins its
 * SHA-256 fingerprint on first use (TOFU) and refuses any other certificate afterwards. A Controller always tries TLS
 * first. Only a device WITHOUT a pin may fall back to plain ws:// (an Agent from before 10b), and only while the person
 * allows it ("Require encryption" off in Settings). A device that has a pin never downgrades.
 */
object TlsPolicy {
    /** SHA-256 of the DER certificate as 64 lowercase hex characters. */
    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    fun normalise(s: String): String = s.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    fun valid(fp: String): Boolean = normalise(fp).length == 64

    /** Constant-time comparison; separators and case do not matter. Invalid input never matches. */
    fun matches(expected: String, actual: String): Boolean {
        val a = normalise(expected); val b = normalise(actual)
        return a.length == 64 && b.length == 64 && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }

    /** `AB:CD:...` (all 32 bytes) for the Agent screen; the Controller shows [short]. Empty for invalid input. */
    fun display(fp: String): String {
        val n = normalise(fp)
        return if (n.length != 64) "" else n.chunked(2).joinToString(":") { it.uppercase() }
    }

    /** First 8 bytes, enough to compare by eye on both phones. */
    fun short(fp: String): String = display(fp).split(':').take(8).joinToString(":")

    data class Attempt(val secure: Boolean)

    enum class Next {
        /** Dial again without TLS (migration from an Agent that has no TLS). */
        PLAIN_FALLBACK,
        /** Stop: the failure will not fix itself (changed certificate, downgrade, TLS error). */
        TERMINAL_TLS,
        /** Not a TLS decision: use the normal route failover and backoff. */
        NORMAL,
    }

    fun afterFailure(attempt: Attempt, pin: String?, kind: FailureKind, allowPlain: Boolean): Next = when {
        !attempt.secure -> Next.NORMAL
        kind == FailureKind.TLS_PIN -> Next.TERMINAL_TLS
        kind == FailureKind.TLS_PLAIN && pin == null && allowPlain -> Next.PLAIN_FALLBACK
        kind == FailureKind.TLS_PLAIN || kind == FailureKind.TLS -> Next.TERMINAL_TLS
        else -> Next.NORMAL
    }

    /** Status text for a [Next.TERMINAL_TLS] stop. */
    fun terminalText(kind: FailureKind, pinned: Boolean): String = when {
        kind == FailureKind.TLS_PIN -> FailureClassifier.text(kind, false)
        kind == FailureKind.TLS_PLAIN && pinned -> "The Agent answered without encryption, but this device was set up with encryption. Refusing."
        kind == FailureKind.TLS_PLAIN -> "The Agent has no encryption and \"Require encryption\" is on. Update the Agent or turn that off in Settings."
        else -> FailureClassifier.text(kind, false)
    }
}
