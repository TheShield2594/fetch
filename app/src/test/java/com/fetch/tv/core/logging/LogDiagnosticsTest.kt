package com.fetch.tv.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDiagnosticsTest {

    @Test
    fun `rawForLog never returns the raw value`() {
        val code = "ABCD-1234"
        val logged = code.rawForLog()

        assertFalse(logged.contains(code))
        assertTrue(logged.startsWith("len=9#"))
        assertEquals("len=9#".length + 8, logged.length)
    }

    @Test
    fun `rawForLog is stable for the same value and differs for different values`() {
        assertEquals("nonce-1".rawForLog(), "nonce-1".rawForLog())
        assertNotEquals("nonce-1".rawForLog(), "nonce-2".rawForLog())
    }

    @Test
    fun `rawForLog marks null and empty values`() {
        assertEquals("(null)", (null as String?).rawForLog())
        assertEquals("(empty)", "".rawForLog())
    }

    @Test
    fun `urlForLog strips query and fragment`() {
        assertEquals(
            "https://fetch.example/tv?…",
            "https://fetch.example/tv?code=ABCD&nonce=secret".urlForLog()
        )
        assertEquals("https://fetch.example/tv?…", "https://fetch.example/tv#code=ABCD".urlForLog())
    }

    @Test
    fun `urlForLog keeps urls without query unchanged`() {
        assertEquals("https://fetch.example/rest/v1/rpc", "https://fetch.example/rest/v1/rpc".urlForLog())
        assertEquals("(null)", (null as String?).urlForLog())
    }

    @Test
    fun `hostOnlyUrlForLog keeps only scheme and host`() {
        val url = "https://torrentio.example/realdebrid=SECRETKEY/manifest.json?token=abc"
        val logged = url.hostOnlyUrlForLog()
        assertTrue(logged, logged.startsWith("https://torrentio.example/…[len="))
        assertFalse(logged.contains("SECRETKEY"))
        assertFalse(logged.contains("abc"))
        assertEquals(logged, url.hostOnlyUrlForLog())

        val withUserInfo = "http://user:pass@192.168.1.2:7000/addon".hostOnlyUrlForLog()
        assertTrue(withUserInfo, withUserInfo.startsWith("http://192.168.1.2:7000/…"))
        assertFalse(withUserInfo.contains("pass"))

        assertTrue("SECRET/path".hostOnlyUrlForLog().startsWith("(no host)/…"))
        assertEquals("(null)", (null as String?).hostOnlyUrlForLog())
        assertEquals("(empty)", "".hostOnlyUrlForLog())
    }
}
