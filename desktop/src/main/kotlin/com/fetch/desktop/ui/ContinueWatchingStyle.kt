package com.fetch.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.home.ContinueWatchingArt
import com.fetch.desktop.poster.customPosterFor
import com.fetch.tv.domain.model.ContinueWatchingCardStyle

/**
 * Layout › Continue Watching's card options: Card, Wide or Poster cards, episode stills instead of
 * the show's backdrop, Next Up stills blurred against spoilers, plus what TMDB and a custom poster
 * source give each card.
 */
data class ContinueWatchingStyle(
    val card: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    val episodeThumbnails: Boolean = false,
    val blurNextUp: Boolean = false,
    /** A custom poster URL pattern for Poster cards; "" keeps the title's poster. */
    val posterPattern: String = "",
    /** The looked-up artwork for an entry, null until it's in. */
    val art: (ProgressEntry) -> ContinueWatchingArt? = { null },
    /** Asks for an entry's artwork (episode still, TMDB). */
    val requestArt: (ProgressEntry) -> Unit = {},
    /** A kids Home's bigger cards. */
    val scale: Float = 1f
) {
    val width: Dp
        get() = when (card) {
            ContinueWatchingCardStyle.CARD -> 320.dp
            ContinueWatchingCardStyle.WIDE -> 440.dp
            ContinueWatchingCardStyle.POSTER -> 160.dp
        } * scale

    val height: Dp
        get() = when (card) {
            ContinueWatchingCardStyle.CARD -> 180.dp
            ContinueWatchingCardStyle.WIDE -> 200.dp
            ContinueWatchingCardStyle.POSTER -> 240.dp
        } * scale

    /** The picture for [entry]'s card, and whether it's an episode still (which [blurNextUp] blurs). */
    fun imageFor(entry: ProgressEntry): Pair<String?, Boolean> {
        val looked = art(entry)
        if (card == ContinueWatchingCardStyle.POSTER) {
            return (customPosterFor(posterPattern, entry.contentId, entry.contentType) ?: entry.poster ?: looked?.backdrop ?: entry.backdrop) to false
        }
        val still = looked?.still?.takeIf { episodeThumbnails && entry.season != null }
        if (still != null) return still to true
        return (looked?.backdrop ?: entry.backdrop ?: entry.poster) to false
    }

    /** Whether [entry]'s picture is blurred: an unwatched Next Up episode's still. */
    fun blurred(entry: ProgressEntry, isStill: Boolean): Boolean = blurNextUp && isStill && entry.upNext

    /** The title, in TMDB's language when it's enriching Continue Watching. */
    fun nameFor(entry: ProgressEntry): String = art(entry)?.name ?: entry.name

    fun logoFor(entry: ProgressEntry): String? = art(entry)?.logo ?: entry.logo
}

val LocalContinueWatchingStyle = staticCompositionLocalOf { ContinueWatchingStyle() }
