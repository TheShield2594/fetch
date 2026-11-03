package com.fetch.desktop.player.autosync

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Subtitle timings read from the video file itself, over [spans] of it. */
data class FileReference(val cues: List<Cue>, val spans: List<Span>, val source: String)

/** Bytes of the playing file by position: an HTTP link (range requests) or a local file. */
interface RangeSource {
    fun read(offset: Long, length: Int): ByteArray?
}

class HttpRangeSource(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
) : RangeSource {
    override fun read(offset: Long, length: Int): ByteArray? = runCatching {
        val request = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=$offset-${offset + length - 1}")
        }.build()
        client.newCall(request).execute().use { response ->
            // A server that ignores Range sends the whole file from 0: only usable for offset 0.
            if (response.code != 206 && !(response.code == 200 && offset == 0L)) return@use null
            response.body?.byteStream()?.use { it.readNBytes(length) }
        }
    }.getOrNull()
}

class FileRangeSource(private val file: File) : RangeSource {
    override fun read(offset: Long, length: Int): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (offset >= raf.length()) return@use null
            raf.seek(offset)
            val out = ByteArray(minOf(length.toLong(), raf.length() - offset).toInt())
            raf.readFully(out)
            out
        }
    }.getOrNull()
}

/**
 * An image subtitle track's timings (PGS, VobSub) from a Matroska file's index, as the TV's
 * EmbeddedSubtitleTimelineLoader reads them: mkvmerge indexes every subtitle frame in the Cues,
 * so the whole film's timings come from a few small range reads, with no bitmaps decoded. PGS
 * indexes both a display set and the one that clears it; those pair into one line.
 */
object MatroskaCueIndex {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val INFO = 0x1549A966L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_TYPE = 0x83L
    private const val CODEC_ID = 0x86L
    private const val CUES = 0x1C53BB6BL
    private const val CUE_POINT = 0xBBL
    private const val CUE_TIME = 0xB3L
    private const val CUE_TRACK_POSITIONS = 0xB7L
    private const val CUE_TRACK = 0xF7L
    private const val CUE_DURATION = 0xB2L
    private const val CLUSTER = 0x1F43B675L
    private const val SUBTITLE_TYPE = 0x11L
    private const val HEAD_BYTES = 256 * 1024
    private const val MAX_ELEMENT_BYTES = 32 * 1024 * 1024
    const val MIN_CUES = 20

    data class Track(val number: Long, val type: Long, val codecId: String)

