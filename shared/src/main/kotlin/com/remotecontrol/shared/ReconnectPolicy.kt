package com.remotecontrol.shared

import kotlin.math.min

/**
 * Session 10a: when to retry after a lost connection. Pure JVM.
 * Backoff 1 s doubling, +-20 % jitter, cap 30 s on the LAN / 60 s over the VPN. A link that held for at least
 * [STABLE_MS] starts over. After a network change retry almost at once.
 *
 * Usage: `val idx = attemptAfterDrop(counter, uptimeMs); delay = delayMs(idx, remote, random); counter = idx + 1`.
 */
object ReconnectPolicy {
    const val BASE_MS = 1_000L
    const val CAP_LAN_MS = 30_000L
    const val CAP_REMOTE_MS = 60_000L
    const val STABLE_MS = 20_000L
    const val NETWORK_CHANGE_MS = 300L
    const val JITTER = 0.2

    /** Attempt index to use for this delay: 0 when the link that just dropped had been up for [STABLE_MS], else [counter]. */
    fun attemptAfterDrop(counter: Int, uptimeMs: Long): Int = if (uptimeMs >= STABLE_MS) 0 else counter.coerceAtLeast(0)

    /** [random] in 0.0..1.0 (injected for tests). */
    fun delayMs(attempt: Int, remote: Boolean, random: Double): Long {
        val cap = if (remote) CAP_REMOTE_MS else CAP_LAN_MS
        val exp = BASE_MS shl min(attempt.coerceAtLeast(0), 10)
        val factor = 1.0 - JITTER + 2 * JITTER * random.coerceIn(0.0, 1.0)
        return min(cap, (exp * factor).toLong()).coerceAtLeast(BASE_MS / 2)
    }

    fun afterNetworkChange(): Long = NETWORK_CHANGE_MS
}
