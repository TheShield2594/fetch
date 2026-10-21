package com.fetch.tv.core.server

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonConfigureServerTest {

    private val text = AddonConfigureWebText(
        title = "Send to TV", step1 = "s1", openConfigure = "Open", step2 = "s2",
        placeholder = "p", send = "Send", sent = "Sent", invalid = "Invalid", failed = "Failed"
    )
    private val received = mutableListOf<String>()
    private val server = AddonConfigureServer(
        addonName = "Torrentio <b>",
        configureUrl = "https://torrentio.strem.fun/configure",
        text = text,
        onUrlReceived = { received += it }
    )

    @Test
    fun `page links to the configure page and escapes the addon name`() {
        val response = server.serve(FakeHttpSession("/"))
        val body = response.data.bufferedReader().readText()
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue(body.contains("href=\"https://torrentio.strem.fun/configure\""))
        assertTrue(body.contains("Torrentio &lt;b&gt;"))
        assertFalse(body.contains("Torrentio <b>"))
    }

    @Test
    fun `configured stremio link is normalized and handed to the tv`() {
        val response = server.serve(
            FakeHttpSession(
                "/api/configured",
                NanoHTTPD.Method.POST,
                """{"url":"stremio://torrentio.strem.fun/providers=yts/manifest.json"}"""
            )
        )
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(listOf("https://torrentio.strem.fun/providers=yts"), received)
    }

    @Test
    fun `non addon links are rejected`() {
        val response = server.serve(
            FakeHttpSession("/api/configured", NanoHTTPD.Method.POST, """{"url":"javascript:alert(1)"}""")
        )
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertTrue(received.isEmpty())
    }
}
