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

    /** How playback ended: the last position, and whether mpv reached the end of the file. */
    data class Ending(val last: Sample?, val reachedEnd: Boolean)

    /** A jump for mpv to make, and the note it shows on screen ("Skipped intro"). */
    data class Seek(val toSec: Double, val message: String)

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
    ): Sample? = followToEnd(socket, process, intervalMs, onSample = onSample).last

    /** Like [follow], and also tells whether the file played to its end (for Up Next). */
    suspend fun followToEnd(
        socket: File,
        process: Process,
        intervalMs: Long = 5_000,
        /** Asked on every position change; a [Seek] makes mpv jump (Skip Segments). */
        onPosition: (Double) -> Seek? = { null },
        /** Called once the socket is open with a way to show text on mpv's OSD (YouTube's vote counts). */
        onConnected: (showText: (String) -> Unit) -> Unit = {},
        onSample: (Sample) -> Unit
    ): Ending {
        // mpv creates the socket shortly after it starts.
        var waited = 0L
        while (!socket.exists()) {
            if (!process.isAlive || waited > 20_000) return Ending(null, reachedEnd = false)
            delay(200)
            waited += 200
        }
        var position: Double? = null
        var duration: Double? = null
        var lastEmit = 0L
        var reachedEnd = false
        try {
            runInterruptible {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(socket.toPath()))
                    val commands = """{"command":["observe_property",1,"time-pos"]}""" + "\n" +
                        """{"command":["observe_property",2,"duration"]}""" + "\n"
                    channel.write(ByteBuffer.wrap(commands.toByteArray()))
                    onConnected { text ->
                        if (channel.isOpen) runCatching { channel.write(ByteBuffer.wrap(showTextCommand(text, OSD_NOTE_MS).toByteArray())) }
                    }
                    val reader = Channels.newReader(channel, Charsets.UTF_8).buffered()
                    while (true) {
                        val line = reader.readLine() ?: break
                        val event = parse(line) ?: continue
                        val kind = event["event"]?.jsonPrimitive?.content
                        if (kind == "end-file" && event["reason"]?.jsonPrimitive?.content == "eof") reachedEnd = true
                        if (kind != "property-change") continue
                        val value = event["data"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
                        when (event["name"]?.jsonPrimitive?.content) {
                            "time-pos" -> if (value != null) {
                                position = value
                                onPosition(value)?.let { seek ->
                                    val text = seek.message.replace("\\", "").replace("\"", "")
                                    val commands = "{\"command\":[\"seek\",${seek.toSec},\"absolute\"]}\n" +
                                        "{\"command\":[\"show-text\",\"$text\",2000]}\n"
                                    channel.write(ByteBuffer.wrap(commands.toByteArray()))
                                }
                            }
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
        val last = if (p != null && d != null && d > 0) Sample(p, d).also(onSample) else null
        return Ending(last, reachedEnd)
    }

    /** How long an [followToEnd] OSD note stays up. */
    const val OSD_NOTE_MS = 6_000

    /** mpv's IPC `show-text` for [text], JSON-escaped, as one line. */
    fun showTextCommand(text: String, durationMs: Int): String =
        "{\"command\":[\"show-text\",${kotlinx.serialization.json.JsonPrimitive(text)},$durationMs]}\n"

    private fun parse(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
}
