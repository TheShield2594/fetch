package com.fetch.tv.core.player

import com.fetch.tv.core.build.AppFeaturePolicy
import com.fetch.tv.core.streams.ranking.DeviceDecodeCapabilities
import com.fetch.tv.core.streams.ranking.StreamRanker
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.data.local.StreamAutoPlayMode
import com.fetch.tv.data.local.StreamAutoPlaySource
import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamDebridCacheState

object StreamAutoPlaySelector {
    /**
     * The sources auto-play has to hear from before it can pick, when the user limited it to
     * certain addons. Once all of them have answered nothing slower can change the pick, so
     * there is no reason to wait for the rest. Null means any source could still win (no addon
     * limit, plug-ins in play, or plug-ins only), so the caller keeps waiting as before.
     *
     * Media server sources always count: they are never filtered out and they outrank addons.
     */
    fun sourcesNeededForPick(
        source: StreamAutoPlaySource,
        selectedAddons: Set<String>,
        streamAddonNames: Collection<String>,
        serverSourceNames: Collection<String>,
        pluginSourceNames: Collection<String>
    ): Set<String>? {
        if (selectedAddons.isEmpty()) return null
        val effectiveSource = if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY
        } else {
            source
        }
        when (effectiveSource) {
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> return null
            StreamAutoPlaySource.ALL_SOURCES -> if (pluginSourceNames.isNotEmpty()) return null
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> Unit
        }
        val addons = streamAddonNames.filter { it in selectedAddons }
        if (addons.isEmpty()) return null
        return (addons + serverSourceNames).toSet()
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

        val (directDebridEntries, remainingEntries) = streams.partition {
            it.streams.any { stream -> stream.isDirectDebrid() }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries
        val (addonEntries, pluginEntries) = remainingEntries.partition { it.addonName in addonRankByName }
        val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    private fun isPlayable(stream: Stream): Boolean {
        // External URL streams (e.g. error pages, web links) are not playable.
        if (stream.isExternal()) return false
        if (stream.serverTarget != null) return true
        // Streams with a direct URL are always playable regardless of debrid cache status.
        if (stream.getStreamUrl() != null) return true
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED,
            StreamDebridCacheState.UNKNOWN -> return false
            StreamDebridCacheState.CACHED,
            null -> Unit
        }
        return stream.isTorrent() ||
            stream.isDirectDebrid() ||
            // A YouTube id is resolved to a playable URL on the device when the stream is played.
            // Builds without in-app YouTube playback open the watch page instead, an external link.
            (AppFeaturePolicy.inAppTrailerPlaybackEnabled && stream.youTubeIdToResolve() != null)
    }



    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        rankingRules: StreamRankingRules = StreamRankingRules(),
        device: DeviceDecodeCapabilities = DeviceDecodeCapabilities.current
    ): Stream? {
        if (streams.isEmpty()) return null

        val effectiveSource = if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY
        } else {
            source
        }

        val sourceScopedStreams = when (effectiveSource) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.serverTarget != null || it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (stream.serverTarget != null) {
                true
            } else if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
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

        if (mode == StreamAutoPlayMode.MANUAL) return null

        // Stream rules hold in every mode: First stream and Regex pick among what they allow.
        val allowedStreams by lazy { StreamRanker.eligible(candidateStreams, rankingRules, device, ::isPlayable) }
        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM -> allowedStreams.firstOrNull()
            StreamAutoPlayMode.BEST_MATCH ->
                StreamRanker.best(candidateStreams, rankingRules, device, ::isPlayable)?.stream
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()
 
                // Try to compile the user regex
                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                if (userRegex == null) return null

                // Auto-extract exclusion patterns from negative lookaheads
                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex("\\b(${exclusionWords.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
                } else null

                // 1. Build list of ALL regex‑matching streams
                val matchingStreams = allowedStreams.filter { stream ->
                    if (!isPlayable(stream)) return@filter false

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.title.orEmpty()).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(stream.getStreamUrl().orEmpty())
                        if (stream.isTorrent()) append(' ').append(stream.infoHash.orEmpty())
                    }

                    // Must match include pattern
                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    // Must NOT match exclusion pattern
                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }

                if (matchingStreams.isEmpty()) return null
                matchingStreams.firstOrNull { isPlayable(it) }
            }

        }
    }
}
