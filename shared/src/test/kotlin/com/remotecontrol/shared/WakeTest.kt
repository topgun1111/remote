package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeTest {
    private fun st(on: Boolean, locked: Boolean, secure: Boolean = false, admin: Boolean = true) =
        ScreenState(on, locked, secure, admin)

    @Test fun wireRoundTrip() {
        assertEquals(WakeRequest(true, false), WakeWire.parseRequest(WakeWire.wake(true)))
        assertEquals(WakeRequest(false, false), WakeWire.parseRequest(WakeWire.wake(false)))
        assertEquals(true, WakeWire.parseRequest(WakeWire.lock())?.lock)
        val r = WakeResult(false, true, true, true, false, "a \"quoted\" detail\nline")
        assertEquals(r, WakeWire.parseResult(WakeWire.result(r)))
    }

    @Test fun parsersIgnoreOtherMessages() {
        assertNull(WakeWire.parseRequest("""{"type":"INPUT_EVENT","action":"TAP"}"""))
        assertNull(WakeWire.parseRequest("not json \"WAKE\""))
        assertNull(WakeWire.parseResult("""{"type":"HELLO_ACK"}"""))
        assertNotNull(WakeWire.parseRequest("""{"type":"WAKE"}"""))
    }

    @Test fun planWakesOnlyWhenNeeded() {
        val req = WakeRequest(unlock = true)
        assertEquals(listOf(WakeStep.ACQUIRE_WAKE_LOCK, WakeStep.DISMISS_KEYGUARD), WakeLogic.plan(req, st(false, true)))
        assertEquals(listOf(WakeStep.DISMISS_KEYGUARD), WakeLogic.plan(req, st(true, true)))
        assertEquals(emptyList(), WakeLogic.plan(req, st(true, false)))
        assertEquals(listOf(WakeStep.ACQUIRE_WAKE_LOCK), WakeLogic.plan(WakeRequest(unlock = false), st(false, true)))
    }

    @Test fun lockNeedsAdmin() {
        val req = WakeRequest(unlock = false, lock = true)
        assertEquals(listOf(WakeStep.LOCK_NOW), WakeLogic.plan(req, st(true, false)))
        assertEquals(emptyList(), WakeLogic.plan(req, st(true, false, admin = false)))
        assertTrue(!WakeLogic.outcome(req, st(true, false, admin = false), st(true, false, admin = false)).ok)
        assertTrue(WakeLogic.outcome(req, st(true, false), st(false, true)).ok)
    }

    @Test fun secureLockIsReportedHonestly() {
        val req = WakeRequest(unlock = true)
        val r = WakeLogic.outcome(req, st(false, true, secure = true), st(true, true, secure = true))
        assertTrue(!r.ok && r.screenOn && r.locked && r.detail.contains("PIN"))
    }

    @Test fun swipeLockOrSmartLockSucceeds() {
        val req = WakeRequest(unlock = true)
        assertTrue(WakeLogic.outcome(req, st(false, true), st(true, false)).ok)
        assertTrue(!WakeLogic.outcome(req, st(false, true), st(false, true)).ok)
    }

    @Test fun trackerSucceedsOnOkResult() {
        val t = WakeTracker(8000); t.onSent(1000)
        t.onFrame(1200)
        t.onResult(WakeResult(true, true, false, false, true, "Screen is on and unlocked."))
        assertTrue(t.state is WakeTracker.State.Success)
    }

    @Test fun trackerFailsOnNotOkAndOnTimeout() {
        val a = WakeTracker(8000); a.onSent(0)
        a.onResult(WakeResult(false, true, true, true, true, "locked"))
        assertEquals(WakeTracker.State.Failed("locked"), a.state)

        val b = WakeTracker(8000); b.onSent(0)
        assertEquals(WakeTracker.State.Waiting, b.poll(7999))
        assertTrue(b.poll(8000) is WakeTracker.State.Failed)

        val c = WakeTracker(8000); c.onSent(100)
        c.onFrame(50) // older than the request: ignored
        assertTrue(c.poll(9000) is WakeTracker.State.Failed)
        val d = WakeTracker(8000); d.onSent(100); d.onFrame(500)
        assertTrue(d.poll(9000) is WakeTracker.State.Success)
    }
}
