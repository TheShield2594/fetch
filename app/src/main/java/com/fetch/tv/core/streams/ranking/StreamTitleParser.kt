package com.fetch.tv.core.streams.ranking

import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamDebridCacheState
import java.util.Locale

/**
 * Turns the free-form strings addons put in `name`, `title` and `description`
 * (plus `behaviorHints.filename` / `videoSize`) into a [StreamDescriptor].
 *
 * Addons format these very differently, so every rule here is tolerant: a token is
 * recognised whether it is separated by spaces, dots, dashes, brackets or emoji.
 */
object StreamTitleParser {
    private const val CACHE_SIZE = 2_048

    // Long lists are re-ranked on every addon emission and every card parses its stream again;
    // the text rarely changes, so parsing it once is enough.
    private val cache = object : LinkedHashMap<String, StreamDescriptor>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamDescriptor>?): Boolean =
            size > CACHE_SIZE
    }

    fun parse(stream: Stream): StreamDescriptor {
        val text = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename
        ).joinToString("\n")
        val parsed = synchronized(cache) { cache[text] }
            ?: parse(text).also { descriptor -> synchronized(cache) { cache[text] = descriptor } }
        val cached = parsed.isDebridCached ||
            stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED ||
            stream.isDirectDebrid()
        return parsed.copy(
            sizeBytes = stream.behaviorHints?.videoSize?.takeIf { it > 0 } ?: parsed.sizeBytes,
            isDebridCached = cached
        )
    }

    fun parse(rawText: String): StreamDescriptor {
        if (rawText.isBlank()) return StreamDescriptor.Empty
        // Normalise separators so "DDP5.1.Atmos", "H.265" and "[RD+]" tokenise predictably.
        val text = rawText.replace('_', ' ')
        val hdr = parseHdr(text)
        return StreamDescriptor(
            resolution = parseResolution(text),
            videoCodec = parseCodec(text),
            hdr = hdr,
            dolbyVisionProfile = if (HdrFormat.DOLBY_VISION in hdr) parseDolbyVisionProfile(text) else null,
            audio = parseAudio(text),
            hasAtmos = ATMOS.containsMatchIn(text),
            audioChannels = parseChannels(text),
            sizeBytes = parseSize(text),
            source = parseSource(text),
            is3d = THREE_D.containsMatchIn(text),
            bitDepth = parseBitDepth(text),
            isDebridCached = DEBRID_CACHED.containsMatchIn(text),
            seeders = SEEDERS.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            releaseGroup = parseReleaseGroup(rawText)
        )
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    private val RES_2160 = token("2160p?|4k|uhd")
    private val RES_1440 = token("1440p?|2k|qhd")
    private val RES_1080 = token("1080[pi]?|fhd|full ?hd")
    private val RES_720 = token("720p?|hd ?ready")
    private val RES_576 = token("576p?")
    private val RES_480 = token("480p?|sd|dvd ?rip")
    private val RES_360 = token("360p?")

    private fun parseResolution(text: String): StreamResolution = when {
        RES_2160.containsMatchIn(text) -> StreamResolution.UHD_2160
        RES_1440.containsMatchIn(text) -> StreamResolution.QHD_1440
        RES_1080.containsMatchIn(text) -> StreamResolution.FHD_1080
        RES_720.containsMatchIn(text) -> StreamResolution.HD_720
        RES_576.containsMatchIn(text) -> StreamResolution.SD_576
        RES_480.containsMatchIn(text) -> StreamResolution.SD_480
        RES_360.containsMatchIn(text) -> StreamResolution.SD_360
        else -> StreamResolution.UNKNOWN
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    private val AV1 = token("av1")
    private val HEVC = token("hevc|[hx] ?\\.?265|h265")
    private val AVC = token("avc|[hx] ?\\.?264|h264")
    private val VP9 = token("vp9")
    private val VC1 = token("vc-?1")
    private val MPEG2 = token("mpeg-?2")

    private fun parseCodec(text: String): VideoCodec = when {
        AV1.containsMatchIn(text) -> VideoCodec.AV1
        HEVC.containsMatchIn(text) -> VideoCodec.HEVC
        AVC.containsMatchIn(text) -> VideoCodec.AVC
        VP9.containsMatchIn(text) -> VideoCodec.VP9
        VC1.containsMatchIn(text) -> VideoCodec.VC1
        MPEG2.containsMatchIn(text) -> VideoCodec.MPEG2
        else -> VideoCodec.UNKNOWN
    }

    private val DOLBY_VISION = token("dv|dovi|dolby ?vision|dvhe(?:\\.\\d+)?|dvh1(?:\\.\\d+)?")
    private val HDR10_PLUS = token("hdr10\\+|hdr10plus|hdr10p|hdr\\+")
    private val HDR10 = token("hdr10|hdr")
    private val HLG = token("hlg")
    private val SDR = token("sdr")

    private fun parseHdr(text: String): Set<HdrFormat> {
        val formats = linkedSetOf<HdrFormat>()
        if (DOLBY_VISION.containsMatchIn(text)) formats += HdrFormat.DOLBY_VISION
        if (HDR10_PLUS.containsMatchIn(text)) formats += HdrFormat.HDR10_PLUS
        if (HDR10.containsMatchIn(text.replace(HDR10_PLUS, " "))) formats += HdrFormat.HDR10
        if (HLG.containsMatchIn(text)) formats += HdrFormat.HLG
        if (formats.isEmpty() || SDR.containsMatchIn(text) && formats == setOf(HdrFormat.HDR10)) {
            return if (SDR.containsMatchIn(text)) emptySet() else formats
        }
        return formats
    }

    private val DV_PROFILE = Regex(
        "(?:dv|dovi|dolby ?vision)[ .\\-]*(?:p|profile|prof\\.?)[ .\\-]?0?([5789])(?:\\.\\d)?|" +
            "dvhe\\.0?([5789])|dvh1\\.0?([5789])|\\bp0?([5789])(?:\\.\\d)?[ .\\-]*(?:dv|dovi)\\b",
        RegexOption.IGNORE_CASE
    )
    private val DV_FEL = token("fel")

    private fun parseDolbyVisionProfile(text: String): Int? {
        DV_PROFILE.find(text)?.let { match ->
            return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        }
        // A full-enhancement-layer release is profile 7 even when it doesn't say so.
        if (DV_FEL.containsMatchIn(text)) return 7
        return null
    }

    private val BIT_DEPTH = Regex("\\b(8|10|12)[ -]?bits?\\b", RegexOption.IGNORE_CASE)

    private fun parseBitDepth(text: String): Int? =
        BIT_DEPTH.find(text)?.groupValues?.get(1)?.toIntOrNull()

    private val THREE_D = token("3d|sbs|h-?sbs|half-?ou")

    // ── Audio ─────────────────────────────────────────────────────────────────

    // Audio tags are often glued to their channel layout ("DDP5.1", "TrueHD7.1").
    private const val LAYOUT = "(?:[1-7][ .]?[01])?"
    private val TRUEHD = token("true ?-?hd$LAYOUT")
    private val DTS_X = token("dts[ :.\\-]?x$LAYOUT")
    private val DTS_HD_MA = token("dts[ .\\-]?hd(?:[ .\\-]?ma)?$LAYOUT|dts[ .\\-]?ma$LAYOUT")
    private val DTS = token("dts$LAYOUT")
    private val EAC3 = token("e-?ac-?3$LAYOUT|ddp$LAYOUT|dd\\+$LAYOUT|dolby ?digital ?plus")
    private val AC3 = token("ac-?3$LAYOUT|dd$LAYOUT|dolby ?digital")
    private val FLAC = token("flac")
    private val OPUS = token("opus")
    private val AAC = token("aac(?:\\d\\.\\d)?")
    private val MP3 = token("mp3")
    private val ATMOS = token("atmos")

    private fun parseAudio(text: String): Set<AudioFormat> {
        val formats = linkedSetOf<AudioFormat>()
        if (TRUEHD.containsMatchIn(text)) formats += AudioFormat.TRUEHD
        when {
            DTS_X.containsMatchIn(text) -> formats += AudioFormat.DTS_X
            DTS_HD_MA.containsMatchIn(text) -> formats += AudioFormat.DTS_HD_MA
            DTS.containsMatchIn(text) -> formats += AudioFormat.DTS
        }
        // "DDP5.1" also contains "DD"; only fall back to AC3 when no DD+ token was found.
        if (EAC3.containsMatchIn(text)) {
            formats += AudioFormat.EAC3
        } else if (AC3.containsMatchIn(text.replace(EAC3, " "))) {
            formats += AudioFormat.AC3
        }
        if (FLAC.containsMatchIn(text)) formats += AudioFormat.FLAC
        if (OPUS.containsMatchIn(text)) formats += AudioFormat.OPUS
        if (AAC.containsMatchIn(text)) formats += AudioFormat.AAC
        if (MP3.containsMatchIn(text)) formats += AudioFormat.MP3
        return formats
    }

    private val CHANNELS = Regex("(?<!\\d)(?<!\\d\\.)([1-9])[ .]([01])(?:ch)?(?![\\d])|\\b([2-8])ch\\b", RegexOption.IGNORE_CASE)

    private fun parseChannels(text: String): Int? {
        return CHANNELS.findAll(text).mapNotNull { match ->
            val main = match.groupValues[1].toIntOrNull()
            val lfe = match.groupValues[2].toIntOrNull()
            when {
                main != null && lfe != null && main in setOf(1, 2, 5, 6, 7) -> main + lfe
                match.groupValues[3].isNotEmpty() -> match.groupValues[3].toInt()
                else -> null
            }
        }.maxOrNull()
    }

    // ── Size ──────────────────────────────────────────────────────────────────

    private val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s?(tb|gb|gib|mb|mib)\\b", RegexOption.IGNORE_CASE)

    private fun parseSize(text: String): Long? {
        // Addons that list both a file and a pack size put the file size first.
        return SIZE.findAll(text).mapNotNull { match ->
            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "tb" -> 1_099_511_627_776.0
                "gb", "gib" -> 1_073_741_824.0
                else -> 1_048_576.0
            }
            (value * multiplier).toLong()
        }.firstOrNull()
    }

    // ── Source ────────────────────────────────────────────────────────────────

    private val REMUX = token("remux|bdremux")
    private val BLURAY = token("blu-?ray|bd-?rip|brrip|bdmv|uhd ?bd")
    private val WEB_DL = token("web-?dl|web")
    private val WEBRIP = token("web-?rip")
    private val HDTV = token("hdtv|pdtv|dsr")
    private val DVD = token("dvd(?:rip|5|9)?")
    private val TELESYNC = Regex(
        "(?<![A-Za-z0-9])(?:telesync|telecine|hd-?ts|hd-?tc)(?![A-Za-z0-9])|(?<![A-Za-z0-9.])(?:TS|TC)(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE
    )
    private val CAM = token("cam|camrip|hdcam")

    private fun parseSource(text: String): ReleaseSource = when {
        CAM.containsMatchIn(text) -> ReleaseSource.CAM
        TELESYNC.containsMatchIn(text) -> ReleaseSource.TELESYNC
        REMUX.containsMatchIn(text) -> ReleaseSource.REMUX
        BLURAY.containsMatchIn(text) -> ReleaseSource.BLURAY
        WEBRIP.containsMatchIn(text) -> ReleaseSource.WEBRIP
        WEB_DL.containsMatchIn(text) -> ReleaseSource.WEB_DL
        HDTV.containsMatchIn(text) -> ReleaseSource.HDTV
        DVD.containsMatchIn(text) -> ReleaseSource.DVD
        else -> ReleaseSource.UNKNOWN
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    private val DEBRID_CACHED = Regex(
        "\\[(?:rd|ad|pm|tb|dl|ed|oc|pp)\\+]|\\b(?:rd|ad|pm|tb)\\+(?=\\W|$)|⚡|\\binstant\\b|\\bcached\\b",
        RegexOption.IGNORE_CASE
    )
    private val SEEDERS = Regex("(?:👤|seeders?:?|seeds?:?|\\bS:)\\s?(\\d{1,6})", RegexOption.IGNORE_CASE)
    private val RELEASE_GROUP = Regex("-([A-Za-z0-9]{2,15})(?:\\.(?:mkv|mp4|avi|ts|m2ts))?\\s*$")

    private fun parseReleaseGroup(text: String): String? {
        val candidate = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { RELEASE_GROUP.containsMatchIn(it) }
            ?: return null
        return RELEASE_GROUP.find(candidate)?.groupValues?.get(1)
            ?.takeUnless { it.equals("dl", true) || it.equals("rip", true) || it.all(Char::isDigit) }
    }

    /** A case-insensitive pattern that must be bounded by non-alphanumerics on both sides. */
    private fun token(pattern: String): Regex =
        Regex("(?<![A-Za-z0-9])(?:$pattern)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
}
