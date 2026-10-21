package com.fetch.tv.core.streams.ranking

import androidx.compose.runtime.Immutable
import com.fetch.tv.domain.model.Stream
import kotlinx.serialization.Serializable

/**
 * What the current device can actually decode and output. Built once per session by
 * [AndroidDeviceDecodeCapabilities]; a plain data class so ranking stays unit-testable.
 */
@Immutable
data class DeviceDecodeCapabilities(
    val maxResolution: StreamResolution = StreamResolution.UHD_2160,
    val decodableCodecs: Set<VideoCodec> = setOf(VideoCodec.AVC, VideoCodec.HEVC, VideoCodec.VP9),
    val hdrFormats: Set<HdrFormat> = emptySet(),
    /** Dolby Vision profiles a hardware decoder advertises (empty when DV is unsupported). */
    val dolbyVisionProfiles: Set<Int> = emptySet(),
    /** Formats the audio output can pass through untouched (HDMI/eARC to a receiver). */
    val passthroughAudio: Set<AudioFormat> = emptySet(),
    /** Passthrough-only formats the FFmpeg extension can still decode to PCM. */
    val softwareAudio: Set<AudioFormat> = AudioFormat.entries.toSet()
) {
    fun canDecode(codec: VideoCodec): Boolean =
        codec == VideoCodec.UNKNOWN || codec in decodableCodecs

    /**
     * Lossless formats like TrueHD and DTS-HD need passthrough or a software decoder. DTS-HD
     * and DTS:X carry a DTS core, which a receiver that only takes DTS still plays.
     */
    fun canPlay(audio: AudioFormat): Boolean =
        !audio.isPassthroughOnly ||
            audio in passthroughAudio ||
            audio in softwareAudio ||
            (audio.hasDtsCore && AudioFormat.DTS in passthroughAudio)

    companion object {
        /**
         * Process-wide capabilities, filled in by [AndroidDeviceDecodeCapabilities] at startup.
         * Until then ranking assumes a capable device rather than hiding streams.
         */
        @Volatile
        var current: DeviceDecodeCapabilities = DeviceDecodeCapabilities()

        /** Assumes a capable device; used when capability probing is unavailable (tests, previews). */
        val Permissive = DeviceDecodeCapabilities(
            decodableCodecs = VideoCodec.entries.toSet(),
            hdrFormats = HdrFormat.entries.toSet(),
            dolbyVisionProfiles = setOf(5, 7, 8)
        )
    }
}

/**
 * User preferences for picking a stream, e.g. "prefer 1080p HEVC under 15 GB,
 * never Dolby Vision profile 5". Defaults are chosen so most people never touch them.
 */
@Serializable
@Immutable
data class StreamRankingRules(
    /** The resolution to aim for; higher resolutions are still allowed but not preferred. */
    val preferredResolution: StreamResolution = StreamResolution.UHD_2160,
    /** Streams above this resolution are dropped entirely. */
    val maxResolution: StreamResolution = StreamResolution.UHD_2160,
    val minResolution: StreamResolution = StreamResolution.UNKNOWN,
    val preferredCodecs: List<VideoCodec> = emptyList(),
    val preferHdr: Boolean = true,
    /** Streams larger than this are dropped; null means no cap. */
    val maxSizeGb: Double? = null,
    /** Streams larger than this are still allowed but ranked lower; null means no preference. */
    val preferredMaxSizeGb: Double? = null,
    val excludedDolbyVisionProfiles: Set<Int> = emptySet(),
    val excludeDolbyVision: Boolean = false,
    val excludeHdr: Boolean = false,
    val excludeCamAndTelesync: Boolean = true,
    val exclude3d: Boolean = true,
    val preferredAudio: List<AudioFormat> = emptyList(),
    val preferCachedDebrid: Boolean = true,
    /** Files from the user's own Jellyfin/Emby/Plex library beat anything streamed. */
    val preferOwnLibrary: Boolean = true,
    /** Drop streams the device can't decode instead of merely ranking them last. */
    val dropUndecodable: Boolean = true,
    /** Case-insensitive keywords; a match drops the stream (e.g. "HC", "KORSUB"). */
    val excludedKeywords: List<String> = emptyList(),
    val preferredKeywords: List<String> = emptyList()
)

