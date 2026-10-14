package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppIconsWireTest {
    @Test fun requestRoundTripAndClamp() {
        assertEquals(AppIconRequest("com.a.b", 96), AppIconsWire.parseRequest(AppIconsWire.request("com.a.b", 96)))
        assertEquals(AppIconsWire.MAX_SIZE, AppIconsWire.parseRequest(AppIconsWire.request("com.a.b", 5000))!!.size)
        assertEquals(AppIconsWire.MIN_SIZE, AppIconsWire.parseRequest(AppIconsWire.request("com.a.b", 1))!!.size)
        assertEquals(AppIconsWire.DEFAULT_SIZE, AppIconsWire.parseRequest("""{"type":"APP_ICON","package":"x"}""")!!.size)
        assertNull(AppIconsWire.parseRequest("""{"type":"APP_ICON","package":""}"""))
    }

    @Test fun resultRoundTrip() {
        val r = AppIconResult("com.a.b", true, "iVBORw0KGgo=")
        assertEquals(r, AppIconsWire.parseResult(AppIconsWire.result(r)))
        val e = AppIconResult("com.a.b", false, "")
        assertEquals(e, AppIconsWire.parseResult(AppIconsWire.result(e)))
    }

    @Test fun requestAndResultDoNotMix() {
        assertNull(AppIconsWire.parseRequest(AppIconsWire.result(AppIconResult("x", true, ""))))
        assertNull(AppIconsWire.parseResult(AppIconsWire.request("x")))
        assertNull(AppIconsWire.parseResult("""{"type":"FILE_CHUNK"}"""))
    }
}
