package com.fetch.tv.core.streams.ranking

import com.fetch.tv.data.mapper.toDomain
import com.fetch.tv.data.remote.dto.StreamResponseDto
import com.fetch.tv.domain.model.Stream
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The parser over real addon responses (`src/test/resources/streams/<addon>/<title>.json`,
 * captured with `scripts/stream_fixtures.py`).
 *
 * Two kinds of check: where an addon states a value in a fixed place of its own (Comet's
 * "📹 hevc • DV" line, Torrentio's "4k DV | HDR" name), what the parser reads from the whole
 * text has to agree; and `expected.tsv` pins what the parser makes of every stream, so a change
 * in what it reads from real titles shows up as a diff to review. After a deliberate parser
 * change, or new fixtures, rewrite it with
 *
 *     ./gradlew :shared:test --tests '*StreamTitleParserFixturesTest*' -Dfetch.updateStreamFixtures=true
 *
 * and read the diff.
 */
class StreamTitleParserFixturesTest {

    private data class Fixture(val addon: String, val title: String, val index: Int, val stream: Stream) {
        val key: String get() = "$addon/$title#$index"
        val text: String get() = listOfNotNull(stream.name, stream.title, stream.description).joinToString("\n")
    }

    private val fixtures: List<Fixture> by lazy {
        val adapter = Moshi.Builder().build().adapter(StreamResponseDto::class.java)
        val root = resourceDir()
        root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.flatMap { addonDir ->
            addonDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.flatMap { file ->
                val response = adapter.fromJson(file.readText()) ?: error("Unreadable fixture $file")
                response.streams.orEmpty().mapIndexed { index, dto ->
                    Fixture(addonDir.name, file.nameWithoutExtension, index, dto.toDomain(addonDir.name, null))
                }
            }
        }
    }

    @Test
    fun `fixtures are present for each captured addon`() {
        val addons = fixtures.map { it.addon }.toSet()
        assertTrue(
            "Missing stream fixtures under ${resourceDir()}: have $addons",
            addons.containsAll(listOf("torrentio", "comet", "mediafusion"))
        )
    }

    // ── Torrentio: the name's second line is the addon's own quality summary ─────

    @Test
    fun `torrentio name line agrees with the parsed resolution and hdr`() {
        val problems = fixtures.filter { it.addon == "torrentio" }.mapNotNull { fixture ->
            val summary = fixture.stream.name.orEmpty().lineSequence().drop(1).firstOrNull().orEmpty().lowercase()
            val parsed = StreamTitleParser.parse(fixture.stream)
            val resolution = when {
                summary.startsWith("4k") -> StreamResolution.UHD_2160
                summary.startsWith("1440p") -> StreamResolution.QHD_1440
                summary.startsWith("1080p") -> StreamResolution.FHD_1080
                summary.startsWith("720p") -> StreamResolution.HD_720
                summary.startsWith("480p") -> StreamResolution.SD_480
                else -> null
            }
            val tags = summary.substringAfter(' ', "").split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val hdr = buildSet {
                if ("dv" in tags) add(HdrFormat.DOLBY_VISION)
                if ("hdr10+" in tags) add(HdrFormat.HDR10_PLUS)
                if ("hdr" in tags) add(HdrFormat.HDR10)
            }
            listOfNotNull(
                "resolution ${parsed.resolution}, name says $resolution".takeIf { resolution != null && parsed.resolution != resolution },
                "hdr ${parsed.hdr}, name says $hdr".takeIf { !parsed.hdr.containsAll(hdr) }
            ).takeIf { it.isNotEmpty() }?.let { "${fixture.key}: ${it.joinToString("; ")}" }
        }
        assertNoProblems(problems)
    }

    // ── Comet: one field per line, each behind its own emoji ─────────────────────

    @Test
    fun `comet fields agree with the parsed descriptor`() {
        val problems = fixtures.filter { it.addon == "comet" }.mapNotNull { fixture ->
            val parsed = StreamTitleParser.parse(fixture.stream)
            val description = fixture.stream.description.orEmpty()
            val name = fixture.stream.name.orEmpty()
            val video = description.fieldAfter("📹").lowercase().split('•', '|').map { it.trim() }
            val audio = description.fieldAfter("🔊").lowercase().split('•').map { it.trim() }
            val group = description.fieldAfter("🏷️").trim().takeIf { it.isNotEmpty() }
            val seeders = Regex("👤 (\\d+)").find(description)?.groupValues?.get(1)?.toInt()
            val resolution = when {
                name.endsWith("2160p") -> StreamResolution.UHD_2160
                name.endsWith("1080p") -> StreamResolution.FHD_1080
                name.endsWith("720p") -> StreamResolution.HD_720
                else -> null
            }
            buildList {
                if (resolution != null && parsed.resolution != resolution) add("resolution ${parsed.resolution}, name says $resolution")
                if ("dv" in video && !parsed.hasDolbyVision) add("no DV, Comet says DV")
                if ("hdr10+" in video && HdrFormat.HDR10_PLUS !in parsed.hdr) add("no HDR10+, Comet says HDR10+")
                if ("hdr" in video && HdrFormat.HDR10 !in parsed.hdr) add("no HDR10, Comet says HDR")
                if ("hevc" in video && parsed.videoCodec != VideoCodec.HEVC) add("codec ${parsed.videoCodec}, Comet says HEVC")
                if ("avc" in video && parsed.videoCodec != VideoCodec.AVC) add("codec ${parsed.videoCodec}, Comet says AVC")
                if ("av1" in video && parsed.videoCodec != VideoCodec.AV1) add("codec ${parsed.videoCodec}, Comet says AV1")
                if ("10bit" in video && parsed.bitDepth != 10) add("bit depth ${parsed.bitDepth}, Comet says 10bit")
                if ("atmos" in audio && !parsed.hasAtmos) add("no Atmos, Comet says Atmos")
                if ("truehd" in audio && AudioFormat.TRUEHD !in parsed.audio) add("no TrueHD, Comet says TrueHD")
                if ("dts lossless" in audio && parsed.audio.none { it.hasDtsCore }) add("audio ${parsed.audio}, Comet says DTS Lossless")
                if (group != null && parsed.releaseGroup != group) add("group ${parsed.releaseGroup}, Comet says $group")
                if (seeders != null && parsed.seeders != seeders) add("seeders ${parsed.seeders}, Comet says $seeders")
            }.takeIf { it.isNotEmpty() }?.let { "${fixture.key}: ${it.joinToString("; ")}" }
        }
        assertNoProblems(problems)
    }

    // ── MediaFusion: emoji-tagged fields, "🌐 English + French" languages ────────

    @Test
    fun `mediafusion fields agree with the parsed descriptor`() {
        val problems = fixtures.filter { it.addon == "mediafusion" }.mapNotNull { fixture ->
            val parsed = StreamTitleParser.parse(fixture.stream)
            val description = fixture.stream.description.orEmpty()
            val name = fixture.stream.name.orEmpty().trim()
            val hdr = description.mediaFusionField("🎨").lowercase().split('|').map { it.trim() }
            val codec = description.mediaFusionField("🎞️").lowercase().trim()
            val audio = description.mediaFusionField("🎵").lowercase().split('|').map { it.trim() }
            val languages = description.lineSequence().firstOrNull { it.startsWith("🌐") }
                ?.removePrefix("🌐")?.split('+')?.map { it.trim() }.orEmpty()
            val size = Regex("📦 (\\d+(?:\\.\\d+)?) (GB|MB)").find(description)?.let { match ->
                match.groupValues[1].toDouble() * if (match.groupValues[2] == "GB") 1_073_741_824.0 else 1_048_576.0
            }
            val resolution = when {
                name.endsWith("2160p") || name.endsWith("4k", ignoreCase = true) -> StreamResolution.UHD_2160
                name.endsWith("1080p") -> StreamResolution.FHD_1080
                name.endsWith("720p") -> StreamResolution.HD_720
                name.endsWith("480p") -> StreamResolution.SD_480
                else -> null
            }
            buildList {
                if (resolution != null && parsed.resolution != resolution) add("resolution ${parsed.resolution}, name says $resolution")
                if ("dv" in hdr && !parsed.hasDolbyVision) add("no DV, MediaFusion says DV")
                if ("hdr10+" in hdr && HdrFormat.HDR10_PLUS !in parsed.hdr) add("no HDR10+, MediaFusion says HDR10+")
                if ("hdr" in hdr && HdrFormat.HDR10 !in parsed.hdr) add("no HDR10, MediaFusion says HDR")
                if (codec == "hevc" && parsed.videoCodec != VideoCodec.HEVC) add("codec ${parsed.videoCodec}, MediaFusion says HEVC")
                if (codec == "avc" && parsed.videoCodec != VideoCodec.AVC) add("codec ${parsed.videoCodec}, MediaFusion says AVC")
                if (codec == "av1" && parsed.videoCodec != VideoCodec.AV1) add("codec ${parsed.videoCodec}, MediaFusion says AV1")
                if ("atmos" in audio && !parsed.hasAtmos) add("no Atmos, MediaFusion says Atmos")
                if ("truehd" in audio && AudioFormat.TRUEHD !in parsed.audio) add("no TrueHD, MediaFusion says TrueHD")
                if ("dts lossless" in audio && parsed.audio.none { it.hasDtsCore }) add("audio ${parsed.audio}, MediaFusion says DTS Lossless")
                // behaviorHints.videoSize is exact and wins; the badge is rounded to 10 MB.
                val parsedSize = parsed.sizeBytes
                if (size != null && (parsedSize == null || kotlin.math.abs(parsedSize - size) > size * 0.01 + 10_485_760)) {
                    add("size $parsedSize, MediaFusion says ${size.toLong()}")
                }
                languages.mapNotNull { StreamLanguage.fromCode(it) }.filter { it !in parsed.languages }
                    .takeIf { it.isNotEmpty() }?.let { add("missing languages $it") }
                // 🧲 ⏳ marks a plain torrent: there is no debrid link to wait for.
                if (fixture.stream.isTorrent() && parsed.isDebridUncached) add("bare torrent read as uncached debrid")
            }.takeIf { it.isNotEmpty() }?.let { "${fixture.key}: ${it.joinToString("; ")}" }
        }
        assertNoProblems(problems)
    }

    // ── Every addon: sizes and seeders where the addon writes them ───────────────

    @Test
    fun `size and seeders badges are read`() {
        val problems = fixtures.mapNotNull { fixture ->
            val parsed = StreamTitleParser.parse(fixture.stream)
            val text = fixture.text
            val seeders = Regex("👤 (\\d+)").find(text)?.groupValues?.get(1)?.toInt()
            val size = Regex("💾 (\\d+(?:\\.\\d+)?) (GB|MB)").find(text)?.let { match ->
                val unit = if (match.groupValues[2] == "GB") 1_073_741_824.0 else 1_048_576.0
                val decimals = match.groupValues[1].substringAfter('.', "").length
                // The badge is rounded to its last digit; allow half of one, plus 1 % (GB vs GiB).
                val tolerance = unit * 0.5 * Math.pow(10.0, -decimals.toDouble())
                Pair(match.groupValues[1].toDouble() * unit, tolerance)
            }
            buildList {
                if (seeders != null && parsed.seeders != seeders) add("seeders ${parsed.seeders}, badge says $seeders")
                val parsedSize = parsed.sizeBytes
                // Comet's videoSize is exact; the badge is rounded.
                if (size != null) {
                    val (badge, tolerance) = size
                    if (parsedSize == null || kotlin.math.abs(parsedSize - badge) > tolerance + badge * 0.01) {
                        add("size $parsedSize, badge says ${badge.toLong()}")
                    }
                }
            }.takeIf { it.isNotEmpty() }?.let { "${fixture.key}: ${it.joinToString("; ")}" }
        }
        assertNoProblems(problems)
    }

    @Test
    fun `flag badges become languages`() {
        val problems = fixtures.mapNotNull { fixture ->
            val parsed = StreamTitleParser.parse(fixture.stream)
            val expected = StreamLanguage.entries.filter { language -> language.flags.any { it in fixture.text } }
            val missing = expected.filter { it !in parsed.languages }
            missing.takeIf { it.isNotEmpty() }?.let { "${fixture.key}: missing $it" }
        }
        assertNoProblems(problems)
    }

    @Test
    fun `nothing in the fixtures reads as a cam or telesync`() {
        // None of the captured titles is a theatre recording; a hit here is a false positive
        // that would sink a good release to the bottom of the list.
        val problems = fixtures.mapNotNull { fixture ->
            val source = StreamTitleParser.parse(fixture.stream).source
            "${fixture.key}: $source".takeIf { source == ReleaseSource.CAM || source == ReleaseSource.TELESYNC }
        }
        assertNoProblems(problems)
    }

    // ── Golden table ─────────────────────────────────────────────────────────────

    @Test
    fun `parsed descriptors match the reviewed table`() {
        val actual = buildString {
            appendLine(HEADER.joinToString("\t"))
            fixtures.forEach { appendLine(row(it).joinToString("\t")) }
        }
        val file = File(resourceDir(), "expected.tsv")
        if (System.getProperty("fetch.updateStreamFixtures") == "true") {
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("Missing $file; run with -Dfetch.updateStreamFixtures=true and review it")
        val expectedLines = file.readLines()
        val actualLines = actual.trimEnd('\n').lines()
        val diffs = actualLines.zip(expectedLines).filter { (a, e) -> a != e }.map { (a, e) -> "expected: $e\n  actual: $a" }
        assertEquals("Fixture streams added or removed; update the table", expectedLines.size, actualLines.size)
        if (diffs.isNotEmpty()) {
            fail("Parser output changed for ${diffs.size} stream(s):\n" + diffs.take(20).joinToString("\n"))
        }
    }

    private fun row(fixture: Fixture): List<String> {
        val d = StreamTitleParser.parse(fixture.stream)
        return listOf(
            fixture.key,
            d.resolution.name,
            d.videoCodec.name,
            d.hdr.joinToString(",") { it.name },
            d.dolbyVisionProfile?.toString().orEmpty(),
            d.audio.joinToString(",") { it.name } + if (d.hasAtmos) "+ATMOS" else "",
            d.sizeBytes?.let { StreamDescriptor.formatSize(it) }.orEmpty(),
            d.source.name,
            d.bitDepth?.toString().orEmpty(),
            if (d.isDebridCached) "cached" else "",
            d.seeders?.toString().orEmpty(),
            d.releaseGroup.orEmpty(),
            d.languages.joinToString(",") { it.code }
        )
    }

    private fun assertNoProblems(problems: List<String>) {
        if (problems.isNotEmpty()) fail("${problems.size} stream(s) parsed wrong:\n" + problems.joinToString("\n"))
    }

    private fun String.fieldAfter(marker: String): String =
        // "📹 hevc • DV | 🔊 DTS Lossless • 5.1": a field runs to the next " | " or the line's end.
        lineSequence().firstOrNull { marker in it }?.substringAfter(marker)?.substringBefore(" | ").orEmpty()

    // "🎨 DV|HDR 📺 BluRay REMUX 🎞️ hevc 🎵 Atmos": a field runs to the next marker or the line's end.
    private fun String.mediaFusionField(marker: String): String {
        val line = lineSequence().firstOrNull { marker in it } ?: return ""
        val rest = line.substringAfter(marker)
        val end = MEDIAFUSION_MARKERS.mapNotNull { m -> rest.indexOf(m).takeIf { it >= 0 } }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    private fun resourceDir(): File {
        val url = javaClass.classLoader.getResource("streams") ?: error("No streams fixtures on the test classpath")
        // Write updates back to the source tree, not the build output.
        val built = File(url.toURI())
        val source = generateSequence(built) { it.parentFile }
            .firstOrNull { File(it, "src/test/resources/streams").isDirectory }
            ?.let { File(it, "src/test/resources/streams") }
        return source ?: built
    }

    private companion object {
        val HEADER = listOf(
            "stream", "resolution", "codec", "hdr", "dv", "audio", "size", "source", "bits",
            "cached", "seeders", "group", "languages"
        )
        val MEDIAFUSION_MARKERS = listOf("🎨", "📺", "🎞️", "🎵", "🔊", "📦", "👤", "🌐", "🔗")
    }
}
