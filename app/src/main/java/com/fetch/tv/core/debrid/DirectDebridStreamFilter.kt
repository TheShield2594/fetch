package com.fetch.tv.core.debrid

import com.fetch.tv.domain.model.DebridStreamAudioChannel
import com.fetch.tv.domain.model.DebridStreamAudioTag
import com.fetch.tv.domain.model.DebridStreamEncode
import com.fetch.tv.domain.model.DebridStreamLanguage
import com.fetch.tv.domain.model.DebridStreamPreferences
import com.fetch.tv.domain.model.DebridStreamQuality
import com.fetch.tv.domain.model.DebridStreamResolution
import com.fetch.tv.domain.model.DebridStreamVisualTag
import com.fetch.tv.domain.model.Stream
import java.util.concurrent.ConcurrentHashMap

object DirectDebridStreamFilter {
    const val FALLBACK_SOURCE_NAME = "Direct Debrid"

    private val tokenRegexCache = ConcurrentHashMap<String, Regex>()
    private val dolbyVisionRegex = Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|$)")
    private val hdrRegex = Regex("(^|[^a-z0-9])(hdr|hdr10|hdr10plus|hdr10\\+|hlg)([^a-z0-9]|$)")
    private val releaseGroupRegex = Regex("-([a-z0-9][a-z0-9._]{1,24})($|\\.)", RegexOption.IGNORE_CASE)
    private val nonAlphanumericRegex = Regex("[^a-z0-9]")
    private val nonAlphanumericOrPlusRegex = Regex("[^a-z0-9+]")
    private val resolutionTokens = listOf(
        resolutionRegex("2160p?", "4k", "uhd") to DebridStreamResolution.P2160,
        resolutionRegex("1440p?", "2k") to DebridStreamResolution.P1440,
        resolutionRegex("1080p?", "fhd") to DebridStreamResolution.P1080,
        resolutionRegex("720p?", "hd") to DebridStreamResolution.P720,
        resolutionRegex("576p?") to DebridStreamResolution.P576,
        resolutionRegex("480p?", "sd") to DebridStreamResolution.P480,
        resolutionRegex("360p?") to DebridStreamResolution.P360
    )

    fun filterInstant(streams: List<Stream>): List<Stream> =
        streams
            .filter { isInstantCandidate(it) }
            .map { stream ->
                val sourceName = sourceName(stream)
                stream.copy(
                    name = stream.name ?: sourceName,
                    addonName = sourceName,
                    addonLogo = null
                )
            }
            .distinctBy { stream ->
                listOf(
                    stream.clientResolve?.infoHash?.lowercase(),
                    stream.clientResolve?.fileIdx?.toString(),
                    stream.clientResolve?.filename,
                    stream.name,
                    stream.title
                ).joinToString("|")
            }

    fun isInstantCandidate(stream: Stream): Boolean {
        val resolve = stream.clientResolve ?: return false
        return resolve.type.equals("debrid", ignoreCase = true) &&
            DebridProviders.isSupported(resolve.service) &&
            resolve.isCached == true
    }

    fun isDirectDebridSourceName(addonName: String): Boolean {
        return DebridProviders.all().any { addonName == DebridProviders.instantName(it.id) }
    }

    /** What the label templates show for [stream]: resolution, quality, tags, size and so on. */
    fun facts(stream: Stream): StreamFacts = streamFacts(stream, DebridStreamPreferences())

    private fun streamFacts(stream: Stream, preferences: DebridStreamPreferences): StreamFacts {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val searchText = streamSearchText(stream)
        val resolution = streamResolution(parsed?.resolution, parsed?.quality, stream.quality, searchText)
        val quality = streamQuality(parsed?.quality, searchText)
        val visualTags = streamVisualTags(parsed?.hdr.orEmpty(), searchText)
        val audioTags = streamAudioTags(parsed?.audio.orEmpty(), searchText)
        val audioChannels = streamAudioChannels(parsed?.channels.orEmpty(), searchText)
        val encode = streamEncode(parsed?.codec, searchText)
        val languages = parsed?.languages.orEmpty().mapNotNull { languageFor(it) }.ifEmpty {
            DebridStreamLanguage.entries.filter { searchText.hasToken(it.code) }
        }
        val releaseGroup = parsed?.group?.takeIf { it.isNotBlank() } ?: releaseGroupFromText(searchText)
        return StreamFacts(
            resolution = resolution,
            quality = quality,
            visualTags = visualTags,
            audioTags = audioTags,
            audioChannels = audioChannels,
            encode = encode,
            languages = languages,
            releaseGroup = releaseGroup,
            size = streamSize(stream),
            resolutionRank = rank(resolution, preferences.preferredResolutions),
            qualityRank = rank(quality, preferences.preferredQualities),
            visualRank = rankAny(visualTags, preferences.preferredVisualTags),
            audioRank = rankAny(audioTags, preferences.preferredAudioTags),
            channelRank = rankAny(audioChannels, preferences.preferredAudioChannels),
            encodeRank = rank(encode, preferences.preferredEncodes),
            languageRank = if (languages.isEmpty()) Int.MAX_VALUE else languages.minOf { rank(it, preferences.preferredLanguages) }
        )
    }

    private fun streamResolution(vararg values: String?): DebridStreamResolution {
        return values.firstNotNullOfOrNull { resolutionValue(it) } ?: DebridStreamResolution.UNKNOWN
    }

    private fun resolutionValue(value: String?): DebridStreamResolution? {
        val normalized = value?.lowercase().orEmpty()
        return resolutionTokens.firstOrNull { (regex, _) -> regex.containsMatchIn(normalized) }?.second
    }

    private fun streamQuality(parsedQuality: String?, searchText: String): DebridStreamQuality {
        val text = listOfNotNull(parsedQuality, searchText).joinToString(" ").lowercase()
        return when {
            text.contains("remux") -> DebridStreamQuality.BLURAY_REMUX
            text.contains("blu-ray") || text.contains("bluray") || text.contains("bdrip") || text.contains("brrip") -> DebridStreamQuality.BLURAY
            text.contains("web-dl") || text.contains("webdl") -> DebridStreamQuality.WEB_DL
            text.contains("webrip") || text.contains("web-rip") -> DebridStreamQuality.WEBRIP
            text.contains("hdrip") -> DebridStreamQuality.HDRIP
            text.contains("hd-rip") || text.contains("hcrip") -> DebridStreamQuality.HD_RIP
            text.contains("dvdrip") -> DebridStreamQuality.DVDRIP
            text.contains("hdtv") -> DebridStreamQuality.HDTV
            text.hasToken("cam") -> DebridStreamQuality.CAM
            text.hasToken("ts") -> DebridStreamQuality.TS
            text.hasToken("tc") -> DebridStreamQuality.TC
            text.hasToken("scr") -> DebridStreamQuality.SCR
            else -> DebridStreamQuality.UNKNOWN
        }
    }

    private fun streamVisualTags(parsedHdr: List<String>, searchText: String): List<DebridStreamVisualTag> {
        val text = (parsedHdr + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamVisualTag>()
        val hasDv = parsedHdr.any { it.isDolbyVisionToken() } || dolbyVisionRegex.containsMatchIn(searchText)
        val hasHdr = parsedHdr.any { it.isHdrToken() } || hdrRegex.containsMatchIn(searchText)
        if (hasDv && hasHdr) tags += DebridStreamVisualTag.HDR_DV
        if (hasDv && !hasHdr) tags += DebridStreamVisualTag.DV_ONLY
        if (hasHdr && !hasDv) tags += DebridStreamVisualTag.HDR_ONLY
        if (text.contains("hdr10+") || text.contains("hdr10plus")) tags += DebridStreamVisualTag.HDR10_PLUS
        if (text.contains("hdr10")) tags += DebridStreamVisualTag.HDR10
        if (hasDv) tags += DebridStreamVisualTag.DV
        if (hasHdr) tags += DebridStreamVisualTag.HDR
        if (text.hasToken("hlg")) tags += DebridStreamVisualTag.HLG
        if (text.contains("10bit") || text.contains("10 bit")) tags += DebridStreamVisualTag.TEN_BIT
        if (text.hasToken("3d")) tags += DebridStreamVisualTag.THREE_D
        if (text.hasToken("imax")) tags += DebridStreamVisualTag.IMAX
        if (text.hasToken("ai")) tags += DebridStreamVisualTag.AI
        if (text.hasToken("sdr")) tags += DebridStreamVisualTag.SDR
        if (text.contains("h-ou")) tags += DebridStreamVisualTag.H_OU
        if (text.contains("h-sbs")) tags += DebridStreamVisualTag.H_SBS
        return tags.distinct().ifEmpty { listOf(DebridStreamVisualTag.UNKNOWN) }
    }

    private fun streamAudioTags(parsedAudio: List<String>, searchText: String): List<DebridStreamAudioTag> {
        val text = (parsedAudio + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamAudioTag>()
        if (text.hasToken("atmos")) tags += DebridStreamAudioTag.ATMOS
        if (text.contains("dd+") || text.contains("ddp") || text.contains("dolby digital plus")) tags += DebridStreamAudioTag.DD_PLUS
        if (text.hasToken("dd") || text.contains("ac3") || text.contains("dolby digital")) tags += DebridStreamAudioTag.DD
        if (text.contains("dts:x") || text.contains("dtsx")) tags += DebridStreamAudioTag.DTS_X
        if (text.contains("dts-hd ma") || text.contains("dtshd ma")) tags += DebridStreamAudioTag.DTS_HD_MA
        if (text.contains("dts-hd") || text.contains("dtshd")) tags += DebridStreamAudioTag.DTS_HD
        if (text.contains("dts-es") || text.contains("dtses")) tags += DebridStreamAudioTag.DTS_ES
        if (text.hasToken("dts")) tags += DebridStreamAudioTag.DTS
        if (text.contains("truehd") || text.contains("true hd")) tags += DebridStreamAudioTag.TRUEHD
        if (text.hasToken("opus")) tags += DebridStreamAudioTag.OPUS
        if (text.hasToken("flac")) tags += DebridStreamAudioTag.FLAC
        if (text.hasToken("aac")) tags += DebridStreamAudioTag.AAC
        return tags.distinct().ifEmpty { listOf(DebridStreamAudioTag.UNKNOWN) }
    }

    private fun streamAudioChannels(parsedChannels: List<String>, searchText: String): List<DebridStreamAudioChannel> {
        val text = (parsedChannels + searchText).joinToString(" ").lowercase()
        val channels = mutableListOf<DebridStreamAudioChannel>()
        if (text.hasToken("7.1")) channels += DebridStreamAudioChannel.CH_7_1
        if (text.hasToken("6.1")) channels += DebridStreamAudioChannel.CH_6_1
        if (text.hasToken("5.1") || text.hasToken("6ch")) channels += DebridStreamAudioChannel.CH_5_1
        if (text.hasToken("2.0")) channels += DebridStreamAudioChannel.CH_2_0
        return channels.distinct().ifEmpty { listOf(DebridStreamAudioChannel.UNKNOWN) }
    }

    private fun streamEncode(parsedCodec: String?, searchText: String): DebridStreamEncode {
        val text = listOfNotNull(parsedCodec, searchText).joinToString(" ").lowercase()
        return when {
            text.hasToken("av1") -> DebridStreamEncode.AV1
            text.hasToken("hevc") || text.hasToken("h265") || text.hasToken("x265") -> DebridStreamEncode.HEVC
            text.hasToken("avc") || text.hasToken("h264") || text.hasToken("x264") -> DebridStreamEncode.AVC
            text.hasToken("xvid") -> DebridStreamEncode.XVID
            text.hasToken("divx") -> DebridStreamEncode.DIVX
            else -> DebridStreamEncode.UNKNOWN
        }
    }

    private fun languageFor(value: String): DebridStreamLanguage? {
        val normalized = value.lowercase()
        return DebridStreamLanguage.entries.firstOrNull {
            normalized == it.code || normalized == it.label.lowercase()
        }
    }

    private fun releaseGroupFromText(text: String): String {
        return releaseGroupRegex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun <T> rank(value: T, preferred: List<T>): Int {
        val index = preferred.indexOf(value)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun <T> rankAny(values: List<T>, preferred: List<T>): Int {
        return values.minOfOrNull { rank(it, preferred) } ?: Int.MAX_VALUE
    }

    private fun resolutionRegex(vararg tokens: String): Regex =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|\$)")

    /** The receiver must already be lowercase: every caller builds its text with lowercase(). */
    private fun String.hasToken(token: String): Boolean {
        val regex = tokenRegexCache.getOrPut(token) {
            Regex("(^|[^a-z0-9])${Regex.escape(token.lowercase())}([^a-z0-9]|\$)")
        }
        return regex.containsMatchIn(this)
    }

    private fun String.isDolbyVisionToken(): Boolean {
        val normalized = lowercase().replace(nonAlphanumericRegex, "")
        return normalized == "dv" || normalized == "dovi" || normalized == "dolbyvision"
    }

    private fun String.isHdrToken(): Boolean {
        val normalized = lowercase().replace(nonAlphanumericOrPlusRegex, "")
        return normalized == "hdr" ||
            normalized == "hdr10" ||
            normalized == "hdr10+" ||
            normalized == "hdr10plus" ||
            normalized == "hlg"
    }

    private fun streamSize(stream: Stream): Long? =
        StreamTextSizeParser.effectiveSizeBytes(stream)

    private fun streamSearchText(stream: Stream): String {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.quality,
            resolve?.torrentName,
            resolve?.filename,
            raw?.torrentName,
            raw?.filename,
            stream.debridCacheStatus?.cachedName,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
            parsed?.audio?.joinToString(" ")
        ).joinToString(" ").lowercase()
    }

    private fun sourceName(stream: Stream): String {
        return DebridProviders.instantName(stream.clientResolve?.service)
    }

    private fun Int.gigabytes(): Long = this * 1_000_000_000L

    data class StreamFacts(
        val resolution: DebridStreamResolution,
        val quality: DebridStreamQuality,
        val visualTags: List<DebridStreamVisualTag>,
        val audioTags: List<DebridStreamAudioTag>,
        val audioChannels: List<DebridStreamAudioChannel>,
        val encode: DebridStreamEncode,
        val languages: List<DebridStreamLanguage>,
        val releaseGroup: String,
        val size: Long?,
        val resolutionRank: Int,
        val qualityRank: Int,
        val visualRank: Int,
        val audioRank: Int,
        val channelRank: Int,
        val encodeRank: Int,
        val languageRank: Int
    )
}
