package com.fetch.tv.core.player

import com.fetch.tv.core.build.AppFeaturePolicy
import com.fetch.tv.core.streams.ranking.DeviceDecodeCapabilities
import com.fetch.tv.core.streams.ranking.RankedStream
import com.fetch.tv.core.streams.ranking.StreamRanker
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import com.fetch.tv.data.local.StreamAutoPlayMode
import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.Stream

object StreamAutoPlaySelector {
    /**
     * The sources auto-play has to hear from before it can pick, when the user limited it to
     * certain addons. Once all of them have answered nothing slower can change the pick, so
     * there is no reason to wait for the rest. Null means any source could still win (no addon
     * limit), so the caller keeps waiting as before.
     *
     * Media server sources always count: they are never filtered out and they outrank addons.
     */
    fun sourcesNeededForPick(
        selectedAddons: Set<String>,
        streamAddonNames: Collection<String>,
        serverSourceNames: Collection<String>
    ): Set<String>? {
        if (selectedAddons.isEmpty()) return null
        val addons = streamAddonNames.filter { it in selectedAddons }
        if (addons.isEmpty()) return null
        return (addons + serverSourceNames).toSet()
    }

    /**
     * For Best match with "Start without waiting" on: whether the best stream found so far is
     * good enough to play without waiting for slower sources. It has to reach the preferred
     * quality (never above the maximum) and, when cached debrid streams are preferred, not be
     * marked as still downloading ("[RD download]", ⏳). An unmarked link (HTTP, usenet,
     * self-hosted) plays as it is. A bare torrent never qualifies.
     */
    fun isGoodEnoughToStartEarly(stream: Stream, rankingRules: StreamRankingRules): Boolean {
        if (stream.isTorrent()) return false
        val descriptor = StreamTitleParser.parse(stream)
        val target = minOf(rankingRules.preferredResolution.height, rankingRules.maxResolution.height)
        if (descriptor.resolution.height == 0 || descriptor.resolution.height < target) return false
        if (stream.serverTarget != null) return true
        return !rankingRules.preferCachedDebrid || !descriptor.isDebridUncached
    }

    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>,
        preferredNames: Set<String> = emptySet()
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams
        if (preferredNames.isNotEmpty()) {
            val (preferred, rest) = streams.partition { it.addonName in preferredNames }
            return preferred + orderAddonStreams(rest, installedOrder)
        }

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        if (installedOrder.isEmpty()) return streams
        val (addonEntries, otherEntries) = streams.partition { it.addonName in addonRankByName }
        val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
        return orderedAddons + otherEntries
    }

    private fun isPlayable(stream: Stream): Boolean {
        // External URL streams (e.g. error pages, web links) are not playable.
        if (stream.isExternal()) return false
        if (stream.serverTarget != null) return true
        if (stream.getStreamUrl() != null) return true
        // A YouTube id is resolved to a playable URL on the device when the stream is played.
        // Builds without in-app YouTube playback open the watch page instead, an external link.
        return AppFeaturePolicy.inAppTrailerPlaybackEnabled && stream.youTubeIdToResolve() != null
    }

    /** The streams auto-play may choose from once the addon limit applies. */
    private fun candidates(
        streams: List<Stream>,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>
    ): List<Stream> {
        if (selectedAddons.isEmpty()) return streams
        return streams.filter { stream ->
            stream.serverTarget != null ||
                stream.addonName !in installedAddonNames ||
                stream.addonName in selectedAddons
        }
    }

    /**
     * For Best match: the stream among the same candidates that would have looked better than
     * [picked] but was ruled out, with the reason on it. See [StreamRanker.skippedBetterLooking].
     */
    fun skippedBetterLooking(
        streams: List<Stream>,
        picked: Stream,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        rankingRules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.current
    ): RankedStream? {
        val candidateStreams = candidates(streams, installedAddonNames, selectedAddons)
        val ranked = StreamRanker.rank(candidateStreams, rankingRules, device, ::isPlayable)
        return StreamRanker.skippedBetterLooking(ranked, picked)
    }

    /**
     * The stream to try after the one playing failed to open: the next one in the order the
     * sources panel lists them (best first under Best match, otherwise the addons' order),
     * among those the rules allow and auto-play may pick from. [failed] are the streams already
     * tried for this title. Null when nothing is left.
     */
    fun nextAfterFailure(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        failed: (Stream) -> Boolean,
        regexPattern: String = "",
        rankingRules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.current
    ): Stream? {
        val candidateStreams = candidates(streams, installedAddonNames, selectedAddons)
        // Ranked before the failed ones are dropped, so result limits stay where they were.
        val eligible = StreamRanker.rank(candidateStreams, rankingRules, device, ::isPlayable).filter { it.isEligible }
        val ordered = if (mode == StreamAutoPlayMode.BEST_MATCH) eligible else eligible.sortedBy { it.originalIndex }
        // Regex match keeps to the streams its pattern allows, as when it picked the first one.
        val matches = if (mode == StreamAutoPlayMode.REGEX_MATCH) regexFilter(regexPattern) ?: return null else null
        return ordered.firstOrNull { !failed(it.stream) && (matches == null || matches(it.stream)) }?.stream
    }

    /**
     * A leading "exclude these words" lookahead, as in `^(?!.*\b(cam|ts)\b).*$`, optionally after
     * inline flags like `(?is)`. Only a flat word list right after the `^` counts: a lookahead
     * elsewhere in the pattern is positional, and a nested group can't be split into words.
     */
    private val LEADING_EXCLUSION = Regex("^(?:\\(\\?[a-zA-Z]+\\))?\\^\\(\\?![^()]*?\\(([^()]+)\\)")

    /**
     * The Regex match test for one stream: the pattern, plus its leading negative-lookahead words
     * applied across the whole text (the pattern's `.` stops at a line break, so the lookahead
     * alone would only check the first line). Null when the pattern won't compile.
     */
    private fun regexFilter(regexPattern: String): ((Stream) -> Boolean)? {
        val pattern = regexPattern.trim()
        val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        val exclusionWords = LEADING_EXCLUSION.find(pattern)
            ?.groupValues?.get(1)
            ?.split("|")
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        // Words are matched literally: they were written as part of a larger pattern, so on their
        // own they may not compile, and a bad user pattern must never crash auto-play.
        val excludeRegex = if (exclusionWords.isNotEmpty()) {
            runCatching {
                Regex(
                    "(?<![A-Za-z0-9])(${exclusionWords.joinToString("|") { Regex.escape(it) }})(?![A-Za-z0-9])",
                    RegexOption.IGNORE_CASE
                )
            }.getOrNull()
        } else null
        return { stream ->
            val searchableText = buildString {
                append(stream.addonName).append(' ')
                append(stream.name.orEmpty()).append(' ')
                append(stream.title.orEmpty()).append(' ')
                append(stream.description.orEmpty()).append(' ')
                append(stream.getStreamUrl().orEmpty())
            }
            userRegex.containsMatchIn(searchableText) &&
                (excludeRegex == null || !excludeRegex.containsMatchIn(searchableText))
        }
    }

    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        rankingRules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.current
    ): Stream? {
        if (streams.isEmpty()) return null

        val candidateStreams = candidates(streams, installedAddonNames, selectedAddons)
        if (candidateStreams.isEmpty()) return null

        // Binge group matching takes priority over mode and source filters — even in MANUAL mode,
        // a persisted binge group should auto-play without showing the picker.
        // Search ALL streams, not just candidateStreams, since binge group reuse should
        // not be limited by autoplay source/addon selection filters.
        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            val bingeGroupMatch = streams.firstOrNull { stream ->
                !stream.isExternal() &&
                    stream.behaviorHints?.bingeGroup == targetBingeGroup &&
                    isPlayable(stream)
            }
            if (bingeGroupMatch != null) return bingeGroupMatch
            // When bingeGroupOnly is set (MANUAL mode with only binge-group
            // preference enabled), don't fall back to a non-matching stream —
            // return null so the caller shows the stream picker instead.
            if (bingeGroupOnly) return null
        }

        if (bingeGroupOnly) return null

        // Stream rules hold in every mode: First stream and Regex pick among what they allow.
        val allowedStreams by lazy { StreamRanker.eligible(candidateStreams, rankingRules, device, ::isPlayable) }
        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM -> allowedStreams.firstOrNull()
            StreamAutoPlayMode.BEST_MATCH ->
                StreamRanker.best(candidateStreams, rankingRules, device, ::isPlayable)?.stream
            StreamAutoPlayMode.REGEX_MATCH -> {
                val matches = regexFilter(regexPattern) ?: return null
                allowedStreams.firstOrNull(matches)
            }
        }
    }
}
