package com.fetch.tv.data.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FetchWebsiteClientTest {
    @Test
    fun `a typed address gets a scheme and loses its trailing slash`() {
        assertEquals("http://192.168.1.25:7000", FetchWebsiteClient.normalizeServerUrl(" 192.168.1.25:7000/ "))
        assertEquals("https://fetch.example/api", FetchWebsiteClient.normalizeServerUrl("https://fetch.example/api/"))
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
