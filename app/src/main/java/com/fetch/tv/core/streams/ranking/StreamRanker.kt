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
    val softwareAudio: Set<AudioFormat> = AudioFormat.entries.toSet(),
    /**
     * True on devices known to show a black screen for Dolby Vision files that also carry
     * HDR10+ metadata (Fire TV Stick 4K, NuvioMedia/NuvioTV#3996). Such files are kept out
     * of auto-play; they stay listed under "Other sources".
     */
    val dolbyVisionWithHdr10PlusBroken: Boolean = false
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
    val preferredKeywords: List<String> = emptyList(),
    /** Streams smaller than this are dropped; null means no minimum. */
    val minSizeGb: Double? = null,
    val excludedResolutions: Set<StreamResolution> = emptySet(),
    val excludedSources: Set<ReleaseSource> = emptySet(),
    val excludedCodecs: Set<VideoCodec> = emptySet(),
    /** A stream is dropped when every audio format it names is in here. */
    val excludedAudio: Set<AudioFormat> = emptySet(),
    /** Audio languages, most wanted first. */
    val preferredLanguages: List<StreamLanguage> = emptyList(),
    /** When set, a stream that names its languages must include one of these. */
    val requiredLanguages: Set<StreamLanguage> = emptySet(),
    /** A stream is dropped when every language it names is in here. */
    val excludedLanguages: Set<StreamLanguage> = emptySet(),
    /** Release groups, case-insensitive, e.g. a group picked with "Prefer this next time". */
    val preferredReleaseGroups: List<String> = emptyList(),
    val excludedReleaseGroups: List<String> = emptyList(),
    /** Addons, case-insensitive, whose streams rank higher, e.g. one picked with "Prefer this addon". */
    val preferredAddons: List<String> = emptyList(),
    /** Keep at most this many eligible streams per resolution; 0 means no limit. */
    val maxPerResolution: Int = 0,
    /** Keep at most this many eligible streams per release source (WEB-DL, BluRay…); 0 means no limit. */
    val maxPerSource: Int = 0,
    /** Keep at most this many eligible streams in total; 0 means no limit. */
    val maxResults: Int = 0
)

enum class RankReason {
    OWN_LIBRARY,
    DEBRID_CACHED,
    PREFERRED_RESOLUTION,
    PREFERRED_CODEC,
    HDR,
    PREFERRED_AUDIO,
    PREFERRED_KEYWORD,
    PREFERRED_LANGUAGE,
    PREFERRED_RELEASE_GROUP,
    PREFERRED_ADDON,
    OVER_PREFERRED_SIZE,
    LOW_QUALITY_SOURCE,
    UNKNOWN_QUALITY
}

