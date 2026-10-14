package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconnectPolicyTest {
    @Test fun backoffDoublesWithoutJitter() {
        val d = (0..6).map { ReconnectPolicy.delayMs(it, false, 0.5) }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), d)
    }

    @Test fun remoteCapIsHigher() {
        assertEquals(60000L, ReconnectPolicy.delayMs(9, true, 0.5))
        assertEquals(30000L, ReconnectPolicy.delayMs(9, false, 0.5))
    }

    @Test fun jitterStaysInBandAndUnderCap() {
        for (a in 0..12) for (r in listOf(0.0, 0.25, 0.5, 1.0)) {
            val lan = ReconnectPolicy.delayMs(a, false, r)
            val rem = ReconnectPolicy.delayMs(a, true, r)
            assertTrue(lan in 500L..ReconnectPolicy.CAP_LAN_MS, "$a $r $lan")
            assertTrue(rem in 500L..ReconnectPolicy.CAP_REMOTE_MS, "$a $r $rem")
        }
        assertEquals(800L, ReconnectPolicy.delayMs(0, false, 0.0))
        assertEquals(1200L, ReconnectPolicy.delayMs(0, false, 1.0))
    }

    @Test fun badInputIsClamped() {
        assertEquals(1000L, ReconnectPolicy.delayMs(-3, false, 0.5))
        assertEquals(1200L, ReconnectPolicy.delayMs(0, false, 7.0))
    }

    @Test fun stableLinkResetsCounter() {
        assertEquals(0, ReconnectPolicy.attemptAfterDrop(5, 20_000))
        assertEquals(0, ReconnectPolicy.attemptAfterDrop(5, 60_000))
        assertEquals(5, ReconnectPolicy.attemptAfterDrop(5, 19_999))
        assertEquals(0, ReconnectPolicy.attemptAfterDrop(-2, 0))
    }

    @Test fun networkChangeIsQuick() { assertEquals(300L, ReconnectPolicy.afterNetworkChange()) }
}