    /** Reads an element's ID or size at [pos]: (value, length in bytes); sizes have the marker bit removed. */
    internal fun vint(buf: ByteArray, pos: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (pos >= buf.size) return null
        val first = buf[pos].toInt() and 0xFF
        if (first == 0) return null
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (length !in 1..8 || pos + length > buf.size) return null
        var value = (if (keepMarker) first else first and (0xFF shr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (buf[pos + i].toLong() and 0xFF)
        return value to length
    }

    internal data class Element(val id: Long, val start: Int, val dataStart: Int, val size: Long) {
        val end: Long get() = dataStart + size
    }

    internal fun element(buf: ByteArray, pos: Int): Element? {
        val (id, idLength) = vint(buf, pos, keepMarker = true) ?: return null
        val (size, sizeLength) = vint(buf, pos + idLength, keepMarker = false) ?: return null
        val unknown = size == (1L shl (7 * sizeLength)) - 1
        return Element(id, pos, pos + idLength + sizeLength, if (unknown) Long.MAX_VALUE / 4 else size)
    }

    /** The children of [buf][from, to). */
    internal fun children(buf: ByteArray, from: Int, to: Int): List<Element> {
        val out = mutableListOf<Element>()
        var pos = from
        while (pos < to) {
            val e = element(buf, pos) ?: break
            out += e
            if (e.end > to || e.size >= Long.MAX_VALUE / 8) break
            pos = e.end.toInt()
        }
        return out
    }

    internal fun uint(buf: ByteArray, e: Element): Long {
        var v = 0L
        for (i in 0 until e.size.toInt().coerceAtMost(8)) v = (v shl 8) or (buf[e.dataStart + i].toLong() and 0xFF)
        return v
    }

    /**
     * The timings of the subtitle track numbered [trackNumber] (or, without one, the
     * [subtitleOrdinal]-th subtitle track), or null when the file isn't Matroska or its index
     * doesn't list that track's frames.
     */
    fun read(source: RangeSource, trackNumber: Long?, subtitleOrdinal: Int, durationMs: Long?): FileReference? {
        val head = source.read(0, HEAD_BYTES) ?: return null
        val ebml = element(head, 0)?.takeIf { it.id == EBML } ?: return null
        val segment = element(head, ebml.end.toInt())?.takeIf { it.id == SEGMENT } ?: return null
        val segmentStart = segment.dataStart.toLong()

        // Where Info, Tracks and Cues are: the SeekHead, else wherever they sit in the head.
        val positions = mutableMapOf<Long, Long>()
        val top = children(head, segment.dataStart, head.size)
        top.forEach { e -> if (e.id == INFO || e.id == TRACKS || e.id == CUES) positions.putIfAbsent(e.id, e.start - segmentStart) }
        top.firstOrNull { it.id == SEEK_HEAD }?.let { readSeekHead(head, it, positions) }
        // A second SeekHead (at the end) may list the Cues.
        positions[SEEK_HEAD]?.takeIf { CUES !in positions }?.let { pos ->
            readElement(source, segmentStart + pos)?.let { (buf, e) -> if (e.id == SEEK_HEAD) readSeekHead(buf, e, positions) }
        }

        val scaleNs = positions[INFO]?.let { readElement(source, segmentStart + it) }?.let { (buf, e) ->
            children(buf, e.dataStart, minOf(e.end, buf.size.toLong()).toInt()).firstOrNull { it.id == TIMESTAMP_SCALE }?.let { uint(buf, it) }
        } ?: 1_000_000L
        val tracks = positions[TRACKS]?.let { readElement(source, segmentStart + it) }?.let { (buf, e) -> parseTracks(buf, e) } ?: return null
        val subtitles = tracks.filter { it.type == SUBTITLE_TYPE }
        val track = trackNumber?.let { n -> subtitles.firstOrNull { it.number == n } } ?: subtitles.getOrNull(subtitleOrdinal) ?: return null
        val (cuesBuf, cuesElement) = positions[CUES]?.let { readElement(source, segmentStart + it) } ?: return null
        if (cuesElement.id != CUES) return null
        val points = parseCuePoints(cuesBuf, cuesElement, track.number).map { (t, d) -> t * scaleNs / 1_000_000 to d?.let { it * scaleNs / 1_000_000 } }
        val cues = timeline(points, pgs = track.codecId.equals("S_HDMV/PGS", ignoreCase = true))
        if (cues.size < MIN_CUES) return null
        val end = maxOf(durationMs ?: 0L, cues.last().endMs)
        return FileReference(cues, listOf(Span(0, end)), "index")
    }

    private fun readSeekHead(buf: ByteArray, seekHead: Element, into: MutableMap<Long, Long>) {
        children(buf, seekHead.dataStart, minOf(seekHead.end, buf.size.toLong()).toInt()).filter { it.id == SEEK }.forEach { seek ->
            val parts = children(buf, seek.dataStart, seek.end.toInt())
            val idElement = parts.firstOrNull { it.id == SEEK_ID } ?: return@forEach
            val pos = parts.firstOrNull { it.id == SEEK_POSITION }?.let { uint(buf, it) } ?: return@forEach
            val id = uint(buf, idElement)
            into[id] = pos
        }
    }

    /** The element at [offset] with its data, up to [MAX_ELEMENT_BYTES]. */
    private fun readElement(source: RangeSource, offset: Long): Pair<ByteArray, Element>? {
        val header = source.read(offset, 16) ?: return null
        val e = element(header, 0) ?: return null
        if (e.id == CLUSTER || e.size > MAX_ELEMENT_BYTES) return null
        val buf = source.read(offset, (e.dataStart + e.size).toInt()) ?: return null
        if (buf.size < e.dataStart + e.size) return null
        return buf to e
    }

    internal fun parseTracks(buf: ByteArray, tracks: Element): List<Track> =
        children(buf, tracks.dataStart, tracks.end.toInt()).filter { it.id == TRACK_ENTRY }.map { entry ->
            val fields = children(buf, entry.dataStart, entry.end.toInt())
            Track(
                number = fields.firstOrNull { it.id == TRACK_NUMBER }?.let { uint(buf, it) } ?: 0,
                type = fields.firstOrNull { it.id == TRACK_TYPE }?.let { uint(buf, it) } ?: 0,
                codecId = fields.firstOrNull { it.id == CODEC_ID }?.let { String(buf, it.dataStart, it.size.toInt(), Charsets.US_ASCII) }.orEmpty()
            )
        }

    /** Each indexed frame of [trackNumber]: its time and duration (when the index has one), in timestamp units. */
    internal fun parseCuePoints(buf: ByteArray, cues: Element, trackNumber: Long): List<Pair<Long, Long?>> =
        children(buf, cues.dataStart, cues.end.toInt()).filter { it.id == CUE_POINT }.flatMap { point ->
            val fields = children(buf, point.dataStart, point.end.toInt())
            val time = fields.firstOrNull { it.id == CUE_TIME }?.let { uint(buf, it) } ?: return@flatMap emptyList()
            fields.filter { it.id == CUE_TRACK_POSITIONS }.mapNotNull { positions ->
                val p = children(buf, positions.dataStart, positions.end.toInt())
                if (p.firstOrNull { it.id == CUE_TRACK }?.let { uint(buf, it) } != trackNumber) return@mapNotNull null
                time to p.firstOrNull { it.id == CUE_DURATION }?.let { uint(buf, it) }?.takeIf { it > 0 }
            }
        }

    /**
     * Indexed frames (ms, with durations when known) as lines. PGS without durations alternates a
     * display set and the one that clears it, so frames pair; otherwise a frame lasts its
     * duration, or until the next one (at most [MAX_GUESSED_MS]).
     */
    fun timeline(points: List<Pair<Long, Long?>>, pgs: Boolean): List<Cue> {
        val sorted = points.sortedBy { it.first }.distinctBy { it.first }
        if (sorted.size < 2) return emptyList()
        val withDuration = sorted.count { it.second != null }
        if (pgs && withDuration * 4 < sorted.size * 3) {
            // Pair in order, but a gap too long to be a shown line means a display or clear set
            // is missing: step one frame on, so the pairs that follow line up again.
            val cues = mutableListOf<Cue>()
            var i = 0
            while (i + 1 < sorted.size) {
                val (start, duration) = sorted[i]
                val clear = sorted[i + 1].first
                val end = duration?.let { minOf(start + it, clear) } ?: clear
                if (end - start in 100..MAX_PAIRED_MS) {
                    cues += Cue(start, end)
                    i += 2
                } else i += 1
            }
            return cues
        }
        return sorted.mapIndexedNotNull { i, (start, duration) ->
            val next = sorted.getOrNull(i + 1)?.first
            val end = duration?.let { start + it } ?: next?.let { minOf(it, start + MAX_GUESSED_MS) } ?: (start + 2_000)
            Cue(start, end).takeIf { end > start }
        }
    }

    private const val MAX_GUESSED_MS = 6_000L
    private const val MAX_PAIRED_MS = 15_000L
}

/**
 * An image subtitle track's timings from ffprobe (ffmpeg's prober), for files whose index
 * doesn't list them: the packets of a few stretches of the video, read with `-read_intervals`
 * so only those stretches are fetched. A PGS display set that clears the screen is a few bytes
 * and only ends the line before it.
 */
object FfprobeReference {
    private const val WINDOWS = 6
    private const val WINDOW_SEC = 90
    private const val CLEAR_MAX_BYTES = 64

    fun available(): Boolean = runCatching {
        val p = ProcessBuilder("ffprobe", "-version").redirectErrorStream(true).start()
        // Drained on the side, so output that never ends can't hold up the timeout.
        Thread { runCatching { p.inputStream.readAllBytes() } }.apply { isDaemon = true; start() }
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching false
        }
        p.exitValue() == 0
    }.getOrDefault(false)

    /** The stretches to read: [WINDOWS] of [WINDOW_SEC] spread over [durationSec], or everything when unknown or short. */
    fun windows(durationSec: Double?): List<Span> {
        val total = durationSec?.takeIf { it > 0 } ?: return emptyList()
        if (total <= WINDOWS * WINDOW_SEC * 1.5) return listOf(Span(0, (total * 1000).toLong()))
        return (0 until WINDOWS).map { i ->
            val start = ((total - WINDOW_SEC) * (i + 0.5) / WINDOWS).toLong()
            Span(start * 1000, (start + WINDOW_SEC) * 1000)
        }
    }

    fun command(url: String, subtitleOrdinal: Int, windows: List<Span>, headers: Map<String, String>): List<String> = buildList {
        add("ffprobe"); add("-v"); add("error")
        if (url.startsWith("http")) {
            add("-rw_timeout"); add("20000000")
            headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { add("-user_agent"); add(it.value) }
            val others = headers.filterKeys { !it.equals("User-Agent", true) }
            if (others.isNotEmpty()) { add("-headers"); add(others.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }) }
        }
        if (windows.isNotEmpty()) {
            add("-read_intervals"); add(windows.joinToString(",") { "${it.startMs / 1000}%+${(it.endMs - it.startMs) / 1000}" })
        }
        add("-select_streams"); add("s:$subtitleOrdinal")
        add("-show_entries"); add("packet=pts_time,duration_time,size")
        add("-of"); add("csv=p=0")
        add(url)
    }

