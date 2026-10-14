package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressesWireTest {
    private val sample = AddressesResult(
        8765, true,
        listOf(AgentAddress("100.90.1.2", "tun0", "VPN"), AgentAddress("192.168.1.20", "wlan0", "LAN")),
    )

    @Test fun resultRoundTrip() {
        assertEquals(sample, AddressesWire.parseResult(AddressesWire.result(sample)))
        val empty = AddressesResult(9000, false, emptyList())
        assertEquals(empty, AddressesWire.parseResult(AddressesWire.result(empty)))
    }

    @Test fun requestAndResultDoNotMix() {
        assertTrue(AddressesWire.isRequest(AddressesWire.request()))
        assertFalse(AddressesWire.isRequest(AddressesWire.result(sample)))
        assertNull(AddressesWire.parseResult(AddressesWire.request()))
        assertNull(AddressesWire.parseResult("""{"type":"FILE_CHUNK"}"""))
        assertNull(AddressesWire.parseResult("not json ADDRESSES_RESULT"))
        assertFalse(AddressesWire.isRequest("""{"type":"APP_ICON"}"""))
    }

    @Test fun parseDropsBadEntriesAndNormalisesKinds() {
        val raw = """{"type":"ADDRESSES_RESULT","port":99999,"addrs":[
            {"ip":"127.0.0.1","iface":"lo","kind":"LAN"},
            {"ip":"fe80::1","iface":"x","kind":"LAN"},
            {"ip":"300.1.1.1","kind":"LAN"},
            {"iface":"x"},
            {"ip":"10.0.0.5","iface":"eth0","kind":"WEIRD"},
            "junk"]}"""
        val r = AddressesWire.parseResult(raw)!!
        assertEquals(RouteLogic.DEFAULT_PORT, r.port)            // bad port -> default
        assertFalse(r.vpnUp)
        assertEquals(listOf(AgentAddress("10.0.0.5", "eth0", "OTHER")), r.addrs)
    }

    @Test fun parseCapsEntryCount() {
        val many = AddressesResult(8765, false, (1..40).map { AgentAddress("10.0.0.$it", "e", "LAN") })
        assertEquals(AddressesWire.MAX_ADDRS, AddressesWire.parseResult(AddressesWire.result(many))!!.addrs.size)
    }

    @Test fun suggestUsesVpnAddressOnly() {
        assertEquals("100.90.1.2" to 8765, AddressesWire.suggestRemote(sample))
        val lanOnly = AddressesResult(8765, false, listOf(AgentAddress("192.168.1.20", "wlan0", "LAN")))
        assertNull(AddressesWire.suggestRemote(lanOnly))
        // carrier-grade NAT address on mobile data: 100.64/10 range but kind OTHER -> never suggested
        val cgnat = AddressesResult(8765, false, listOf(AgentAddress("100.70.3.4", "rmnet_data0", "OTHER")))
        assertNull(AddressesWire.suggestRemote(cgnat))
        assertEquals("100.90.1.2" to 9000, AddressesWire.suggestRemote(sample.copy(port = 9000)))
    }
}
