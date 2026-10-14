package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputGeometryTest {
    private fun close(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.001f

    @Test fun centerMapsToMiddleOfDisplay() {
        val (x, y) = InputGeometry.toScreen(0.5f, 0.5f, 1081, 2401)
        assertTrue(close(x, 540f)); assertTrue(close(y, 1200f))
    }

    @Test fun edgesClampToLastPixel() {
        assertEquals(0f to 0f, InputGeometry.toScreen(-1f, -0.2f, 1080, 2400))
        assertEquals(1079f to 2399f, InputGeometry.toScreen(1f, 7f, 1080, 2400))
    }

    @Test fun degenerateDisplayDoesNotCrash() {
        assertEquals(0f to 0f, InputGeometry.toScreen(0.5f, 0.5f, 0, 0))
    }

    @Test fun scrollDownMovesFingerUp() {
        val s = InputGeometry.scrollSwipe(0.5f, 0.5f, 0f, 0.4f)
        assertTrue(close(s[1], 0.7f)); assertTrue(close(s[3], 0.3f))
        assertTrue(close(s[0], 0.5f)); assertTrue(close(s[2], 0.5f))
    }

    @Test fun scrollRightMovesFingerLeft() {
        val s = InputGeometry.scrollSwipe(0.5f, 0.5f, 0.2f, 0f)
        assertTrue(s[0] > s[2])
    }

    @Test fun scrollIsClampedToScreen() {
        val s = InputGeometry.scrollSwipe(0.9f, 0.9f, 0f, 1.5f)
        assertTrue(s.all { it in 0f..1f })
    }

    @Test fun orientationCheck() {
        assertTrue(InputGeometry.orientationMatches(720, 1600, 1080, 2400))
        assertFalse(InputGeometry.orientationMatches(720, 1600, 2400, 1080))
        assertTrue(InputGeometry.orientationMatches(0, 0, 2400, 1080))
    }
}
