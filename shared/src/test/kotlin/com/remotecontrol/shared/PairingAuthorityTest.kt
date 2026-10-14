package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingAuthorityTest {
    private class MemStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private fun hello(pin: String? = null, token: String? = null, v: Int = PROTOCOL_VERSION) =
        Hello(protocolVersion = v, deviceName = "test", pin = pin, token = token)

    @Test fun pinIsSixDigits() {
        val pin = PairingAuthority(MemStore()).currentPin()
        assertEquals(6, pin.length); assertTrue(pin.all { it.isDigit() })
    }

    @Test fun correctPinIssuesTokenAndRotatesPin() {
        val a = PairingAuthority(MemStore())
        val pin = a.currentPin()
        val r = a.authenticate(hello(pin = pin))
        assertIs<AuthResult.Accepted>(r)
        assertNotNull(r.newToken)
        assertEquals(1, a.pairedCount())
        assertEquals(6, a.currentPin().length) // a fresh single-use PIN was issued
    }

    @Test fun tokenAuthenticatesAcrossRestart() {
        val store = MemStore()
        val a = PairingAuthority(store)
        val token = (a.authenticate(hello(pin = a.currentPin())) as AuthResult.Accepted).newToken!!
        val restarted = PairingAuthority(store) // same persisted store
        val r = restarted.authenticate(hello(token = token))
        assertIs<AuthResult.Accepted>(r); assertNull(r.newToken)
    }

    @Test fun unknownTokenRejectedNeedsPairing() {
        val r = PairingAuthority(MemStore()).authenticate(hello(token = "nope"))
        assertIs<AuthResult.Rejected>(r); assertTrue(r.needsPairing)
    }

    @Test fun tokenNotStoredInPlaintext() {
        val store = MemStore()
        val a = PairingAuthority(store)
        val token = (a.authenticate(hello(pin = a.currentPin())) as AuthResult.Accepted).newToken!!
        assertTrue(store.map.values.none { it.contains(token) })
    }

    @Test fun wrongPinLocksOutAndRegenerates() {
        val a = PairingAuthority(MemStore(), maxPinAttempts = 3)
        val pin = a.currentPin()
        val wrong = if (pin == "000000") "000001" else "000000"
        repeat(2) { assertIs<AuthResult.Rejected>(a.authenticate(hello(pin = wrong))) }
        val last = a.authenticate(hello(pin = wrong))
        assertIs<AuthResult.Rejected>(last); assertTrue(last.reason.contains("Too many"))
        // old PIN must no longer work after regeneration (unless it collided, 1e-6)
        val newPin = a.currentPin()
        if (newPin != pin) assertIs<AuthResult.Rejected>(a.authenticate(hello(pin = pin)))
        assertEquals(0, a.pairedCount())
    }

    @Test fun revokeAllInvalidatesTokens() {
        val a = PairingAuthority(MemStore())
        val token = (a.authenticate(hello(pin = a.currentPin())) as AuthResult.Accepted).newToken!!
        a.revokeAll()
        assertEquals(0, a.pairedCount())
        assertIs<AuthResult.Rejected>(a.authenticate(hello(token = token)))
    }

    @Test fun protocolMismatchRejected() {
        val a = PairingAuthority(MemStore())
        val r = a.authenticate(hello(pin = a.currentPin(), v = 1))
        assertIs<AuthResult.Rejected>(r); assertEquals(false, r.needsPairing)
    }

    @Test fun jsonRoundTrip() {
        val m: Message = Hello(deviceName = "x", pin = "123456")
        val text = Protocol.encode(m)
        assertTrue(text.contains("\"type\":\"HELLO\""))
        assertEquals(m, Protocol.decode(text))
        val ack: Message = HelloAck(true, "agent", token = "abc")
        assertEquals(ack, Protocol.decode(Protocol.encode(ack)))
    }
}
