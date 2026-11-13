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

    @Test
    fun `hostOnlyUrlForLog needs a leading scheme and stops the host at a backslash`() {
        val nested = "torrentio.example/realdebrid=SECRETKEY/manifest.json?next=https://example.org"
        val logged = nested.hostOnlyUrlForLog()
        assertTrue(logged, logged.startsWith("(no host)/…"))
        assertFalse(logged.contains("SECRETKEY"))

        val backslash = "https://example.com\\provider-key/sub.srt".hostOnlyUrlForLog()
        assertTrue(backslash, backslash.startsWith("https://example.com/…"))
        assertFalse(backslash.contains("provider-key"))
    }

    @Test
    fun `redactUrlsForLog removes known and embedded urls from messages`() {
        val manifest = "torrentio.example/realdebrid=SECRETKEY/manifest.json"
        val malformed = "Malformed URL. Base: https://base.example/, Relative: $manifest".redactUrlsForLog(manifest)!!
        assertFalse(malformed, malformed.contains("SECRETKEY"))
        assertTrue(malformed, malformed.startsWith("Malformed URL. Base: https://base.example/…[len="))

        val timeout = "Request to https://addon.example/KEY123/manifest.json timed out".redactUrlsForLog()!!
        assertFalse(timeout, timeout.contains("KEY123"))
        assertTrue(timeout, timeout.contains("https://addon.example/…[len=") && timeout.endsWith(" timed out"))

        // Input that only looks redacted is not trusted.
        val spoofed = "Request to https://addon.example/…[len=KEY123/manifest.json timed out".redactUrlsForLog()!!
        assertFalse(spoofed, spoofed.contains("KEY123"))

        // A known URL that is also embedded in a longer scheme:// token is still reduced.
        val nested = "GET https://cdn.example/a?u=SECRET failed".redactUrlsForLog("https://cdn.example/a?u=SECRET")!!
        assertFalse(nested, nested.contains("SECRET"))

        // A suffix after a known URL belongs to the same token and is redacted with it.
        val known = "https://addon.example/meta/movie/item.json"
        val suffixed = "GET $known?token=SECRET failed".redactUrlsForLog(known)!!
        assertFalse(suffixed, suffixed.contains("SECRET"))
        assertTrue(suffixed, suffixed.startsWith("GET https://addon.example/…[len=") && suffixed.endsWith(" failed"))

        // A scheme-less known URL with a suffix is reduced to its fingerprint.
        val relative = "Relative: torrentio.example/KEY/manifest.json?x=1, done".redactUrlsForLog("torrentio.example/KEY/manifest.json")!!
        assertFalse(relative, relative.contains("KEY"))
        assertTrue(relative, relative.endsWith(" done"))

        // The meta timeout names an uninstalled addon by its (possibly scheme-less) base URL, not
        // the meta URL; callers pass both.
        val base = "addon.example/KEY123"
        val timedOut = "$base didn't answer in time".redactUrlsForLog("$base/meta/movie/id.json", base)!!
        assertFalse(timedOut, timedOut.contains("KEY123"))
        assertTrue(timedOut, timedOut.endsWith(" didn't answer in time"))

        assertEquals("plain failure", "plain failure".redactUrlsForLog("https://a.example/x"))
        assertEquals(null, (null as String?).redactUrlsForLog())
    }
}