enum class ExclusionReason {
    ABOVE_MAX_RESOLUTION,
    BELOW_MIN_RESOLUTION,
    OVER_MAX_SIZE,
    UNDER_MIN_SIZE,
    RESOLUTION_EXCLUDED,
    SOURCE_EXCLUDED,
    CODEC_EXCLUDED,
    AUDIO_EXCLUDED,
    LANGUAGE_EXCLUDED,
    RELEASE_GROUP_EXCLUDED,
    OVER_RESULT_LIMIT,
    DOLBY_VISION_PROFILE_EXCLUDED,
    DOLBY_VISION_EXCLUDED,
    HDR_EXCLUDED,
    CAM_OR_TELESYNC,
    THREE_D,
    EXCLUDED_KEYWORD,
    UNSUPPORTED_CODEC,
    UNSUPPORTED_DOLBY_VISION,
    UNSUPPORTED_DOLBY_VISION_HDR10_PLUS,
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
        val ranked = streams.mapIndexed { index, stream ->
            score(stream, parse(stream), rules, device, isPlayable(stream), index)
        }.sortedWith(order)
        return if (rules.hasLimits) applyLimits(ranked, rules).sortedWith(order) else ranked
    }

    private val order = compareBy<RankedStream> { if (it.isEligible) 0 else 1 }
        .thenByDescending { it.score }
        .thenBy { it.originalIndex }

    private val StreamRankingRules.hasLimits: Boolean
        get() = maxResults > 0 || maxPerResolution > 0 || maxPerSource > 0

    /**
     * Moves eligible streams past the user's limits, best first, to "other sources". Files
     * from the user's own library are never limited.
     */
    private fun applyLimits(ranked: List<RankedStream>, rules: StreamRankingRules): List<RankedStream> {
        val perResolution = mutableMapOf<StreamResolution, Int>()
        val perSource = mutableMapOf<ReleaseSource, Int>()
        var kept = 0
        return ranked.map { item ->
            if (!item.isEligible || item.stream.serverTarget != null) return@map item
            val resolution = item.descriptor.resolution
            val source = item.descriptor.source
            val overLimit = (rules.maxResults > 0 && kept >= rules.maxResults) ||
                (rules.maxPerResolution > 0 && (perResolution[resolution] ?: 0) >= rules.maxPerResolution) ||
                (rules.maxPerSource > 0 && (perSource[source] ?: 0) >= rules.maxPerSource)
            if (overLimit) return@map item.copy(exclusion = ExclusionReason.OVER_RESULT_LIMIT)
            kept++
            perResolution[resolution] = (perResolution[resolution] ?: 0) + 1
            perSource[source] = (perSource[source] ?: 0) + 1
            item
        }
    }

    /**
     * [rank] for a source list: streams the rules allow first and ruled-out ones last, with
     * why each ruled-out one would not auto-play, keyed by [Stream.stableKey]. Allowed streams
     * are best first when [bestFirst], otherwise in the order the addons gave them.
     */
    fun rankedList(
        streams: List<Stream>,
        rules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.Permissive,
        bestFirst: Boolean = true
    ): RankedStreamList {
        val ranked = rank(streams, rules, device).let { ranked ->
            if (bestFirst) ranked else ranked.sortedWith(compareBy<RankedStream> { if (it.isEligible) 0 else 1 }.thenBy { it.originalIndex })
        }
        return RankedStreamList(
            streams = ranked.map { it.stream },
            exclusions = ranked.mapNotNull { item -> item.exclusion?.let { item.stream.stableKey() to it } }.toMap()
        )
    }

    /** [streams] the rules and the device allow, in their original order. */
    fun eligible(
        streams: List<Stream>,
        rules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.Permissive,
        isPlayable: (Stream) -> Boolean = { true }
    ): List<Stream> =
        rank(streams, rules, device, isPlayable).filter { it.isEligible }.sortedBy { it.originalIndex }.map { it.stream }

    /** The stream to start immediately when the user presses play, if any qualifies. */
    fun best(
        streams: List<Stream>,
        rules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.Permissive,
        isPlayable: (Stream) -> Boolean = { true }
    ): RankedStream? = rank(streams, rules, device, isPlayable).firstOrNull { it.isEligible }

    /**
     * The ruled-out stream in [ranked] that would have looked better than [picked]: a higher
     * resolution, or Dolby Vision or HDR the pick lacks. Null when nothing better was passed
     * over, or only for reasons that don't say anything about the picture (a results limit,
     * a cam copy, a link the player can't open).
     */
    fun skippedBetterLooking(ranked: List<RankedStream>, picked: Stream): RankedStream? {
        val pickedTier = ranked.firstOrNull { it.stream === picked }?.descriptor?.pictureTier ?: return null
        return ranked
            .filter { it.exclusion != null && it.exclusion !in reasonsNotAboutPicture }
            // A cam copy can be ruled out for another reason first (a 4K cam over the size cap).
            .filter { it.descriptor.source != ReleaseSource.CAM && it.descriptor.source != ReleaseSource.TELESYNC }
            .filter { it.descriptor.resolution != StreamResolution.UNKNOWN && it.descriptor.pictureTier > pickedTier }
            .maxByOrNull { it.descriptor.pictureTier }
    }

    private val reasonsNotAboutPicture = setOf(
        ExclusionReason.OVER_RESULT_LIMIT,
        ExclusionReason.CAM_OR_TELESYNC,
        ExclusionReason.NOT_PLAYABLE
    )

    /** Resolution first, then Dolby Vision over other HDR over SDR at the same resolution. */
    private val StreamDescriptor.pictureTier: Int
        get() = resolution.height * 10 + when {
            hasDolbyVision -> 2
            isHdr -> 1
            else -> 0
        }

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

        val languageRank = descriptor.languages
            .map { rules.preferredLanguages.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (languageRank != null) {
            score += 300 - 60 * languageRank
            reasons += RankReason.PREFERRED_LANGUAGE
        }

        if (descriptor.releaseGroup != null &&
            rules.preferredReleaseGroups.any { it.equals(descriptor.releaseGroup, ignoreCase = true) }
        ) {
            score += 300
            reasons += RankReason.PREFERRED_RELEASE_GROUP
        }

        val addonName = stream.addonName.trim()
        if (addonName.isNotEmpty() && rules.preferredAddons.any { it.trim().equals(addonName, ignoreCase = true) }) {
            score += 300
            reasons += RankReason.PREFERRED_ADDON
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
            if (sizeGb != null && rules.minSizeGb != null && sizeGb < rules.minSizeGb) {
                return ExclusionReason.UNDER_MIN_SIZE
            }
            if (known && descriptor.resolution in rules.excludedResolutions) return ExclusionReason.RESOLUTION_EXCLUDED
            if (descriptor.source != ReleaseSource.UNKNOWN && descriptor.source in rules.excludedSources) {
                return ExclusionReason.SOURCE_EXCLUDED
            }
            if (descriptor.videoCodec != VideoCodec.UNKNOWN && descriptor.videoCodec in rules.excludedCodecs) {
                return ExclusionReason.CODEC_EXCLUDED
            }
            if (descriptor.audio.isNotEmpty() && rules.excludedAudio.containsAll(descriptor.audio)) {
                return ExclusionReason.AUDIO_EXCLUDED
            }
            if (!languagesAllowed(descriptor.languages, rules)) return ExclusionReason.LANGUAGE_EXCLUDED
            if (descriptor.releaseGroup != null &&
                rules.excludedReleaseGroups.any { it.equals(descriptor.releaseGroup, ignoreCase = true) }
            ) {
                return ExclusionReason.RELEASE_GROUP_EXCLUDED
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
            if (device.dolbyVisionWithHdr10PlusBroken && descriptor.hasDolbyVision &&
                HdrFormat.HDR10_PLUS in descriptor.hdr
            ) {
                return ExclusionReason.UNSUPPORTED_DOLBY_VISION_HDR10_PLUS
            }
            // The server converts audio the TV can't play, so only streamed files are ruled out.
            if (!ownLibrary && descriptor.audio.isNotEmpty() && descriptor.audio.none(device::canPlay)) {
                return ExclusionReason.UNSUPPORTED_AUDIO
            }
        }
        return null
    }

    /** Releases that don't name their languages are never ruled out by language rules. */
    private fun languagesAllowed(languages: Set<StreamLanguage>, rules: StreamRankingRules): Boolean {
        if (languages.isEmpty()) return true
        if (rules.requiredLanguages.isNotEmpty() && languages.none { it in rules.requiredLanguages || it == StreamLanguage.MULTI }) {
            return false
        }
        return !rules.excludedLanguages.containsAll(languages)
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
