package com.fetch.desktop.player.autosync

import com.fetch.desktop.AddonSubtitle
import com.fetch.desktop.player.embedded.MpvTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

class AutoSyncRetimeTest {

    /** A dialogue-like track: lines of 1–4 s with gaps of 0.5–6 s. */
    private fun dialogue(seed: Int, count: Int, startMs: Long = 0): List<Cue> {
        val random = Random(seed)
        var t = startMs
        return List(count) {
            t += 500 + random.nextLong(5_500)
            val length = 1_000 + random.nextLong(3_000)
            Cue(t, t + length, "line $it").also { t += length }
        }
    }

    /** [video] as a subtitle made for other timing: video time = map(subtitle time). */
    private fun subtitleFor(video: List<Cue>, toSubtitle: (Long) -> Long) = video.map { Cue(toSubtitle(it.startMs), toSubtitle(it.endMs), it.text) }

    private fun assertLinesUp(result: RetimeResult, subtitle: List<Cue>, video: List<Cue>, toleranceMs: Long = 120) {
        val worst = subtitle.zip(video).maxOf { (s, v) -> abs(result.map.map(s.startMs) - v.startMs) }
        assertTrue("worst line off by $worst ms", worst <= toleranceMs)
    }

    @Test
    fun `a constant offset comes out as a plain shift`() {
        val video = dialogue(7, 500)
        val subtitle = subtitleFor(video) { it - 2_340 }
        val played = video.take(45)
        val result = AutoSyncRetimer.retime(played, emptyList(), subtitle)!!
        assertTrue(result.confident)
        assertTrue(result.map.isConstantShift)
        assertEquals(2_340.0, result.map.offsetMs, 30.0)
    }

    @Test
    fun `another frame rate is found and re-timed from the first minutes`() {
        val video = dialogue(11, 900)
        // Made for 25 fps (PAL speed-up) against a 23.976 fps video, and 1.5 s early.
        val ratio = 25.0 / 23.976
        val subtitle = subtitleFor(video) { ((it - 1_500) / ratio).roundToLong() }
        val played = video.filter { it.endMs < 5 * 60_000 }
        val result = AutoSyncRetimer.retime(played, emptyList(), subtitle)!!
        assertTrue(result.confident)
        assertFalse(result.map.isConstantShift)
        assertEquals(ratio, result.map.scale, 0.0005)
        // The whole film lines up, not only what played.
        assertLinesUp(result, subtitle, video, toleranceMs = 150)
    }

    @Test
    fun `a cut that differs bends the timing where it changes`() {
        val video = dialogue(5, 400)
        // The subtitle's cut has a scene 4 s shorter a third of the way in.
        val cut = video[60].startMs - 1_000
        val subtitle = subtitleFor(video) { if (it < cut) it + 800 else it + 800 - 4_000 }
        val played = video.filter { it.endMs < video[120].endMs }
        val result = AutoSyncRetimer.retime(played, emptyList(), subtitle)!!
        assertTrue(result.confident)
        assertTrue(result.map.anchors.isNotEmpty())
        // Lines well away from the cut land within a frame or two.
        val away = subtitle.zip(video).filterIndexed { i, _ -> i in 10..50 || i in 75..115 }
        val worst = away.maxOf { (s, v) -> abs(result.map.map(s.startMs) - v.startMs) }
        assertTrue("worst line off by $worst ms", worst <= 150)
    }

    @Test
    fun `sampled stretches of the whole film count only where they were read`() {
        val video = dialogue(21, 1_200)
        val subtitle = subtitleFor(video) { ((it + 3_000) * 1.001).roundToLong() }
        val spans = listOf(Span(600_000, 690_000), Span(2_400_000, 2_490_000), Span(4_200_000, 4_290_000))
        val seen = video.filter { c -> spans.any { c.startMs >= it.startMs && c.endMs <= it.endMs } }
        val result = AutoSyncRetimer.retime(seen, spans, subtitle)!!
        assertTrue(result.confident)
        assertLinesUp(result, subtitle, video, toleranceMs = 150)
    }

