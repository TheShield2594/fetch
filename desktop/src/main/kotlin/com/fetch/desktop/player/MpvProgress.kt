package com.fetch.desktop.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/**
 * Follows an mpv window's playback position through its JSON IPC socket
 * (`--input-ipc-server`). mpv pushes `time-pos` and `duration` changes once observed; the socket
 * closes when mpv quits, which ends [follow].
 */
object MpvProgress {

    /** Position and duration in seconds. */
    data class Sample(val positionSec: Double, val durationSec: Double)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Connects to [socket] once mpv creates it, then calls [onSample] at most every
     * [intervalMs] and once more when playback ends. Returns the last sample, or null when mpv
     * never reported one. Runs blocking reads, so call it on Dispatchers.IO.
     */
    suspend fun follow(
        socket: File,
        process: Process,
        intervalMs: Long = 5_000,
        onSample: (Sample) -> Unit
    ): Sample? {
        // mpv creates the socket shortly after it starts.
        var waited = 0L
        while (!socket.exists()) {
            if (!process.isAlive || waited > 20_000) return null
            delay(200)
            waited += 200
        }
        var position: Double? = null
        var duration: Double? = null
        var lastEmit = 0L
        try {
            runInterruptible {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(socket.toPath()))
                    val commands = """{"command":["observe_property",1,"time-pos"]}""" + "\n" +
                        """{"command":["observe_property",2,"duration"]}""" + "\n"
                    channel.write(ByteBuffer.wrap(commands.toByteArray()))
                    val reader = Channels.newReader(channel, Charsets.UTF_8).buffered()
                    while (true) {
                        val line = reader.readLine() ?: break
                        val event = parse(line) ?: continue
                        if (event["event"]?.jsonPrimitive?.content != "property-change") continue
                        val value = event["data"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
                        when (event["name"]?.jsonPrimitive?.content) {
                            "time-pos" -> if (value != null) position = value
                            "duration" -> if (value != null) duration = value
                        }
                        val p = position
                        val d = duration
                        val now = System.currentTimeMillis()
                        if (p != null && d != null && d > 0 && now - lastEmit >= intervalMs) {
                            lastEmit = now
                            onSample(Sample(p, d))
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // mpv quit or the socket went away: keep whatever was read.
        } finally {
            socket.delete()
        }
        val p = position
        val d = duration
        return if (p != null && d != null && d > 0) Sample(p, d).also(onSample) else null
    }

    private fun parse(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
}
