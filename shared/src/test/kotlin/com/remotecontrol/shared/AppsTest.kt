package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppsTest {
    private val apps = listOf(
        AppEntry("com.android.settings", "Ayarlar", true),
        AppEntry("com.whatsapp", "WhatsApp", false),
        AppEntry("com.example.isik", "Işık \"Lamba\"", false),
        AppEntry("com.android.chrome", "chrome", false),
    )

    @Test fun listRoundTripWithSpecialCharacters() {
        assertEquals(apps, AppsWire.parseListResult(AppsWire.listResult(apps)))
        assertEquals(emptyList(), AppsWire.parseListResult(AppsWire.listResult(emptyList())))
    }

    @Test fun launchRoundTrip() {
        assertEquals("com.whatsapp", AppsWire.parseLaunchRequest(AppsWire.launch("com.whatsapp")))
        val r = LaunchResult(true, "com.x.y", "Ünlü \"App\"", "DIRECT", "line1\nline2")
        assertEquals(r, AppsWire.parseLaunchResult(AppsWire.launchResult(r)))
    }

    @Test fun parsersIgnoreOtherMessages() {
        assertTrue(AppsWire.isListRequest(AppsWire.listRequest()))
        assertFalse(AppsWire.isListRequest(AppsWire.listResult(apps)))
        assertFalse(AppsWire.isListRequest("""{"type":"WAKE"}"""))
        assertFalse(AppsWire.isListRequest("not json \"APP_LIST\""))
        assertNull(AppsWire.parseLaunchRequest("""{"type":"INPUT_EVENT"}"""))
        assertNull(AppsWire.parseLaunchRequest("LAUNCH_APP but not json"))
        assertNull(AppsWire.parseListResult("""{"type":"HELLO_ACK"}"""))
        assertNull(AppsWire.parseLaunchResult("""{"type":"WAKE_RESULT"}"""))
        assertEquals("", AppsWire.parseLaunchRequest("""{"type":"LAUNCH_APP"}"""))
        assertNotNull(AppsWire.parseLaunchResult("""{"type":"LAUNCH_RESULT"}"""))
    }

    @Test fun malformedEntriesAreSkipped() {
        val raw = """{"type":"APP_LIST_RESULT","apps":[{"label":"no pkg"},5,{"pkg":"a.b","label":""}]}"""
        assertEquals(listOf(AppEntry("a.b", "a.b", false)), AppsWire.parseListResult(raw))
    }

    @Test fun packageValidation() {
        assertTrue(AppsLogic.isValidPackage("com.android.chrome"))
        assertTrue(AppsLogic.isValidPackage("com.a_b.c1"))
        assertFalse(AppsLogic.isValidPackage(""))
        assertFalse(AppsLogic.isValidPackage("nodots"))
        assertFalse(AppsLogic.isValidPackage("com..x"))
        assertFalse(AppsLogic.isValidPackage("com.x; rm -rf"))
        assertFalse(AppsLogic.isValidPackage("1com.x"))
    }

    @Test fun filterSortsHidesSystemAndFoldsTurkishI() {
        assertEquals(listOf("Ayarlar", "chrome", "Işık \"Lamba\"", "WhatsApp"), AppsLogic.filter(apps, "", false).map { it.label })
        assertEquals(listOf("chrome", "Işık \"Lamba\"", "WhatsApp"), AppsLogic.filter(apps, "  ", true).map { it.label })
        assertEquals(listOf("com.example.isik"), AppsLogic.filter(apps, "ISIK", false).map { it.pkg })
        assertEquals(listOf("com.example.isik"), AppsLogic.filter(apps, "ışık", false).map { it.pkg })
        assertEquals(listOf("com.example.isik"), AppsLogic.filter(apps, "İŞİK", false).map { it.pkg })
        assertEquals(listOf("com.whatsapp"), AppsLogic.filter(apps, "com.whats", false).map { it.pkg })
        assertTrue(AppsLogic.filter(apps, "zzz", false).isEmpty())
    }

    @Test fun planDependsOnExemption() {
        assertEquals(emptyList(), AppsLogic.plan(false, true, true, 34))
        assertEquals(emptyList(), AppsLogic.plan(true, false, true, 34))
        assertEquals(listOf(LaunchStep.START_ACTIVITY), AppsLogic.plan(true, true, true, 34))
        assertEquals(listOf(LaunchStep.START_ACTIVITY), AppsLogic.plan(true, true, false, 28))
        assertEquals(listOf(LaunchStep.START_ACTIVITY, LaunchStep.POST_NOTIFICATION), AppsLogic.plan(true, true, false, 34))
    }

    @Test fun outcomeIsHonest() {
        fun o(installed: Boolean = true, intent: Boolean = true, attempted: Boolean = true, err: String? = null,
              notified: Boolean = false, draw: Boolean = true, pkg: String = "com.x.y") =
            AppsLogic.outcome(pkg, "X", installed, intent, attempted, err, notified, draw)
        assertTrue(!o(pkg = "bad").ok)
        assertTrue(!o(installed = false).ok && o(installed = false).detail.contains("not installed"))
        assertTrue(!o(intent = false).ok)
        assertEquals("DIRECT", o().via)
        assertTrue(o().ok)
        assertEquals("DIRECT+NOTIFICATION", o(notified = true, draw = false).via)
        assertEquals("NOTIFICATION", o(err = "boom", notified = true).via)
        assertTrue(!o(err = "boom").ok && o(err = "boom").detail.contains("boom"))
        assertTrue(o(draw = false).detail.contains("blocked"))
    }
}
