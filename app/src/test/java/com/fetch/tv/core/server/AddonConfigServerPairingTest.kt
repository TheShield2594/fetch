package com.fetch.tv.core.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The addon and collection page served to the phone: pairing comes first, and a change from
 * the phone is only ever a proposal the TV has to confirm.
 */
class AddonConfigServerPairingTest {
    private val context = mockk<Context>(relaxed = true) {
        every { getString(any()) } returns "Invalid request"
    }
    private val proposed = mutableListOf<PendingAddonChange>()
    private var stateReads = 0
    private val currentState = PageState(
        addons = listOf(AddonInfo(url = "https://primary.example", name = "Primary", description = null)),
        catalogs = listOf(
            CatalogInfo(
                key = "catalog_a",
                disableKey = "catalog_a",
                catalogName = "Featured",
                addonName = "Primary",
                type = "movie",
                isDisabled = false
            )
        )
    )

    private fun server(mode: AddonWebConfigMode = AddonWebConfigMode.FULL) = AddonConfigServer(
        context = context,
        webConfigMode = mode,
        currentPageStateProvider = { stateReads++; currentState },
        onChangeProposed = { proposed += it },
        pairingGuard = PairingGuard()
    )

    private fun AddonConfigServer.token(): String =
        pairingUrl("192.168.1.2").substringAfter("?${PairingGuard.PAIR_PARAM}=")

    private fun AddonConfigServer.pairedCookie(): String {
        val paired = serve(FakeHttpSession("/", parameters = mapOf(PairingGuard.PAIR_PARAM to listOf(token()))))
        assertEquals(NanoHTTPD.Response.Status.REDIRECT_SEE_OTHER, paired.status)
        assertEquals("/", paired.getHeader("Location"))
        return paired.getHeader("Set-Cookie")!!.substringBefore(';')
    }

    private fun body(response: NanoHTTPD.Response): String =
        response.data.use { String(it.readBytes(), StandardCharsets.UTF_8) }

    private fun postAddons(server: AddonConfigServer, json: String, cookie: String?) =
        server.serve(FakeHttpSession("/api/addons", NanoHTTPD.Method.POST, json, cookie = cookie))

    @Test
    fun `nothing is served or proposed before pairing`() {
        val server = server()
        listOf(
            FakeHttpSession("/"),
            FakeHttpSession("/api/state"),
            FakeHttpSession("/api/addons"),
            FakeHttpSession("/api/collections"),
            FakeHttpSession("/api/status/anything"),
            FakeHttpSession("/api/addons", NanoHTTPD.Method.POST, """{"urls":["https://evil.example"]}"""),
        ).forEach { session ->
            assertEquals(session.getUri(), NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(session).status)
        }
        assertEquals(0, stateReads)
        assertTrue(proposed.isEmpty())
    }

    @Test
    fun `devices outside the local network are refused`() {
        val server = server()
        val outside = server.serve(
            FakeHttpSession("/", remoteIp = "203.0.113.9", parameters = mapOf(PairingGuard.PAIR_PARAM to listOf(server.token())))
        )
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, outside.status)

        val cookie = server.pairedCookie()
        val fromOutside = server.serve(FakeHttpSession("/api/state", remoteIp = "8.8.8.8", cookie = cookie))
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, fromOutside.status)
        assertEquals(0, stateReads)
    }

    @Test
    fun `the paired phone reads the current state`() {
        val server = server()
        val cookie = server.pairedCookie()

        val state = server.serve(FakeHttpSession("/api/state", cookie = cookie))
        assertEquals(NanoHTTPD.Response.Status.OK, state.status)
        assertTrue(body(state).contains("https://primary.example"))

        // A cookie from another server's pairing does not work here.
        val otherCookie = server().pairedCookie()
        assertEquals(
            NanoHTTPD.Response.Status.UNAUTHORIZED,
            server.serve(FakeHttpSession("/api/state", cookie = otherCookie)).status
        )
        assertEquals(
            NanoHTTPD.Response.Status.NOT_FOUND,
            server.serve(FakeHttpSession("/api/nope", cookie = cookie)).status
        )
    }

    @Test
    fun `a change from the phone waits for the TV to confirm it`() {
        val server = server()
        val cookie = server.pairedCookie()

        val response = postAddons(
            server,
            """{"urls":[" https://primary.example ","https://new.example","https://new.example","",42],"catalogOrderKeys":["catalog_a"]}""",
            cookie
        )
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue(body(response).contains("\"status\":\"pending_confirmation\""))
        val change = proposed.single()
        // Links are trimmed, blanks, non-strings and repeats dropped.
        assertEquals(listOf("https://primary.example", "https://new.example"), change.proposedUrls)
        assertEquals(AddonChangeStatus.PENDING, change.status)

        fun status(id: String) = body(server.serve(FakeHttpSession("/api/status/$id", cookie = cookie)))
        assertTrue(status(change.id).contains("\"status\":\"pending\""))
        server.confirmChange(change.id)
        assertTrue(status(change.id).contains("\"status\":\"confirmed\""))
        assertTrue(status("unknown-id").contains("\"status\":\"not_found\""))
    }

    @Test
    fun `a newer proposal replaces one the TV never answered`() {
        val server = server()
        val cookie = server.pairedCookie()
        postAddons(server, """{"urls":["https://a.example"]}""", cookie)
        postAddons(server, """{"urls":["https://b.example"]}""", cookie)

        assertEquals(2, proposed.size)
        assertEquals(AddonChangeStatus.REJECTED, proposed[0].status)
        assertEquals(AddonChangeStatus.PENDING, proposed[1].status)

        server.rejectChange(proposed[1].id)
        assertEquals(AddonChangeStatus.REJECTED, proposed[1].status)
    }

    @Test
    fun `a body that is not a JSON object is refused`() {
        val server = server()
        val cookie = server.pairedCookie()
        listOf("not json", "[1,2]", "\"urls\"").forEach { json ->
            val response = postAddons(server, json, cookie)
            assertEquals(json, NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
            assertTrue(json, body(response).contains("Invalid request"))
        }
        assertTrue(proposed.isEmpty())
    }

    @Test
    fun `a collections-only page cannot change addons or catalogs`() {
        val server = server(AddonWebConfigMode.COLLECTIONS_ONLY)
        val cookie = server.pairedCookie()

        postAddons(
            server,
            """{"urls":["https://evil.example"],"catalogOrderKeys":["evil"],"disabledCatalogKeys":["catalog_a"],"collections":[]}""",
            cookie
        )

        val change = proposed.single()
        assertEquals(listOf("https://primary.example"), change.proposedUrls)
        assertEquals(listOf("catalog_a"), change.proposedCatalogOrderKeys)
        assertTrue(change.proposedDisabledCatalogKeys.isEmpty())
        assertFalse(change.proposedUrls.contains("https://evil.example"))
    }
}
