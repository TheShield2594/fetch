package com.fetch.desktop.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceTest {
    private val socket = File(Files.createTempDirectory("fs").toFile(), "fetch.sock")

    @Test
    fun `a second launch hands its links to the first`() {
        assertFalse("nothing running yet", SingleInstance.forward(listOf("fetch://movie/tt1"), socket))
        val got = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(2)
        assertTrue(SingleInstance.listen(onLink = { got += it; done.countDown() }, socket = socket))
        assertTrue(SingleInstance.forward(listOf("fetch://movie/tt1", "stremio://addon.example/manifest.json"), socket))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("fetch://movie/tt1", "stremio://addon.example/manifest.json"), got.toList())
    }

    @Test
    fun `a launch without links just brings the window forward`() {
        val pinged = CountDownLatch(1)
        SingleInstance.listen(onLink = {}, onPing = { pinged.countDown() }, socket = socket)
        assertTrue(SingleInstance.forward(emptyList(), socket))
        assertTrue(pinged.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a socket left by a crashed Fetch is replaced`() {
        socket.writeText("stale")
        assertFalse(SingleInstance.forward(listOf("fetch://movie/tt0111161"), socket))
        assertTrue(SingleInstance.listen(onLink = {}, socket = socket))
        assertTrue(SingleInstance.forward(listOf("fetch://movie/tt0111161"), socket))
    }

    @Test
    fun `the desktop entry takes a link and handles both schemes`() {
        val text = LinkRegistration.entry("/opt/fetch/bin/Fetch", "/opt/fetch/lib/Fetch.png")
        assertTrue("Exec=\"/opt/fetch/bin/Fetch\" %u" in text)
        assertTrue("MimeType=x-scheme-handler/fetch;x-scheme-handler/stremio;" in text)
        assertTrue("NoDisplay=true" in text)
    }

    @Test
    fun `only fetch and stremio links are taken`() {
        assertTrue(DeepLinks.isLink("fetch://movie/tt1"))
        assertTrue(DeepLinks.isLink("STREMIO://x.example/manifest.json"))
        assertFalse(DeepLinks.isLink("https://example.com"))
        assertFalse(DeepLinks.isLink("--some-flag"))
    }

    @Test
    fun `a socket path that is a link is not trusted`() {
        val real = File(socket.parentFile, "real.sock").apply { writeText("") }
        val link = File(socket.parentFile, "link.sock")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        assertTrue(SingleInstance.ownedByMe(real))
        assertFalse(SingleInstance.ownedByMe(link))
        assertFalse(SingleInstance.forward(listOf("fetch://movie/tt1"), link))
    }
}
