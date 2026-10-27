package com.fetch.tv.data.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FetchServerDiscoveryTest {
    private fun txt(vararg entries: Pair<String, String>): Map<String, ByteArray?> =
        entries.associate { (key, value) -> key to value.toByteArray() }

    @Test
    fun `announced API URL comes first, then the address it answered from`() {
        val candidates = FetchServerDiscovery.discoveredServerCandidates(
            serviceName = "Fetch (nas)",
            attributes = txt("v" to "1", "api" to "https://fetch.example.com/api/", "web" to "https://fetch.example.com"),
            host = "192.168.1.25",
            port = 7000
        )
        assertEquals("Fetch (nas)", candidates.name)
        assertEquals(listOf("https://fetch.example.com/api", "http://192.168.1.25:7000"), candidates.apiUrls)
        assertEquals("https://fetch.example.com", candidates.webUrl)
    }

    @Test
    fun `the same address is offered once`() {
        val candidates = FetchServerDiscovery.discoveredServerCandidates(
            serviceName = "Fetch",
            attributes = txt("api" to "http://192.168.1.25:7000"),
            host = "192.168.1.25",
            port = 7000
        )
        assertEquals(listOf("http://192.168.1.25:7000"), candidates.apiUrls)
    }

    @Test
    fun `no TXT record still gives the address it answered from`() {
        val candidates = FetchServerDiscovery.discoveredServerCandidates(
            serviceName = "",
            attributes = emptyMap(),
            host = "[fe80::1]",
            port = 7000
        )
        assertEquals("Fetch", candidates.name)
        assertEquals(listOf("http://[fe80::1]:7000"), candidates.apiUrls)
        assertNull(candidates.webUrl)
    }

    @Test
    fun `TXT values that are not web addresses are ignored`() {
        val candidates = FetchServerDiscovery.discoveredServerCandidates(
            serviceName = "Fetch",
            attributes = txt("api" to "javascript:alert(1)", "web" to "ftp://nas"),
            host = null,
            port = 0
        )
        assertEquals(emptyList<String>(), candidates.apiUrls)
        assertNull(candidates.webUrl)
    }
}
