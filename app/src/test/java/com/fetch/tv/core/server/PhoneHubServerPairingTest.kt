package com.fetch.tv.core.server

import com.fetch.tv.core.remote.RemoteCommand
import com.fetch.tv.core.remote.RemoteKey
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone hub behind its [PairingGuard]: nothing is served, and no section page is reached,
 * until a phone on the local network has paired with the one-time code from the TV.
 */
class PhoneHubServerPairingTest {
    private val actions = mockk<PhoneHubActions>(relaxed = true) {
        every { sendRemote(any()) } returns true
        every { settingsChangeBlocked() } returns null
    }
    private val savedPatterns = mutableListOf<String>()
    private var activity = 0
    private val server = PhoneHubServer(
        actions = actions,
        // Every request below is answered before any page text is needed.
        resources = { throw AssertionError("no page text expected") },
        sections = mapOf(
            PhoneHubServer.SECTION_POSTERS to CustomPosterConfigServer(
                currentPatternProvider = { "https://img/{imdb}.jpg" },
                onPatternChanged = { savedPatterns += it }
            )
        ),
        pairingGuard = PairingGuard(),
        onActivity = { activity++ }
    )

    /** The code the TV shows in its QR link. */
    private val token: String = server.pairingUrl("192.168.1.2").substringAfter("?${PairingGuard.PAIR_PARAM}=")

    private fun remoteKey(cookie: String?, ip: String = "192.168.1.20") = server.serve(
        FakeHttpSession("/api/remote", NanoHTTPD.Method.POST, """{"type":"key","key":"ok"}""", remoteIp = ip, cookie = cookie)
    )

    private fun pair(uri: String = "/", ip: String = "192.168.1.20"): NanoHTTPD.Response = server.serve(
        FakeHttpSession(uri, remoteIp = ip, parameters = mapOf(PairingGuard.PAIR_PARAM to listOf(token)))
    )

    private fun cookieOf(response: NanoHTTPD.Response): String =
        response.getHeader("Set-Cookie")!!.substringBefore(';')

    @Test
    fun `the QR link carries a pairing code`() {
        assertEquals(32, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `nothing is served before pairing and refused requests are not activity`() {
        listOf(
            FakeHttpSession("/"),
            FakeHttpSession("/remote"),
            FakeHttpSession("/api/accounts"),
            FakeHttpSession("/api/backup"),
            FakeHttpSession("/api/remote", NanoHTTPD.Method.POST, """{"type":"key","key":"ok"}"""),
            FakeHttpSession("/api/accounts/mdblist", NanoHTTPD.Method.POST, """{"apiKey":"k"}"""),
            FakeHttpSession("/api/profiles/import", NanoHTTPD.Method.POST, "{}"),
            FakeHttpSession("/posters/api/pattern"),
            FakeHttpSession("/nope"),
        ).forEach { session ->
            assertEquals(session.getUri(), NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(session).status)
        }
        assertEquals(0, activity)
        assertEquals(0, server.pairedSessionCount)
        verify(exactly = 0) { actions.sendRemote(any()) }
    }

    @Test
    fun `a section page cannot be changed without pairing`() {
        val refused = server.serve(
            FakeHttpSession("/posters/api/pattern", NanoHTTPD.Method.POST, """{"pattern":"https://evil/{imdb}.jpg"}""")
        )
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, refused.status)
        assertEquals(
            NanoHTTPD.Response.Status.UNAUTHORIZED,
            server.serve(FakeHttpSession("/posters/api/clear", NanoHTTPD.Method.POST)).status
        )
        assertTrue(savedPatterns.isEmpty())
    }

    @Test
    fun `pairing sets a locked-down cookie and sends the phone on without the code`() {
        val paired = pair("/remote")

        assertEquals(NanoHTTPD.Response.Status.REDIRECT_SEE_OTHER, paired.status)
        assertEquals("/remote", paired.getHeader("Location"))
        assertEquals("no-store", paired.getHeader("Cache-Control"))
        val setCookie = paired.getHeader("Set-Cookie")!!
        assertTrue(setCookie, setCookie.startsWith("${PairingGuard.COOKIE_NAME}="))
        assertTrue(setCookie, setCookie.contains("HttpOnly"))
        assertTrue(setCookie, setCookie.contains("SameSite=Strict"))
        assertTrue(setCookie, setCookie.contains("Path=/"))
        // The session cookie is a fresh secret, not the code from the QR link.
        assertFalse(setCookie.contains(token))
        assertEquals(1, server.pairedSessionCount)
        // The QR link no longer carries a usable code.
        assertFalse(server.pairingUrl("192.168.1.2").contains("${PairingGuard.PAIR_PARAM}="))
    }

    @Test
    fun `the paired phone reaches the TV and section pages`() {
        val cookie = cookieOf(pair())

        assertEquals(NanoHTTPD.Response.Status.OK, remoteKey(cookie).status)
        verify(exactly = 1) { actions.sendRemote(RemoteCommand.Key(RemoteKey.OK)) }

        // Other cookies on the same request do not get in the way.
        assertEquals(NanoHTTPD.Response.Status.OK, remoteKey("theme=dark; $cookie; x=1").status)

        val saved = server.serve(
            FakeHttpSession("/posters/api/pattern", NanoHTTPD.Method.POST, """{"pattern":"https://img/{imdb}.png"}""", cookie = cookie)
        )
        assertEquals(NanoHTTPD.Response.Status.OK, saved.status)
        assertEquals(listOf("https://img/{imdb}.png"), savedPatterns)
        assertTrue(activity > 0)
    }

    @Test
    fun `the code pairs one phone only`() {
        cookieOf(pair())

        val second = pair(ip = "192.168.1.21")
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, second.status)
        assertNull(second.getHeader("Set-Cookie"))
        assertEquals(1, server.pairedSessionCount)
    }

