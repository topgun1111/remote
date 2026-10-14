package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteLogicTest {
    private val wifi = listOf(LocalAddr("192.168.1.5", 24))

    @Test fun parseIpv4IsStrict() {
        assertEquals(0xC0A80114L, RouteLogic.parseIpv4("192.168.1.20"))
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "1.2.3.-4", "a.b.c.d", "1.2.3.4 ", "::1", "1..3.4"))
            assertNull(RouteLogic.parseIpv4(bad), bad)
    }

    @Test fun classify() {
        assertEquals(AddressClass.PRIVATE_LAN, RouteLogic.classify("192.168.0.1"))
        assertEquals(AddressClass.PRIVATE_LAN, RouteLogic.classify("10.1.2.3"))
        assertEquals(AddressClass.PRIVATE_LAN, RouteLogic.classify("172.16.0.1"))
        assertEquals(AddressClass.PRIVATE_LAN, RouteLogic.classify("172.31.255.255"))
        assertEquals(AddressClass.PUBLIC, RouteLogic.classify("172.32.0.1"))
        assertEquals(AddressClass.CGNAT, RouteLogic.classify("100.64.0.1"))
        assertEquals(AddressClass.CGNAT, RouteLogic.classify("100.127.255.254"))
        assertEquals(AddressClass.PUBLIC, RouteLogic.classify("100.128.0.1"))
        assertEquals(AddressClass.LOOPBACK, RouteLogic.classify("127.0.0.1"))
        assertEquals(AddressClass.LINK_LOCAL, RouteLogic.classify("169.254.1.1"))
        assertEquals(AddressClass.MULTICAST, RouteLogic.classify("224.0.0.1"))
        assertEquals(AddressClass.UNSPECIFIED, RouteLogic.classify("0.0.0.0"))
        assertEquals(AddressClass.INVALID, RouteLogic.classify("nope"))
        assertEquals(AddressClass.PUBLIC, RouteLogic.classify("8.8.8.8"))
        assertTrue(RouteLogic.usable(AddressClass.CGNAT))
        assertFalse(RouteLogic.usable(AddressClass.LOOPBACK))
        assertFalse(RouteLogic.usable(AddressClass.INVALID))
    }

    @Test fun parseEndpoint() {
        assertEquals("100.90.1.2" to 8765, RouteLogic.parseEndpoint("100.90.1.2"))
        assertEquals("100.90.1.2" to 9000, RouteLogic.parseEndpoint(" 100.90.1.2:9000 "))
        assertEquals("100.90.1.2" to 8765, RouteLogic.parseEndpoint("ws://100.90.1.2/"))
        assertEquals("100.90.1.2" to 1234, RouteLogic.parseEndpoint("wss://100.90.1.2:1234/path?x=1"))
        assertEquals("my-phone.tail1234.ts.net" to 8765, RouteLogic.parseEndpoint("My-Phone.tail1234.ts.net"))
        assertEquals("pixel" to 8765, RouteLogic.parseEndpoint("pixel"))
        for (bad in listOf("", " ", ":8765", "host:", "host:0", "host:70000", "host:12a", "[::1]:8765", "fe80::1", "127.0.0.1",
            "300.1.1.1", "1.2.3", "-bad-.example", "a b", "host:1:2"))
            assertNull(RouteLogic.parseEndpoint(bad), bad)
    }

    @Test fun formatEndpoint() {
        assertEquals("100.90.1.2", RouteLogic.formatEndpoint("100.90.1.2", 8765))
        assertEquals("100.90.1.2:9000", RouteLogic.formatEndpoint("100.90.1.2", 9000))
        val ep = RouteLogic.parseEndpoint(RouteLogic.formatEndpoint("h.example", 9000))
        assertEquals("h.example" to 9000, ep)
    }

    @Test fun subnet() {
        assertTrue(RouteLogic.onSameSubnet("192.168.1.20", wifi))
        assertFalse(RouteLogic.onSameSubnet("192.168.2.20", wifi))
        assertFalse(RouteLogic.onSameSubnet("192.168.1.20", emptyList()))
        assertFalse(RouteLogic.onSameSubnet("junk", wifi))
        assertTrue(RouteLogic.onSameSubnet("10.200.0.9", listOf(LocalAddr("10.0.0.4", 8))))
    }

    @Test fun planLanFirstOnSameSubnet() {
        val r = RouteLogic.plan("192.168.1.20", 8765, "100.90.1.2" to 8765, wifi)
        assertEquals(listOf(RouteKind.LAN, RouteKind.REMOTE), r.map { it.kind })
        assertEquals(RouteLogic.LAN_FIRST_TIMEOUT_MS, r[0].connectTimeoutMs)
        assertEquals(RouteLogic.REMOTE_TIMEOUT_MS, r[1].connectTimeoutMs)
    }

    @Test fun planRemoteFirstElsewhere() {
        val r = RouteLogic.plan("192.168.1.20", 8765, "100.90.1.2" to 8765, emptyList())
        assertEquals(listOf(RouteKind.REMOTE, RouteKind.LAN), r.map { it.kind })
        assertEquals(RouteLogic.LAN_FALLBACK_TIMEOUT_MS, r[1].connectTimeoutMs)
        val other = RouteLogic.plan("192.168.1.20", 8765, "100.90.1.2" to 8765, listOf(LocalAddr("10.0.0.2", 24)))
        assertEquals(RouteKind.REMOTE, other[0].kind)
    }

    @Test fun planWithoutRemoteIsLanOnly() {
        val r = RouteLogic.plan("192.168.1.20", 8765, null, emptyList())
        assertEquals(1, r.size)
        assertEquals(RouteLogic.LAN_ONLY_TIMEOUT_MS, r[0].connectTimeoutMs)
        assertEquals(emptyList(), RouteLogic.plan("", 8765, null, wifi))
    }

    @Test fun planDropsDuplicateRemote() {
        val r = RouteLogic.plan("192.168.1.20", 8765, "192.168.1.20" to 8765, wifi)
        assertEquals(1, r.size)
        assertEquals(2, RouteLogic.plan("192.168.1.20", 8765, "192.168.1.20" to 9000, wifi).size)
    }

    @Test fun describe() {
        assertEquals("Local network 192.168.1.20:8765", RouteLogic.describe(Route(RouteKind.LAN, "192.168.1.20", 8765, 1)))
        assertTrue(RouteLogic.describe(Route(RouteKind.REMOTE, "100.90.1.2", 8765, 1)).startsWith("Remote (VPN"))
    }

    @Test fun sessionWalksRoutes() {
        val a = Route(RouteKind.LAN, "a", 1, 1); val b = Route(RouteKind.REMOTE, "b", 2, 1)
        val s = RouteSession(listOf(a, b))
        assertEquals(a, s.current())
        assertNull(s.connected)
        assertEquals(b, s.failed())
        assertEquals(b, s.succeeded())
        assertEquals(b, s.connected)
        assertNull(s.failed())
        assertNull(s.current())
        assertNull(s.connected)
        assertNull(RouteSession(emptyList()).current())
    }
}
