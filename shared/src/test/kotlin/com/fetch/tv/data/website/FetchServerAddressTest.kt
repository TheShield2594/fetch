package com.fetch.tv.data.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FetchServerAddressTest {
    private val apiHealth = """{"status":"ok","service":"api"}"""
    private val indexHtml = "<!doctype html><html><body>Fetch</body></html>"

    /** A fake network: exact URLs answer their body, everything else doesn't answer. */
    private fun network(vararg routes: Pair<String, String>): (String) -> String? {
        val map = routes.toMap()
        return { url -> map[url] }
    }

    @Test
    fun `the API address resolves to itself`() {
        val get = network("http://192.168.1.25:7000/health" to apiHealth)
        assertEquals(
            ResolvedFetchServer("http://192.168.1.25:7000", null),
            FetchServerAddress.resolve("192.168.1.25:7000", get)
        )
    }

    @Test
    fun `the website address finds the API it announces`() {
        val get = network(
            "http://192.168.1.25:7002/health" to indexHtml,
            "http://192.168.1.25:7002/fetch-server.json" to """{"service":"web","api":"http://192.168.1.25:7000"}""",
            "http://192.168.1.25:7000/health" to apiHealth
        )
        assertEquals(
            ResolvedFetchServer("http://192.168.1.25:7000", "http://192.168.1.25:7002"),
            FetchServerAddress.resolve("192.168.1.25:7002", get)
        )
    }

    @Test
    fun `an older website is read from config js, relative paths included`() {
        val get = network(
            "https://fetch.example.com/health" to indexHtml,
            "https://fetch.example.com/fetch-server.json" to indexHtml,
            "https://fetch.example.com/config.js" to "window.__FETCH_API_BASE__ = \"/api\";\nwindow.__FETCH_ADDON_BASE__ = \"\";",
            "https://fetch.example.com/api/health" to apiHealth
        )
        assertEquals(
            ResolvedFetchServer("https://fetch.example.com/api", "https://fetch.example.com"),
            FetchServerAddress.resolve("fetch.example.com", get)
        )
    }

    @Test
    fun `an unreachable announced API falls back to the same host on port 7000`() {
        // The compose file's example address, left unchanged.
        val get = network(
            "http://10.0.0.5:7002/fetch-server.json" to """{"api":"http://192.168.1.25:7000"}""",
            "http://10.0.0.5:7000/health" to apiHealth
        )
        assertEquals(
            ResolvedFetchServer("http://10.0.0.5:7000", "http://10.0.0.5:7002"),
            FetchServerAddress.resolve("http://10.0.0.5:7002/", get)
        )
    }

    @Test
    fun `a public name is never quietly tried over http`() {
        val get = network("http://home.example.net:7000/health" to apiHealth)
        assertNull(FetchServerAddress.resolve("home.example.net:7000", get))
        // Typed with its scheme, it is used as typed.
        assertEquals(
            ResolvedFetchServer("http://home.example.net:7000", null),
            FetchServerAddress.resolve("http://home.example.net:7000", get)
        )
    }

    @Test
    fun `an https website announcing an http api is not followed into plain http`() {
        val get = network(
            "https://fetch.example.com/fetch-server.json" to """{"api":"http://fetch.example.com:7000"}""",
            "http://fetch.example.com:7000/health" to apiHealth,
            "https://fetch.example.com/api/health" to apiHealth
        )
        // The announced http API is skipped; the https one under /api is used instead.
        assertEquals(
            ResolvedFetchServer("https://fetch.example.com/api", "https://fetch.example.com"),
            FetchServerAddress.resolve("fetch.example.com", get)
        )
        assertNull(FetchServerAddress.resolve("fetch.example.com", network(
            "https://fetch.example.com/fetch-server.json" to """{"api":"http://fetch.example.com:7000"}""",
            "http://fetch.example.com:7000/health" to apiHealth
        )))
    }

    @Test
    fun `nothing that answers as the API resolves to null`() {
        assertNull(FetchServerAddress.resolve("fetch.example.com", network("https://fetch.example.com/health" to indexHtml)))
        assertNull(FetchServerAddress.resolve("   ", network()))
    }

    @Test
    fun `normalize keeps the existing rules`() {
        assertEquals("http://192.168.1.25:7000", FetchServerAddress.normalize(" 192.168.1.25:7000/ "))
        assertEquals("https://fetch.example", FetchServerAddress.normalize("fetch.example"))
        assertEquals("http://nas:7000", FetchServerAddress.normalize("nas:7000"))
        assertNull(FetchServerAddress.normalize(""))
    }

    @Test
    fun `confirm url carries the code`() {
        assertEquals(
            "https://fetch.example.com/pair?code=ABCD-1234",
            FetchServerAddress.confirmUrl("https://fetch.example.com/", "ABCD-1234")
        )
    }
}
