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
        /** Lines sent once connected (key bindings), each a JSON IPC command. */
        startup: List<String> = emptyList(),
        /** Asked whenever position or duration changes, once both are known; returns IPC commands to send. */
        onTimes: (positionSec: Double, durationSec: Double) -> List<String> = { _, _ -> emptyList() },
        /** A `script-message` sent to clients (a key bound in [startup]); returns IPC commands to send. */
        onClientMessage: (args: List<String>) -> List<String> = { emptyList() },
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
                    // Writes come from this thread and from onConnected's callers: one at a time.
                    fun send(lines: List<String>) {
                        if (lines.isEmpty() || !channel.isOpen) return
                        val text = lines.joinToString("") { if (it.endsWith("\n")) it else it + "\n" }
                        synchronized(channel) { runCatching { channel.write(ByteBuffer.wrap(text.toByteArray())) } }
                    }
                    send(listOf(commands) + startup)
                    onConnected { text -> send(listOf(showTextCommand(text, OSD_NOTE_MS))) }
                    val reader = Channels.newReader(channel, Charsets.UTF_8).buffered()
                    while (true) {
                        val line = reader.readLine() ?: break
                        val event = parse(line) ?: continue
                        val kind = event["event"]?.jsonPrimitive?.content
                        if (kind == "end-file" && event["reason"]?.jsonPrimitive?.content == "eof") reachedEnd = true
                        if (kind == "client-message") {
                            val args = (event["args"] as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
                            send(onClientMessage(args))
                            continue
                        }
                        if (kind != "property-change") continue
                        val value = event["data"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
                        when (event["name"]?.jsonPrimitive?.content) {
                            "time-pos" -> if (value != null) {
                                position = value
                                onPosition(value)?.let { seek ->
                                    send(listOf("{\"command\":[\"seek\",${seek.toSec},\"absolute\"]}", showTextCommand(seek.message, 2000)))
                                }
                            }
                            "duration" -> if (value != null) duration = value
                        }
                        val p = position
                        val d = duration
                        if (p != null && d != null && d > 0) send(onTimes(p, d))
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

    /** mpv's IPC `quit`: closes the window (Up Next moving on before the end). */
    const val QUIT_COMMAND = "{\"command\":[\"quit\"]}\n"

    /** Binds [key] in mpv's window to send `script-message [message]` back over IPC (mpv 0.37+; older ones ignore it). */
    fun keybindCommand(key: String, message: String): String =
        "{\"command\":[\"keybind\",${kotlinx.serialization.json.JsonPrimitive(key)},${kotlinx.serialization.json.JsonPrimitive("script-message $message")}]}\n"

    /** mpv's IPC `show-text` for [text], JSON-escaped, as one line. */
    fun showTextCommand(text: String, durationMs: Int): String =
        "{\"command\":[\"show-text\",${kotlinx.serialization.json.JsonPrimitive(text)},$durationMs]}\n"

    private fun parse(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
}