    @Test
    fun `wrong codes and forged cookies are refused`() {
        val wrongCode = server.serve(
            FakeHttpSession("/", parameters = mapOf(PairingGuard.PAIR_PARAM to listOf(token.reversed())))
        )
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, wrongCode.status)
        assertNull(wrongCode.getHeader("Set-Cookie"))

        listOf(
            "${PairingGuard.COOKIE_NAME}=forged",
            "${PairingGuard.COOKIE_NAME}=$token",
            "${PairingGuard.COOKIE_NAME}=",
            "other_${PairingGuard.COOKIE_NAME}=x",
        ).forEach { cookie ->
            assertEquals(cookie, NanoHTTPD.Response.Status.UNAUTHORIZED, remoteKey(cookie).status)
        }
        verify(exactly = 0) { actions.sendRemote(any()) }

        // None of that spent the code.
        assertEquals(NanoHTTPD.Response.Status.REDIRECT_SEE_OTHER, pair().status)
    }

    @Test
    fun `devices outside the local network are refused, even with the code or a valid cookie`() {
        val outside = pair(ip = "203.0.113.9")
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, outside.status)
        assertEquals(0, server.pairedSessionCount)

        val cookie = cookieOf(pair())
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, remoteKey(cookie, ip = "8.8.8.8").status)
        verify(exactly = 0) { actions.sendRemote(any()) }
    }

    @Test
    fun `a link to a hub page keeps the pairing code and stays on the TV`() {
        val state = PhoneHubState(url = "http://192.168.1.2:8095/?pair=abc123")

        listOf(
            "remote" to "http://192.168.1.2:8095/remote?pair=abc123",
            "/remote" to "http://192.168.1.2:8095/remote?pair=abc123",
            "addons" to "http://192.168.1.2:8095/addons?pair=abc123",
            "" to "http://192.168.1.2:8095/?pair=abc123",
            // A path can never turn into another host (`//host` would be protocol-relative).
            "//evil.example/x" to "http://192.168.1.2:8095/evil.example/x?pair=abc123",
        ).forEach { (path, expected) ->
            assertEquals(path, expected, state.urlFor(path))
        }

        assertEquals("http://192.168.1.2:8095/remote", PhoneHubState(url = "http://192.168.1.2:8095").urlFor("remote"))
        assertEquals("http://192.168.1.2:8095/remote", PhoneHubState(url = "http://192.168.1.2:8095/").urlFor("/remote"))
        assertNull(PhoneHubState().urlFor("remote"))
    }
}
