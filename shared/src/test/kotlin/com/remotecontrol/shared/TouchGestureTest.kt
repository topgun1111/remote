package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchGestureTest {
    private fun cls() = TouchGestureClassifier(slopPx = 20f, longPressMs = 500)

    @Test fun shortStillTouchIsTap() {
        val c = cls(); c.down(100f, 200f, 0); c.move(103f, 202f)
        assertEquals(Gesture.Tap(100f, 200f), c.up(103f, 202f, 120))
    }

    @Test fun heldTouchIsLongPress() {
        val c = cls(); c.down(10f, 10f, 0)
        assertEquals(Gesture.LongPress(10f, 10f, 800), c.up(10f, 10f, 800))
    }

    @Test fun dragIsSwipeWithMinimumDuration() {
        val c = cls(); c.down(100f, 500f, 0); c.move(100f, 300f)
        assertEquals(Gesture.Swipe(100f, 500f, 100f, 100f, 50), c.up(100f, 100f, 30))
    }

    @Test fun dragBackToStartIsIgnored() {
        val c = cls(); c.down(100f, 100f, 0); c.move(100f, 300f)
        assertNull(c.up(105f, 102f, 200))
    }

    @Test fun upWithoutDownAndCancelGiveNothing() {
        val c = cls()
        assertNull(c.up(1f, 1f, 5))
        c.down(1f, 1f, 0); c.cancel()
        assertNull(c.up(1f, 1f, 5))
    }

    @Test fun wireTapMatchesAgentFormat() {
        assertEquals(
            """{"type":"INPUT_EVENT","action":"TAP","x":0.5000,"y":0.2500,"frameW":720,"frameH":1600}""",
            InputWire.tap(0.5f, 0.25f, 720, 1600),
        )
    }

    @Test fun wireOmitsUnknownFrameSizeAndClampsDuration() {
        val s = InputWire.longPress(0.1f, 0.2f, 99999)
        assertTrue(s.contains("\"durationMs\":3000")); assertTrue(!s.contains("frameW"))
    }

    @Test fun textIsEscaped() {
        assertEquals(
            """{"type":"INPUT_EVENT","action":"TEXT","text":"a\"b\\c\n"}""",
            InputWire.text("a\"b\\c\n"),
        )
    }

    @Test fun gestureOutsideFrameIsDropped() {
        val map: (Float, Float) -> Pair<Float, Float>? = { x, y -> if (x < 0f) null else x / 100f to y / 100f }
        assertNull(InputWire.fromGesture(Gesture.Tap(-5f, 10f), 0, 0, map))
        assertNull(InputWire.fromGesture(Gesture.Swipe(10f, 10f, -1f, 10f, 100), 0, 0, map))
        assertNotNull(InputWire.fromGesture(Gesture.Swipe(10f, 10f, 50f, 10f, 100), 0, 0, map))
    }
}
