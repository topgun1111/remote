package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureProtocolTest {
    @Test fun captureMessagesRoundTrip() {
        val cfg: Message = CaptureConfig(fps = 10, quality = 70, maxDimension = 1080)
        assertTrue(Protocol.encode(cfg).contains("\"type\":\"CAPTURE_CONFIG\""))
        assertEquals(cfg, Protocol.decode(Protocol.encode(cfg)))

        val st: Message = CaptureStatus(active = true, width = 720, height = 1600, fps = 5, quality = 60)
        assertEquals(st, Protocol.decode(Protocol.encode(st)))

        val frame: Message = ScreenFrame(720, 1600, "QUJD", 123L)
        assertEquals(frame, Protocol.decode(Protocol.encode(frame)))
    }
}
