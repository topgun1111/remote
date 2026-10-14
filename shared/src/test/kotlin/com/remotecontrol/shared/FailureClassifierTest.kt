package com.remotecontrol.shared

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureClassifierTest {
    private fun k(t: Throwable?, remote: Boolean = false) = FailureClassifier.classify(t, remote)

    @Test fun commonTransportErrors() {
        assertEquals(FailureKind.TIMEOUT, k(SocketTimeoutException("connect timed out")).kind)
        assertEquals(FailureKind.REFUSED, k(ConnectException("Connection refused")).kind)
        assertEquals(FailureKind.REFUSED, k(ConnectException("failed to connect: ECONNREFUSED (Connection refused)")).kind)
        assertEquals(FailureKind.UNREACHABLE, k(NoRouteToHostException("No route to host")).kind)
        assertEquals(FailureKind.UNREACHABLE, k(ConnectException("Network is unreachable")).kind)
        assertEquals(FailureKind.DNS, k(UnknownHostException("x")).kind)
        assertEquals(FailureKind.RESET, k(EOFException()).kind)
        assertEquals(FailureKind.RESET, k(java.io.IOException("Connection reset by peer")).kind)
        assertEquals(FailureKind.OTHER, k(IllegalStateException("boom")).kind)
        assertEquals(FailureKind.OTHER, k(null).kind)
    }

    @Test fun causeChainIsSearched() {
        val wrapped = RuntimeException("outer", ConnectException("Connection refused"))
        assertEquals(FailureKind.REFUSED, k(wrapped).kind)
    }

    @Test fun selfReferencingCauseDoesNotLoop() {
        class Loop : Exception("loop") { override val cause: Throwable get() = this }
        assertEquals(FailureKind.OTHER, k(Loop()).kind)
    }

    @Test fun tlsIsNotRetried() {
        val f = k(SSLHandshakeException("PKIX path failed"))
        assertEquals(FailureKind.TLS, f.kind)
        assertFalse(f.retry)
        assertTrue(k(SocketTimeoutException("timeout")).retry)
    }

    @Test fun hintDependsOnRoute() {
        assertTrue("VPN" in k(SocketTimeoutException("timeout"), remote = true).text)
        assertTrue("Wi-Fi" in k(SocketTimeoutException("timeout"), remote = false).text)
    }

    @Test fun tlsSubKinds() {
        class PinMismatchException(m: String) : java.security.cert.CertificateException(m)
        assertEquals(FailureKind.TLS_PIN, k(javax.net.ssl.SSLHandshakeException("x").initCause(PinMismatchException("pin")) as Throwable).kind)
        assertEquals(FailureKind.TLS_PLAIN, k(javax.net.ssl.SSLException("Unrecognized SSL message, plaintext connection?")).kind)
        assertEquals(FailureKind.TLS_PLAIN, k(javax.net.ssl.SSLHandshakeException("error:100000f7:SSL routines:OPENSSL_internal:WRONG_VERSION_NUMBER")).kind)
        assertEquals(FailureKind.TLS, k(SSLHandshakeException("PKIX path failed")).kind)
        for (kind in listOf(FailureKind.TLS, FailureKind.TLS_PIN, FailureKind.TLS_PLAIN)) assertFalse(FailureClassifier.text(kind, false).isEmpty())
    }
}
