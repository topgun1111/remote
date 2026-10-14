package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsLogicTest {
    @Test fun clamps() {
        assertEquals(1, SettingsLogic.clampFps(-4)); assertEquals(30, SettingsLogic.clampFps(99)); assertEquals(12, SettingsLogic.clampFps(12))
        assertEquals(20, SettingsLogic.clampQuality(0)); assertEquals(95, SettingsLogic.clampQuality(100))
    }

    @Test fun sizes() {
        assertEquals(720, SettingsLogic.snapSize(700)); assertEquals(480, SettingsLogic.snapSize(1)); assertEquals(1440, SettingsLogic.snapSize(5000))
        assertEquals(1080, SettingsLogic.nextSize(720)); assertEquals(480, SettingsLogic.nextSize(1440)); assertEquals(1080, SettingsLogic.nextSize(700))
    }
}

class StreamGateLogicTest {
    @Test fun pausedOnlyWhileHiddenAndFresh() {
        assertFalse(StreamGateLogic.paused(true, 1000, 2000))
        assertTrue(StreamGateLogic.paused(false, 1000, 2000))
        assertTrue(StreamGateLogic.paused(false, 1000, 1000 + StreamGateLogic.TTL_MS))
        assertFalse(StreamGateLogic.paused(false, 1000, 1001 + StreamGateLogic.TTL_MS))   // controller vanished
        assertFalse(StreamGateLogic.paused(false, 0, 5))                                   // never reported
        assertFalse(StreamGateLogic.paused(false, 5000, 100))                              // clock went backwards
    }
}
