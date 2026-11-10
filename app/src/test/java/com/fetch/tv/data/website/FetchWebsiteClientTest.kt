package com.fetch.tv.data.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchWebsiteClientTest {
    @Test
    fun `a typed address gets a scheme and loses its trailing slash`() {
        assertEquals("http://192.168.1.25:7000", FetchWebsiteClient.normalizeServerUrl(" 192.168.1.25:7000/ "))
        assertEquals("https://fetch.example/api", FetchWebsiteClient.normalizeServerUrl("https://fetch.example/api/"))
    }

    @Test
    fun `a typed public address defaults to https`() {
        assertEquals("https://fetch.example", FetchWebsiteClient.normalizeServerUrl("fetch.example"))
        assertEquals("https://fetch.example:8443/api", FetchWebsiteClient.normalizeServerUrl("fetch.example:8443/api/"))
        assertEquals("https://8.8.8.8:7000", FetchWebsiteClient.normalizeServerUrl("8.8.8.8:7000"))
    }

    @Test
    fun `a typed local address defaults to http`() {
        assertEquals("http://10.0.0.2:7000", FetchWebsiteClient.normalizeServerUrl("10.0.0.2:7000"))
        assertEquals("http://172.20.1.1", FetchWebsiteClient.normalizeServerUrl("172.20.1.1"))
        assertEquals("http://127.0.0.1:7000", FetchWebsiteClient.normalizeServerUrl("127.0.0.1:7000"))
        assertEquals("http://nas:7000", FetchWebsiteClient.normalizeServerUrl("nas:7000"))
        assertEquals("http://fetch.local:7000", FetchWebsiteClient.normalizeServerUrl("fetch.local:7000"))
        assertEquals("http://server.lan", FetchWebsiteClient.normalizeServerUrl("server.lan/"))
    }

    @Test
    fun `an explicit scheme is kept`() {
        assertEquals("http://fetch.example", FetchWebsiteClient.normalizeServerUrl("http://fetch.example"))
        assertEquals("https://192.168.1.25:7000", FetchWebsiteClient.normalizeServerUrl("https://192.168.1.25:7000"))
    }

    @Test
    fun `local network hosts are recognised`() {
        listOf("192.168.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.1.1",
            "localhost", "nas", "tv.local", "box.lan", "::1", "fe80::1", "fd12::1")
            .forEach { assertTrue(it, FetchWebsiteClient.isLocalNetworkHost(it)) }
        listOf("172.32.0.1", "8.8.8.8", "fetch.example", "192.169.0.1", "2001:db8::1", "")
            .forEach { assertFalse(it, FetchWebsiteClient.isLocalNetworkHost(it)) }
    }

    @Test
    fun `the host is picked out of a scheme-less address`() {
        assertEquals("nas", FetchWebsiteClient.hostOf("user@nas:7000/api"))
        assertEquals("fe80::1", FetchWebsiteClient.hostOf("[fe80::1]:7000"))
        assertEquals("fetch.example", FetchWebsiteClient.hostOf("fetch.example?x=1"))
    }

    @Test
    fun `something that can't be an address is refused`() {
        assertNull(FetchWebsiteClient.normalizeServerUrl("   "))
        assertNull(FetchWebsiteClient.normalizeServerUrl("http://"))
    }

    @Test
    fun `endpoints join without doubled slashes`() {
        assertEquals("http://h:7000/devices/me", FetchWebsiteClient.endpoint("http://h:7000/", "devices/me"))
    }
}