    /** [cues] as another person's subtitle would have them: times off by up to ±[jitterMs], some lines gone, some extra. */
    private fun messy(cues: List<Cue>, seed: Int, jitterMs: Long = 120): List<Cue> {
        val random = Random(seed)
        return cues.flatMap { c ->
            val j = { random.nextLong(-jitterMs, jitterMs + 1) }
            when (random.nextInt(20)) {
                0, 1 -> emptyList()
                2 -> listOf(c, Cue(c.endMs + 300, c.endMs + 1_300, "extra"))
                else -> listOf(Cue(c.startMs + j(), c.endMs + j(), c.text))
            }
        }.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    @Test
    fun `real-world noise keeps a plain offset plain and still finds a frame rate`() {
        val video = dialogue(31, 900)
        val played = messy(video, 1).filter { it.endMs < 4 * 60_000 }
        val shifted = messy(subtitleFor(video) { it + 4_200 }, 2)
        val plain = AutoSyncRetimer.retime(played, emptyList(), shifted)!!
        assertTrue(plain.confident)
        assertTrue("scale ${plain.map.scale}, anchors ${plain.map.anchors.size}", plain.map.isConstantShift)
        assertEquals(-4_200.0, plain.map.offsetMs, 60.0)

        val ratio = 23.976 / 24.0
        val slow = messy(subtitleFor(video) { (it / ratio).roundToLong() }, 3)
        val stretched = AutoSyncRetimer.retime(played, emptyList(), slow)!!
        assertTrue(stretched.confident)
        assertEquals(ratio, stretched.map.scale, 0.0002)
    }

    @Test
    fun `an unrelated subtitle isn't trusted`() {
        val result = AutoSyncRetimer.retime(dialogue(1, 60), emptyList(), dialogue(99, 500))
        assertFalse(result?.confident ?: false)
    }

    @Test
    fun `the map interpolates between anchors and follows the stretch past them`() {
        val map = TimeMap(1.0, 0.0, listOf(TimeMap.Anchor(1_000, 2_000), TimeMap.Anchor(3_000, 3_000)))
        assertEquals(2_500L, map.map(2_000))
        assertEquals(1_500L, map.map(500))
        assertEquals(4_000L, map.map(4_000))
        assertEquals(1_000L, TimeMap.shift(1_000).maxShift(0, 10_000))
        assertEquals(0L, TimeMap.IDENTITY.maxDifference(TimeMap(1.0, 0.0), 0, 5_000))
        // A correction between whole seconds still counts.
        val blip = TimeMap(1.0, 0.0, listOf(TimeMap.Anchor(1_000, 1_000), TimeMap.Anchor(1_400, 1_900), TimeMap.Anchor(1_800, 1_800)))
        assertEquals(500L, blip.maxShift(0, 3_000))
        assertEquals(listOf(Cue(1_500, 1_600, "a")), map.apply(listOf(Cue(500, 600, "a"))))
    }

    @Test
    fun `the live reference splits at a seek`() {
        val live = LiveReference()
        listOf(0L, 250, 500, 750, 60_000, 60_250).forEach(live::tick)
        assertEquals(listOf(Span(0, 750), Span(60_000, 60_250)), live.spans())
    }

    // --- Writing ---

    @Test
    fun `re-timing keeps the subtitle's own format`() {
        val map = TimeMap(1.0, 1_500.0)
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hi</i>\r\n\r\n2\r\n00:59:59,000 --> 01:00:00,000\r\nBye\r\n"
        assertEquals("1\r\n00:00:02,500 --> 00:00:04,000\r\n<i>Hi</i>\r\n\r\n2\r\n01:00:00,500 --> 01:00:01,500\r\nBye\r\n", SubtitleRetimeWriter.retime(srt, map))
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000 align:start\nHi\n"
        assertEquals("WEBVTT\n\n00:00:02.500 --> 00:00:03.500 align:start\nHi\n", SubtitleRetimeWriter.retime(vtt, map))
        val ass = """
            [Script Info]
            Title: x
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.10,0:00:07.00,Default,,0,0,0,,{\i1}Hello, there
        """.trimIndent()
        val out = SubtitleRetimeWriter.retime(ass, map)!!
        assertTrue(out.endsWith("Dialogue: 0,0:00:06.60,0:00:08.50,Default,,0,0,0,,{\\i1}Hello, there"))
        assertEquals(listOf(Cue(6_600, 8_500, "Hello, there")), CueParser.parse(out))
        assertNull(SubtitleRetimeWriter.retime("nothing here", map))
        // A time range inside a line's text isn't a timing line.
        val quoted = "1\n00:00:01,000 --> 00:00:02,000\nShe said 00:00:05,000 --> 00:00:06,000\n"
        assertEquals("1\n00:00:02,500 --> 00:00:03,500\nShe said 00:00:05,000 --> 00:00:06,000\n", SubtitleRetimeWriter.retime(quoted, map))
    }

    // --- Image subtitles: the Matroska index ---

    private fun ebml(id: Long, vararg children: ByteArray): ByteArray = ebmlData(id, children.fold(ByteArray(0)) { a, b -> a + b })

    private fun ebmlData(id: Long, data: ByteArray): ByteArray {
        val idBytes = generateSequence(id) { it ushr 8 }.takeWhile { it > 0 }.map { (it and 0xFF).toByte() }.toList().reversed().toByteArray()
        // An 8-byte size, so offsets are easy to know.
        val size = ByteArray(8).also { b -> b[0] = 0x01; for (i in 1..7) b[i] = ((data.size.toLong() ushr (8 * (7 - i))) and 0xFF).toByte() }
        return idBytes + size + data
    }

    private fun uintEl(id: Long, value: Long): ByteArray = ebmlData(id, ByteArray(8) { i -> ((value ushr (8 * (7 - i))) and 0xFF).toByte() })

    private fun strEl(id: Long, value: String) = ebmlData(id, value.toByteArray())

    @Test
    fun `reads a PGS track's timings from the Matroska index`() {
        val tracks = ebml(
            0x1654AE6B,
            ebml(0xAE, uintEl(0xD7, 1), uintEl(0x83, 1), strEl(0x86, "V_MPEG4/ISO/AVC")),
            ebml(0xAE, uintEl(0xD7, 3), uintEl(0x83, 0x11), strEl(0x86, "S_HDMV/PGS"))
        )
        // Display and clear pairs: 1.0–3.0 s, 5.0–6.5 s, ... (times in ms, scale 1 ms).
        val points = (0 until 30).flatMap { i -> listOf(1_000L + i * 4_000, 3_000L + i * 4_000) }
        val cues = ebml(0x1C53BB6B, *points.map { t ->
            ebml(0xBB, uintEl(0xB3, t), ebml(0xB7, uintEl(0xF7, 3), uintEl(0xF1, 0)))
        }.toTypedArray(), ebml(0xBB, uintEl(0xB3, 500), ebml(0xB7, uintEl(0xF7, 1), uintEl(0xF1, 0))))
        val info = ebml(0x1549A966, uintEl(0x2AD7B1, 1_000_000))
        val segmentBody = info + tracks + cues
        val file = ByteArrayOutputStream().apply {
            write(ebml(0x1A45DFA3, strEl(0x4282, "matroska")))
            write(ebmlData(0x18538067, segmentBody))
        }.toByteArray()
        val source = object : RangeSource {
            override fun read(offset: Long, length: Int): ByteArray? =
                if (offset >= file.size) null else file.copyOfRange(offset.toInt(), minOf(file.size, (offset + length).toInt()))
        }
        val ref = MatroskaCueIndex.read(source, trackNumber = 3, subtitleOrdinal = 0, durationMs = 200_000)!!
        assertEquals(30, ref.cues.size)
        assertEquals(Cue(1_000, 3_000), ref.cues.first())
        assertEquals(listOf(Span(0, 200_000)), ref.spans)
        // By its place among subtitle tracks too.
        assertNotNull(MatroskaCueIndex.read(source, trackNumber = null, subtitleOrdinal = 0, durationMs = null))
        assertNull(MatroskaCueIndex.read(object : RangeSource { override fun read(offset: Long, length: Int) = ByteArray(64) }, 3, 0, null))
    }

    @Test
    fun `ffprobe's packets become lines, clearing display sets end them`() {
        val out = "1.000000,N/A,2400\n3.000000,N/A,30\n5.000000,1.500000,1800\n40.000000,N/A,900\n"
        assertEquals(listOf(Cue(1_000, 3_000), Cue(5_000, 6_500), Cue(40_000, 43_000)), FfprobeReference.parse(out))
        val windows = FfprobeReference.windows(7_200.0)
        assertEquals(6, windows.size)
        assertTrue(windows.all { it.lengthMs == 90_000L && it.endMs <= 7_200_000 })
        assertEquals(listOf(Span(0, 600_000)), FfprobeReference.windows(600.0))
        val cmd = FfprobeReference.command("https://x/y.mkv", 2, windows.take(1), mapOf("User-Agent" to "Fetch", "Referer" to "https://x"))
        assertTrue(cmd.containsAll(listOf("-user_agent", "Fetch", "-headers", "Referer: https://x\r\n", "s:2")))
    }

    @Test
    fun `PGS frames without durations pair into lines`() {
        val points = listOf(1_000L to null, 2_500L to null, 4_000L to null, 6_000L to null)
        assertEquals(listOf(Cue(1_000, 2_500), Cue(4_000, 6_000)), MatroskaCueIndex.timeline(points, pgs = true))
        // A missing clear set: the pairs after it line up again.
        val missing = listOf(1_000L to null, 2_500L to null, 30_000L to null, 60_000L to null, 61_500L to null)
        assertEquals(listOf(Cue(1_000, 2_500), Cue(60_000, 61_500)), MatroskaCueIndex.timeline(missing, pgs = true))
        val vobsub = listOf(1_000L to 1_200L, 4_000L to null)
        assertEquals(listOf(Cue(1_000, 2_200), Cue(4_000, 6_000)), MatroskaCueIndex.timeline(vobsub, pgs = false))
    }

    // --- Which tracks ---

    private fun track(id: String, codec: String, lang: String? = null, forced: Boolean = false, external: Boolean = false) =
        MpvTrack(id, "sub", null, lang, codec, external, selected = false, forced = forced)

    @Test
    fun `text tracks are preferred, then image tracks, unforced and in the language`() {
        val pgs = track("1", "hdmv_pgs_subtitle", "eng")
        val forced = track("2", "subrip", "eng", forced = true)
        val german = track("3", "subrip", "ger")
        val english = track("4", "ass", "eng")
        val addon = track("5", "subrip", "eng", external = true)
        assertEquals("4", AutoSyncSources.reference(listOf(pgs, forced, german, english, addon), "en")!!.track.id)
        assertEquals("3", AutoSyncSources.reference(listOf(pgs, forced, german), "en")!!.track.id)
        val image = AutoSyncSources.reference(listOf(track("1", "dvd_subtitle", "fre"), pgs), "en")!!
        assertTrue(image.image)
        assertEquals("1", image.track.id)
        assertEquals(1, image.ordinal)
        assertNull(AutoSyncSources.reference(listOf(addon), "en"))
    }

    @Test
    fun `alternatives are the other subtitles in the language`() {
        val subs = listOf(
            AddonSubtitle("eng", "https://a/1.srt", "A"),
            AddonSubtitle("en", "https://a/2.srt", "A"),
            AddonSubtitle("ger", "https://a/3.srt", "B"),
            AddonSubtitle("English", "https://b/4.srt", "B")
        )
        assertEquals(listOf("https://a/2.srt", "https://b/4.srt"), AutoSyncSources.alternatives("https://a/1.srt", "eng", subs).map { it.url })
        assertTrue(AutoSyncSources.alternatives("https://a/1.srt", null, subs).isEmpty())
        assertEquals(mapOf("Referer" to "https://x/a,b", "Cookie" to "k=v", "User-Agent" to "UA"), AutoSyncSources.headers("Referer: https://x/a,b,Cookie: k=v", "UA"))
    }
}
