package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FrameGeometryTest {
    private fun close(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.001f

    @Test fun portraitFrameInLandscapeViewIsPillarboxed() {
        val r = assertNotNull(FrameGeometry.fit(720, 1600, 2000, 1000))
        assertEquals(1000f, r.height)
        assertEquals(450f, r.width)
        assertEquals(775f, r.left)
        assertEquals(0f, r.top)
    }

    @Test fun wideFrameInTallViewIsLetterboxed() {
        val r = assertNotNull(FrameGeometry.fit(1600, 720, 800, 1600))
        assertEquals(800f, r.width)
        assertEquals(360f, r.height)
        assertEquals(620f, r.top)
    }

    @Test fun invalidSizesGiveNull() {
        assertNull(FrameGeometry.fit(0, 100, 100, 100))
        assertNull(FrameGeometry.fit(100, 100, 100, 0))
    }

    @Test fun centreMapsToHalfHalf() {
        val r = assertNotNull(FrameGeometry.fit(720, 1600, 2000, 1000))
        val (x, y) = assertNotNull(FrameGeometry.toNormalized(r, 1000f, 500f))
        assertEquals(true, close(x, 0.5f) && close(y, 0.5f))
    }

    @Test fun barsAreOutsideUnlessClamped() {
        val r = assertNotNull(FrameGeometry.fit(720, 1600, 2000, 1000))
        assertNull(FrameGeometry.toNormalized(r, 10f, 500f))
        val (x, y) = assertNotNull(FrameGeometry.toNormalized(r, 10f, 500f, clamp = true))
        assertEquals(0f, x); assertEquals(true, close(y, 0.5f))
    }
}
