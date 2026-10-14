package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TlsPolicyTest {
    private val fp = TlsPolicy.fingerprint(byteArrayOf(1, 2, 3))

    @Test fun fingerprintIs64LowerHex() {
        assertEquals(64, fp.length)
        assertTrue(TlsPolicy.valid(fp))
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", fp)
    }

    @Test fun matchingIgnoresCaseAndSeparators() {
        assertTrue(TlsPolicy.matches(fp, fp.uppercase()))
        assertTrue(TlsPolicy.matches(TlsPolicy.display(fp), fp))
        assertFalse(TlsPolicy.matches(fp, fp.replaceRange(0, 1, if (fp[0] == 'a') "b" else "a")))
        assertFalse(TlsPolicy.matches("", ""))
        assertFalse(TlsPolicy.matches("abc", "abc"))
    }

    @Test fun display() {
        assertEquals(95, TlsPolicy.display(fp).length)
        assertEquals("03:90:58:C6:F2:C0:CB:49", TlsPolicy.short(fp))
        assertEquals("", TlsPolicy.display("zz"))
        assertEquals("", TlsPolicy.short("zz"))
    }

    private val sec = TlsPolicy.Attempt(true)
    private val plain = TlsPolicy.Attempt(false)

    @Test fun plainFallbackOnlyWithoutPinAndWhenAllowed() {
        assertEquals(TlsPolicy.Next.PLAIN_FALLBACK, TlsPolicy.afterFailure(sec, null, FailureKind.TLS_PLAIN, true))
        assertEquals(TlsPolicy.Next.TERMINAL_TLS, TlsPolicy.afterFailure(sec, null, FailureKind.TLS_PLAIN, false))
        assertEquals(TlsPolicy.Next.TERMINAL_TLS, TlsPolicy.afterFailure(sec, fp, FailureKind.TLS_PLAIN, true))   // never downgrade a pinned device
    }

    @Test fun pinMismatchAndOtherTlsErrorsAreTerminal() {
        assertEquals(TlsPolicy.Next.TERMINAL_TLS, TlsPolicy.afterFailure(sec, fp, FailureKind.TLS_PIN, true))
        assertEquals(TlsPolicy.Next.TERMINAL_TLS, TlsPolicy.afterFailure(sec, null, FailureKind.TLS, true))
    }

    @Test fun transportErrorsStayNormal() {
        for (k in listOf(FailureKind.TIMEOUT, FailureKind.REFUSED, FailureKind.UNREACHABLE, FailureKind.RESET, FailureKind.OTHER))
            assertEquals(TlsPolicy.Next.NORMAL, TlsPolicy.afterFailure(sec, fp, k, true))
        assertEquals(TlsPolicy.Next.NORMAL, TlsPolicy.afterFailure(plain, null, FailureKind.TLS_PLAIN, true))
    }

    @Test fun terminalTexts() {
        assertTrue("reset the pin" in TlsPolicy.terminalText(FailureKind.TLS_PIN, true))
        assertTrue("Refusing" in TlsPolicy.terminalText(FailureKind.TLS_PLAIN, true))
        assertTrue("Require encryption" in TlsPolicy.terminalText(FailureKind.TLS_PLAIN, false))
    }
}
