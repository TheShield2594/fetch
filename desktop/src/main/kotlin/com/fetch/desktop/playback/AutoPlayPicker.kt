package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.pickSource
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.tv.core.streams.ranking.RankedStream
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import com.fetch.tv.domain.model.Stream
import kotlinx.serialization.Serializable

/**
 * A show's "binge group" (an addon's `behaviorHints.bingeGroup`: the same addon, quality and
 * release), with the file name and info hash of the episode that played, as the TV keeps it.
 */
@Serializable
data class BingeHint(val group: String, val filename: String? = null, val infoHash: String? = null) {
    companion object {
        fun of(stream: Stream): BingeHint? {
            val group = stream.behaviorHints?.bingeGroup?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return BingeHint(group, stream.behaviorHints?.filename, stream.infoHash)
        }
    }
}

/** What auto-play does with the sources in so far. */
sealed interface AutoPlayDecision {
    data class Play(val pick: RankedStream) : AutoPlayDecision
    /** Keep waiting for more addons. */
    data object Wait : AutoPlayDecision
    /** Nothing to start automatically: show the sources. */
    data object ShowSources : AutoPlayDecision
}

/** The TV's `StreamAutoPlaySelector` and its waiting rules, over the desktop's ranked sources. */
object AutoPlayPicker {

    /**
     * "Allowed addons for autoplay" (the TV's `candidates`): with addons chosen, auto-play only
     * picks their sources. Your own media servers always count, and so do sources from addons
     * that aren't installed (YouTube). Nothing chosen means every addon. Choosing a source by
     * hand isn't limited.
     */
    fun allowed(ranked: List<RankedStream>, installedAddonNames: Set<String>, allowedAddons: Set<String>): List<RankedStream> {
        if (allowedAddons.isEmpty()) return ranked
        return ranked.filter { r ->
            r.stream.serverTarget != null || r.stream.addonName !in installedAddonNames || r.stream.addonName in allowedAddons
        }
    }

    /**
     * The TV's `sourcesNeededForPick`: with addons chosen, auto-play needs only their answers
     * (and the media servers'), not every addon's. Null when it waits for all of them: nothing
     * chosen, or none of the chosen addons was asked.
     */
    fun addonsNeededForPick(askedAddonNames: Collection<String>, allowedAddons: Set<String>): Set<String>? {
        if (allowedAddons.isEmpty()) return null
        return askedAddonNames.filter { it in allowedAddons }.toSet().takeIf { it.isNotEmpty() }
    }

    /**
     * "Start without waiting": the best so far reaches the preferred quality (never above the
     * maximum) and, when cached debrid is preferred, is cached. Your own servers needn't be
     * cached; a bare torrent never qualifies.
     */
    fun isGoodEnoughToStartEarly(stream: Stream, rules: StreamRankingRules): Boolean {
        if (stream.isTorrent()) return false
        val descriptor = StreamTitleParser.parse(stream)
        val target = minOf(rules.preferredResolution.height, rules.maxResolution.height)
        if (descriptor.resolution.height == 0 || descriptor.resolution.height < target) return false
        if (stream.serverTarget != null) return true
        return !rules.preferCachedDebrid || descriptor.isDebridCached
    }

    /**
     * The source in [hint]'s binge group, in addon order: the same release for the next episode
     * (file name with the episode number moved on) first, then the same torrent, then any in
     * the group. [current] and [next] are (season, episode) of the episode that played and the
     * one wanted; with no [current] the season and episode are read from the file name.
     */
    fun bingeMatch(ranked: List<RankedStream>, hint: BingeHint, current: Pair<Int?, Int>?, next: Pair<Int?, Int>?): RankedStream? {
        val inGroup = ranked.filter { it.stream.behaviorHints?.bingeGroup == hint.group && ExternalPlayer.canPlay(it.stream) }
            .sortedBy { it.originalIndex }
        if (inGroup.isEmpty()) return null
        val nextFilename = nextFilename(hint.filename, current, next)
        if (nextFilename != null) {
            inGroup.firstOrNull { FilenameEpisodeMatcher.matches(it.stream.behaviorHints?.filename, nextFilename) }?.let { return it }
        }
        if (!hint.infoHash.isNullOrBlank()) {
            inGroup.firstOrNull { it.stream.infoHash?.equals(hint.infoHash, ignoreCase = true) == true }?.let { return it }
        }
        return inGroup.first()
    }