enum class RankReason {
    OWN_LIBRARY,
    DEBRID_CACHED,
    PREFERRED_RESOLUTION,
    PREFERRED_CODEC,
    HDR,
    PREFERRED_AUDIO,
    PREFERRED_KEYWORD,
    OVER_PREFERRED_SIZE,
    LOW_QUALITY_SOURCE,
    UNKNOWN_QUALITY
}

enum class ExclusionReason {
    ABOVE_MAX_RESOLUTION,
    BELOW_MIN_RESOLUTION,
    OVER_MAX_SIZE,
    DOLBY_VISION_PROFILE_EXCLUDED,
    DOLBY_VISION_EXCLUDED,
    HDR_EXCLUDED,
    CAM_OR_TELESYNC,
    THREE_D,
    EXCLUDED_KEYWORD,
    UNSUPPORTED_CODEC,
    UNSUPPORTED_DOLBY_VISION,
    UNSUPPORTED_RESOLUTION,
    UNSUPPORTED_AUDIO,
    NOT_PLAYABLE
}

@Immutable
data class RankedStream(
    val stream: Stream,
    val descriptor: StreamDescriptor,
    val score: Int,
    val reasons: List<RankReason>,
    /** Non-null when rules or device limits rule this stream out; it is kept for "Other sources". */
    val exclusion: ExclusionReason? = null,
    /** Original position in the addon list, used to keep ordering stable on ties. */
    val originalIndex: Int
) {
    val isEligible: Boolean get() = exclusion == null
}

@Immutable
data class RankedStreamList(
    val streams: List<Stream>,
    val exclusions: Map<String, ExclusionReason>
) {
    fun exclusionOf(stream: Stream): ExclusionReason? =
        if (exclusions.isEmpty()) null else exclusions[stream.stableKey()]
}

/**
 * Scores streams against [StreamRankingRules] and [DeviceDecodeCapabilities].
 *
 * The result is a full ordering: eligible streams first (best first), then excluded
 * ones, so the picker can still offer every source under "Other sources".
 */
object StreamRanker {

    fun rank(
        streams: List<Stream>,
        rules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.Permissive,
        isPlayable: (Stream) -> Boolean = { true },
        parse: (Stream) -> StreamDescriptor = StreamTitleParser::parse
    ): List<RankedStream> {
        return streams.mapIndexed { index, stream ->
            score(stream, parse(stream), rules, device, isPlayable(stream), index)
        }.sortedWith(
            compareBy<RankedStream> { if (it.isEligible) 0 else 1 }
                .thenByDescending { it.score }
                .thenBy { it.originalIndex }
        )
    }

    /**
     * [rank] for a source list: streams best first, and why each ruled-out one would not
     * auto-play, keyed by [Stream.stableKey].
     */
    fun rankedList(
        streams: List<Stream>,
        rules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.Permissive
    ): RankedStreamList {
        val ranked = rank(streams, rules, device)
        return RankedStreamList(
            streams = ranked.map { it.stream },
            exclusions = ranked.mapNotNull { item -> item.exclusion?.let { item.stream.stableKey() to it } }.toMap()
        )
    }

    /** The stream to start immediately when the user presses play, if any qualifies. */
    fun best(
        streams: List<Stream>,
        rules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.Permissive,
        isPlayable: (Stream) -> Boolean = { true }
    ): RankedStream? = rank(streams, rules, device, isPlayable).firstOrNull { it.isEligible }

