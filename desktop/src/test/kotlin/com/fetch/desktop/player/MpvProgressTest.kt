package com.fetch.desktop.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.concurrent.thread

class MpvProgressTest {

    @Test
    fun `reads mpv's position and duration and keeps the last position when mpv quits`() = runBlocking {
        val socket = Files.createTempDirectory("mpv-test").resolve("mpv.sock").toFile()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket.toPath())) }
        val received = StringBuilder()
        // Plays mpv's side: read the observe commands, push property changes, then quit.
        val fakeMpv = thread {
            server.accept().use { client ->
                val buffer = ByteBuffer.allocate(1024)
                client.read(buffer)
                received.append(String(buffer.array(), 0, buffer.position()))
                val events = listOf(
                    """{"event":"property-change","id":2,"name":"duration","data":3000.0}""",
                    """{"event":"property-change","id":1,"name":"time-pos","data":1200.5}""",
                    """{"request_id":0,"error":"success"}""",
                    // mpv clears time-pos as it shuts down.
                    """{"event":"property-change","id":1,"name":"time-pos"}"""
                ).joinToString("\n", postfix = "\n")
                client.write(ByteBuffer.wrap(events.toByteArray()))
            }
            server.close()
        }
        val process = ProcessBuilder("sleep", "10").start()
        try {
            val samples = mutableListOf<MpvProgress.Sample>()
            val last = withContext(Dispatchers.IO) {
                MpvProgress.follow(socket, process, intervalMs = 0) { samples += it }
            }
            fakeMpv.join()
            assertEquals(MpvProgress.Sample(1200.5, 3000.0), last)
            assertTrue(samples.isNotEmpty())
            assertTrue("observes time-pos", "\"time-pos\"" in received)
            assertTrue("observes duration", "\"duration\"" in received)
            assertFalse("cleans up the socket", socket.exists())
        } finally {
            process.destroy()
        }
    }

    @Test
    fun `gives up when mpv exits before opening its socket`() = runBlocking {
        val socket = Files.createTempDirectory("mpv-test").resolve("never.sock").toFile()
        val process = ProcessBuilder("true").start().also { it.waitFor() }
        assertEquals(null, MpvProgress.follow(socket, process) {})
    }
}