    /** The same release in any group, by the next episode's file name (the TV's filename-only match). */
    fun filenameMatch(ranked: List<RankedStream>, filename: String?, current: Pair<Int?, Int>?, next: Pair<Int?, Int>?): RankedStream? {
        val expected = nextFilename(filename, current, next) ?: return null
        return ranked.filter { ExternalPlayer.canPlay(it.stream) }.sortedBy { it.originalIndex }
            .firstOrNull { FilenameEpisodeMatcher.matches(it.stream.behaviorHints?.filename, expected) }
    }

    private fun nextFilename(filename: String?, current: Pair<Int?, Int>?, next: Pair<Int?, Int>?): String? {
        val name = filename?.takeIf { it.isNotBlank() } ?: return null
        if (next == null) return name
        val playing = current ?: FilenameEpisodeMatcher.extractSeasonEpisode(name) ?: return name
        return FilenameEpisodeMatcher.buildNextFilename(name, playing.first, playing.second, next.first, next.second) ?: name
    }

    /**
     * The next episode's source (Up Next, the Episodes panel): the binge group first when
     * [preferBingeGroup], then, unless [fallback] is off while a group was expected, the same
     * release by file name and the usual pick. "Pick every time" still takes the best match.
     * Null means "show the sources".
     */
    fun nextEpisodePick(
        ranked: List<RankedStream>,
        mode: AutoPlayMode,
        regex: String,
        hint: BingeHint?,
        playingFilename: String?,
        preferBingeGroup: Boolean,
        fallback: Boolean,
        current: Pair<Int?, Int>?,
        next: Pair<Int?, Int>?,
        installedAddonNames: Set<String> = emptySet(),
        allowedAddons: Set<String> = emptySet()
    ): RankedStream? {
        if (preferBingeGroup && hint != null) {
            bingeMatch(ranked, hint, current, next)?.let { return it }
            if (!fallback) return null
        }
        val ranked = allowed(ranked, installedAddonNames, allowedAddons)
        filenameMatch(ranked, playingFilename ?: hint?.filename, current, next)?.let { return it }
        return pickSource(ranked, mode, regex) ?: ranked.firstOrNull { it.isEligible }
    }

    /**
     * The TV's direct auto-play rules, run each time an addon answers: a remembered binge group
     * plays as soon as it shows up; with Start early a good-enough best match plays at once;
     * otherwise auto-play waits for every addon or the timeout ([waitOver]), then picks by
     * [mode] or shows the sources. Before any addon has answered it always waits.
     */
    fun decide(
        ranked: List<RankedStream>,
        mode: AutoPlayMode,
        regex: String,
        rules: StreamRankingRules,
        startEarly: Boolean,
        bingeHint: BingeHint?,
        next: Pair<Int?, Int>?,
        anyAnswered: Boolean,
        allAnswered: Boolean,
        waitOver: Boolean,
        installedAddonNames: Set<String> = emptySet(),
        allowedAddons: Set<String> = emptySet()
    ): AutoPlayDecision {
        // As on TV, a remembered binge group isn't limited by the allowed addons.
        if (bingeHint != null) bingeMatch(ranked, bingeHint, null, next)?.let { return AutoPlayDecision.Play(it) }
        val ranked = allowed(ranked, installedAddonNames, allowedAddons)
        if (mode == AutoPlayMode.MANUAL) {
            // Only a remembered binge group could have started anything.
            return if (allAnswered) AutoPlayDecision.ShowSources else AutoPlayDecision.Wait
        }
        if (startEarly && mode == AutoPlayMode.BEST_MATCH) {
            val best = ranked.firstOrNull { it.isEligible }
            if (best != null && isGoodEnoughToStartEarly(best.stream, rules)) return AutoPlayDecision.Play(best)
        }
        if (!allAnswered && !(waitOver && anyAnswered)) return AutoPlayDecision.Wait
        return pickSource(ranked, mode, regex)?.let { AutoPlayDecision.Play(it) } ?: AutoPlayDecision.ShowSources
    }
}
