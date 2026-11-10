package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.pickSource
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.tv.core.player.StreamAutoPlaySelection
import com.fetch.tv.data.local.StreamAutoPlayMode
import com.fetch.tv.core.player.FilenameEpisodeMatcher
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

/** The shared [StreamAutoPlaySelection] (the TV's rules) with what mpv can open. */
object DesktopAutoPlaySelection : StreamAutoPlaySelection(isPlayable = ExternalPlayer::canPlay)

/** The setting as the shared rules know it. */
fun AutoPlayMode.toShared(): StreamAutoPlayMode = when (this) {
    AutoPlayMode.BEST_MATCH -> StreamAutoPlayMode.BEST_MATCH
    AutoPlayMode.FIRST_STREAM -> StreamAutoPlayMode.FIRST_STREAM
    AutoPlayMode.REGEX_MATCH -> StreamAutoPlayMode.REGEX_MATCH
    AutoPlayMode.MANUAL -> StreamAutoPlayMode.MANUAL
}

/**
 * Auto-play's waiting rules over the desktop's ranked sources. Which sources it may take, the
 * pick for each mode and the binge-group and same-release matches are the shared
 * [StreamAutoPlaySelection] the TV uses.
 */
object AutoPlayPicker {
    private val selection = DesktopAutoPlaySelection

    /** "Allowed addons for autoplay": see [StreamAutoPlaySelection.isCandidate]. */
    fun allowed(ranked: List<RankedStream>, installedAddonNames: Set<String>, allowedAddons: Set<String>): List<RankedStream> {
        if (allowedAddons.isEmpty()) return ranked
        return ranked.filter { selection.isCandidate(it.stream, installedAddonNames, allowedAddons) }
    }

    /**
     * With addons chosen, auto-play needs only their answers, not every addon's. Null when it
     * waits for all of them: nothing chosen, or none of the chosen addons was asked.
     */
    fun addonsNeededForPick(askedAddonNames: Collection<String>, allowedAddons: Set<String>): Set<String>? =
        selection.sourcesNeededForPick(allowedAddons, askedAddonNames, serverSourceNames = emptyList())

    /** "Start without waiting": see [StreamAutoPlaySelection.isGoodEnoughToStartEarly]. */
    fun isGoodEnoughToStartEarly(stream: Stream, rules: StreamRankingRules): Boolean =
        selection.isGoodEnoughToStartEarly(stream, rules)

    /**
     * The source in [hint]'s binge group, in addon order: the same release for the next episode
     * first, then the same torrent, then any in the group. [current] and [next] are (season,
     * episode) of the episode that played and the one wanted; with no [current] the season and
     * episode are read from the file name.
     */
    fun bingeMatch(ranked: List<RankedStream>, hint: BingeHint, current: Pair<Int?, Int>?, next: Pair<Int?, Int>?): RankedStream? {
        val inOrder = ranked.sortedBy { it.originalIndex }
        val nextFilename = FilenameEpisodeMatcher.nextEpisodeFilename(hint.filename, current, next)
        val match = selection.bingeGroupMatch(inOrder.map { it.stream }, hint.group, nextFilename, hint.infoHash) ?: return null
        return inOrder.firstOrNull { it.stream === match }
    }

    /** The same release in any group, by the next episode's file name. */
    fun filenameMatch(ranked: List<RankedStream>, filename: String?, current: Pair<Int?, Int>?, next: Pair<Int?, Int>?): RankedStream? {
        val inOrder = ranked.sortedBy { it.originalIndex }
        val expected = FilenameEpisodeMatcher.nextEpisodeFilename(filename, current, next)
        val match = selection.filenameMatch(inOrder.map { it.stream }, expected) ?: return null
        return inOrder.firstOrNull { it.stream === match }
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
