package com.remotecontrol.shared

/**
 * Session 10a: turns a transport exception into something the person can act on, plus whether retrying makes sense.
 * Pure JVM (matches on exception class names and messages, so it needs no OkHttp / Android types and is testable).
 *
 * Only transport failures are classified. A rejected handshake (wrong PIN, revoked token) is not a transport failure
 * and stays terminal exactly as in Session 2.
 */
/**
 * `TLS` = any other TLS failure; `TLS_PIN` (10b) = the Agent's certificate differs from the pinned one;
 * `TLS_PLAIN` (10b) = we spoke TLS but the peer answered like a plain ws:// server (Agent without TLS).
 */
enum class FailureKind { TIMEOUT, REFUSED, UNREACHABLE, DNS, RESET, TLS, TLS_PIN, TLS_PLAIN, OTHER }

data class Failure(val kind: FailureKind, val retry: Boolean, val text: String)

object FailureClassifier {
    /** What a plain WebSocket server's reply looks like to a TLS client (JDK, Conscrypt/BoringSSL wording). */
    private val PLAIN_HINTS = listOf("wrong version number", "wrong_version_number", "first record does not look like", "unrecognized ssl message",
        "plaintext connection", "reset by peer", "connection closed", "eof")

    /** [remote] = the route that failed was the remote/VPN one (changes the hint). [t] may be null (unknown error). */
    fun classify(t: Throwable?, remote: Boolean): Failure {
        val chain = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(6).toList()
        val names = chain.map { it.javaClass.name }
        val msg = chain.joinToString(" | ") { it.message.orEmpty() }.lowercase()
        fun has(vararg n: String) = names.any { name -> n.any { name.endsWith(it) } }
        val kind = when {
            has("PinMismatchException") || "certificate pin" in msg -> FailureKind.TLS_PIN
            has("SSLException", "SSLHandshakeException", "SSLPeerUnverifiedException", "CertificateException") ->
                if (PLAIN_HINTS.any { it in msg }) FailureKind.TLS_PLAIN else FailureKind.TLS
            has("SocketTimeoutException", "InterruptedIOException") || "timeout" in msg || "timed out" in msg -> FailureKind.TIMEOUT
            has("UnknownHostException") -> FailureKind.DNS
            "refused" in msg || "econnrefused" in msg -> FailureKind.REFUSED
            has("NoRouteToHostException", "PortUnreachableException") || "unreachable" in msg || "enetunreach" in msg || "no route" in msg -> FailureKind.UNREACHABLE
            "reset" in msg || "broken pipe" in msg || "connection abort" in msg || "closed" in msg || has("EOFException") -> FailureKind.RESET
            else -> FailureKind.OTHER
        }
        // A TLS failure will not fix itself by retrying (changed certificate); everything else may.
        val retry = kind != FailureKind.TLS && kind != FailureKind.TLS_PIN && kind != FailureKind.TLS_PLAIN
        return Failure(kind, retry, text(kind, remote))
    }

    fun text(kind: FailureKind, remote: Boolean): String = when (kind) {
        FailureKind.TIMEOUT -> if (remote) "No answer over the VPN. Is Tailscale on at both phones and the Agent running?"
                               else "No answer. Is the Agent running and on the same Wi-Fi?"
        FailureKind.REFUSED -> if (remote) "Agent refused the connection on the VPN address. It may be listening on one interface only."
                               else "Agent refused the connection. Start the Agent first."
        FailureKind.UNREACHABLE -> "Network unreachable. Check Wi-Fi, mobile data or the VPN."
        FailureKind.DNS -> "Address not found. Check the address."
        FailureKind.RESET -> "Connection lost."
        FailureKind.TLS -> "Secure connection failed."
        FailureKind.TLS_PIN -> "The Agent's certificate changed. If you reinstalled the Agent or regenerated its certificate, reset the pin in Settings."
        FailureKind.TLS_PLAIN -> "The Agent does not answer with encryption."
        FailureKind.OTHER -> "Connection failed."
    }
}