    internal fun score(
        stream: Stream,
        descriptor: StreamDescriptor,
        rules: StreamRankingRules,
        device: DeviceDecodeCapabilities,
        playable: Boolean,
        index: Int
    ): RankedStream {
        val reasons = mutableListOf<RankReason>()
        var score = 0
        val ownLibrary = stream.serverTarget != null
        val searchable = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
            .joinToString(" ")

        val exclusion = exclusionFor(stream, descriptor, rules, device, playable, ownLibrary, searchable)

        if (ownLibrary && rules.preferOwnLibrary) {
            score += 10_000
            reasons += RankReason.OWN_LIBRARY
        }
        if (descriptor.isDebridCached && rules.preferCachedDebrid) {
            score += 2_000
            reasons += RankReason.DEBRID_CACHED
        }

        // Resolution: full marks at the preferred height, falling off on either side.
        val resolution = descriptor.resolution
        if (resolution == StreamResolution.UNKNOWN) {
            score += 150
            reasons += RankReason.UNKNOWN_QUALITY
        } else {
            val preferred = rules.preferredResolution.height.coerceAtMost(device.maxResolution.height)
            val distance = resolutionSteps(resolution.height, preferred)
            score += when {
                distance == 0 -> 1_000
                resolution.height > preferred -> 800 - 120 * (distance - 1)
                else -> 700 - 200 * (distance - 1)
            }.coerceAtLeast(0)
            if (distance == 0) reasons += RankReason.PREFERRED_RESOLUTION
        }

        val codecRank = rules.preferredCodecs.indexOf(descriptor.videoCodec)
        if (codecRank >= 0) {
            score += 300 - 60 * codecRank
            reasons += RankReason.PREFERRED_CODEC
        }

        if (rules.preferHdr && descriptor.isHdr && supportsAnyHdr(descriptor, device)) {
            score += 250
            reasons += RankReason.HDR
        }

        val audioRank = descriptor.audio
            .map { rules.preferredAudio.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (audioRank != null) {
            score += 150 - 30 * audioRank
            reasons += RankReason.PREFERRED_AUDIO
        } else if (descriptor.audio.any { it.isLossless && it in device.passthroughAudio }) {
            score += 60
        }

        if (rules.preferredKeywords.any { searchable.contains(it, ignoreCase = true) }) {
            score += 200
            reasons += RankReason.PREFERRED_KEYWORD
        }

        score += descriptor.source.qualityRank * 20
        if (descriptor.source.qualityRank <= ReleaseSource.TELESYNC.qualityRank) {
            reasons += RankReason.LOW_QUALITY_SOURCE
        }

        val sizeGb = descriptor.sizeBytes?.let { it / 1_073_741_824.0 }
        val preferredMax = rules.preferredMaxSizeGb
        if (sizeGb != null && preferredMax != null && sizeGb > preferredMax) {
            // Lose points proportionally to how far over the preference the file is.
            score -= (((sizeGb - preferredMax) / preferredMax) * 400).toInt().coerceIn(50, 600)
            reasons += RankReason.OVER_PREFERRED_SIZE
        }

        descriptor.seeders?.let { seeders ->
            if (!descriptor.isDebridCached) score += (seeders.coerceAtMost(500) / 10)
        }

        return RankedStream(
            stream = stream,
            descriptor = descriptor,
            score = score,
            reasons = reasons,
            exclusion = exclusion,
            originalIndex = index
        )
    }

    private fun exclusionFor(
        stream: Stream,
        descriptor: StreamDescriptor,
        rules: StreamRankingRules,
        device: DeviceDecodeCapabilities,
        playable: Boolean,
        ownLibrary: Boolean,
        searchable: String
    ): ExclusionReason? {
        if (!playable) return ExclusionReason.NOT_PLAYABLE
        val height = descriptor.resolution.height
        val known = descriptor.resolution != StreamResolution.UNKNOWN

        // A file the user owns is never hidden by taste rules, only by what the device can play.
        if (!ownLibrary) {
            if (known && height > rules.maxResolution.height) return ExclusionReason.ABOVE_MAX_RESOLUTION
            if (known && height < rules.minResolution.height) return ExclusionReason.BELOW_MIN_RESOLUTION
            val sizeGb = descriptor.sizeBytes?.let { it / 1_073_741_824.0 }
            if (sizeGb != null && rules.maxSizeGb != null && sizeGb > rules.maxSizeGb) {
                return ExclusionReason.OVER_MAX_SIZE
            }
            if (rules.excludeDolbyVision && descriptor.hasDolbyVision) return ExclusionReason.DOLBY_VISION_EXCLUDED
            val dvProfile = likelyDolbyVisionProfile(descriptor)
            if (dvProfile != null && dvProfile in rules.excludedDolbyVisionProfiles) {
                return ExclusionReason.DOLBY_VISION_PROFILE_EXCLUDED
            }
            if (rules.excludeHdr && descriptor.isHdr) return ExclusionReason.HDR_EXCLUDED
            if (rules.excludeCamAndTelesync &&
                (descriptor.source == ReleaseSource.CAM || descriptor.source == ReleaseSource.TELESYNC)
            ) {
                return ExclusionReason.CAM_OR_TELESYNC
            }
            if (rules.exclude3d && descriptor.is3d) return ExclusionReason.THREE_D
            if (rules.excludedKeywords.any { it.isNotBlank() && containsWord(searchable, it) }) {
                return ExclusionReason.EXCLUDED_KEYWORD
            }
        }

        if (rules.dropUndecodable) {
            if (!device.canDecode(descriptor.videoCodec)) return ExclusionReason.UNSUPPORTED_CODEC
            if (known && height > device.maxResolution.height) return ExclusionReason.UNSUPPORTED_RESOLUTION
            if (!canPlayDolbyVision(descriptor, device)) return ExclusionReason.UNSUPPORTED_DOLBY_VISION
            // The server converts audio the TV can't play, so only streamed files are ruled out.
            if (!ownLibrary && descriptor.audio.isNotEmpty() && descriptor.audio.none(device::canPlay)) {
                return ExclusionReason.UNSUPPORTED_AUDIO
            }
        }
        return null
    }

    /**
     * Profile 5 has no HDR10/SDR fallback layer, so a device without a DV decoder shows
     * it with wrong (green/purple) colours. Profiles 7 and 8 carry a base layer the
     * player can fall back to, so they stay eligible everywhere.
     */
    private fun canPlayDolbyVision(descriptor: StreamDescriptor, device: DeviceDecodeCapabilities): Boolean {
        if (!descriptor.hasDolbyVision) return true
        if (likelyDolbyVisionProfile(descriptor) != 5) return true
        return 5 in device.dolbyVisionProfiles
    }

    /** "DV" with no HDR10/HDR10+ alongside it is almost always a single-layer profile 5 release. */
    internal fun likelyDolbyVisionProfile(descriptor: StreamDescriptor): Int? =
        descriptor.dolbyVisionProfile
            ?: if (descriptor.hasDolbyVision && descriptor.hdr == setOf(HdrFormat.DOLBY_VISION)) 5 else null

    private fun supportsAnyHdr(descriptor: StreamDescriptor, device: DeviceDecodeCapabilities): Boolean =
        descriptor.hdr.any { format ->
            when (format) {
                HdrFormat.DOLBY_VISION -> device.dolbyVisionProfiles.isNotEmpty() ||
                    // DV 7/8 fall back to their HDR10 base layer.
                    (likelyDolbyVisionProfile(descriptor) != 5 && HdrFormat.HDR10 in device.hdrFormats)
                HdrFormat.HDR10_PLUS -> HdrFormat.HDR10_PLUS in device.hdrFormats || HdrFormat.HDR10 in device.hdrFormats
                else -> format in device.hdrFormats
            }
        }

    private val ladder = StreamResolution.entries
        .filter { it != StreamResolution.UNKNOWN }
        .map { it.height }
        .sortedDescending()

    private fun resolutionSteps(height: Int, preferred: Int): Int {
        val a = ladder.indexOfFirst { it <= height }.takeIf { it >= 0 } ?: ladder.lastIndex
        val b = ladder.indexOfFirst { it <= preferred }.takeIf { it >= 0 } ?: ladder.lastIndex
        return kotlin.math.abs(a - b)
    }

    private fun containsWord(text: String, word: String): Boolean =
        Regex("(?<![A-Za-z0-9])${Regex.escape(word.trim())}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
}
