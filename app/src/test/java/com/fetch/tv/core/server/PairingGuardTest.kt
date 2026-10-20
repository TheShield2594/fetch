package com.fetch.tv.core.server

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingGuardTest {
    @Test
    fun `local network addresses are recognised`() {
        listOf("127.0.0.1", "192.168.1.5", "10.0.0.2", "172.20.1.1", "169.254.3.4", "100.64.1.1", "::1", "fe80::1", "fd12:3456::1", "/192.168.0.9")
            .forEach { assertTrue(it, PairingGuard.isLocalNetworkAddress(it)) }
        listOf(null, "", "8.8.8.8", "100.128.0.1", "2001:4860::8888", "example.com", "172.32.0.1")
            .forEach { assertFalse(it.toString(), PairingGuard.isLocalNetworkAddress(it)) }
    }

    @Test
    fun `the token pairs one phone and is then spent`() {
        val guard = PairingGuard()
        val token = guard.pairingToken!!
        assertTrue(guard.pairingUrl("192.168.1.2", 8095).endsWith("/?pair=$token"))

        val paired = guard.decide("192.168.1.20", "/", token, null)
        assertTrue(paired is PairingGuard.Decision.Paired)
        val cookie = (paired as PairingGuard.Decision.Paired).cookie.substringBefore(';')
        assertNull(guard.pairingToken)
        assertEquals(1, guard.pairedSessionCount)

        assertEquals(PairingGuard.Decision.Allow, guard.decide("192.168.1.20", "/api/remote", null, cookie))

        // A second phone scanning the same code is refused.
        val reused = guard.decide("192.168.1.21", "/", token, null)
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, (reused as PairingGuard.Decision.Deny).status)
    }

    @Test
    fun `requests without a session or with a wrong token are refused`() {
        val guard = PairingGuard()
        val none = guard.decide("192.168.1.20", "/", null, null)
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, (none as PairingGuard.Decision.Deny).status)

        val wrong = guard.decide("192.168.1.20", "/", "deadbeef", "fetch_pair=forged")
        assertTrue(wrong is PairingGuard.Decision.Deny)
        assertNotNull(guard.pairingToken)
    }

    @Test
    fun `devices outside the local network are refused even with the token`() {
        val guard = PairingGuard()
        val decision = guard.decide("203.0.113.9", "/", guard.pairingToken, null)
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, (decision as PairingGuard.Decision.Deny).status)
        assertNotNull(guard.pairingToken)
    }

    @Test
    fun `existing config servers enforce pairing`() {
        val guard = PairingGuard()
        val server = CustomPosterConfigServer(
            currentPatternProvider = { "" },
            onPatternChanged = {},
            pairingGuard = guard
        )
        val refused = server.serve(FakeHttpSession("/api/pattern"))
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, refused.status)

        val pair = server.serve(
            FakeHttpSession("/", parameters = mapOf(PairingGuard.PAIR_PARAM to listOf(guard.pairingToken!!)))
        )
        assertEquals(NanoHTTPD.Response.Status.REDIRECT_SEE_OTHER, pair.status)
        val cookie = pair.getHeader("Set-Cookie")!!.substringBefore(';')

        val allowed = server.serve(FakeHttpSession("/api/pattern", cookie = cookie))
        assertEquals(NanoHTTPD.Response.Status.OK, allowed.status)
    }

    @Test
    fun `cookie parsing finds the pairing cookie among others`() {
        assertEquals("abc", PairingGuard.parseCookie("theme=dark; fetch_pair=abc; x=1", PairingGuard.COOKIE_NAME))
        assertNull(PairingGuard.parseCookie("fetch_pair=", PairingGuard.COOKIE_NAME))
    }
}