    /** ffprobe's `pts,duration,size` rows as lines. */
    fun parse(output: String): List<Cue> {
        data class Packet(val startMs: Long, val durationMs: Long?, val size: Int)
        val packets = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(',')
            val pts = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            Packet((pts * 1000).toLong(), parts.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }, parts.getOrNull(2)?.toIntOrNull() ?: Int.MAX_VALUE)
        }.sortedBy { it.startMs }.toList()
        return packets.mapIndexedNotNull { i, p ->
            if (p.size <= CLEAR_MAX_BYTES) return@mapIndexedNotNull null
            val next = packets.getOrNull(i + 1)?.startMs
            val end = p.durationMs?.let { p.startMs + it } ?: next?.takeIf { it - p.startMs <= 10_000 } ?: (p.startMs + 3_000)
            Cue(p.startMs, end).takeIf { end > p.startMs }
        }
    }

    fun read(url: String, subtitleOrdinal: Int, durationSec: Double?, headers: Map<String, String>): FileReference? = runCatching {
        val windows = windows(durationSec)
        val process = ProcessBuilder(command(url, subtitleOrdinal, windows, headers)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = StringBuilder()
        val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        reader.join(2_000)
        val cues = parse(output.toString())
        if (cues.size < MatroskaCueIndex.MIN_CUES) return null
        FileReference(cues, windows.ifEmpty { listOf(Span(0, cues.last().endMs)) }, "ffprobe")
    }.getOrNull()
}
