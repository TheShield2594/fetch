package com.fetch.tv.ui.screens.detail

/**
 * The title and overview an episode surface shows, with the spoiler option applied.
 *
 * When "blur unwatched episodes" is on, the thumbnail is not the only spoiler: the overview
 * usually gives the plot away and the title often does too. For an unwatched episode the
 * overview is dropped and the title becomes the neutral "Episode N" ([hiddenTitle]).
 */
internal data class EpisodeSpoilerText(
    val title: String,
    val overview: String,
    val hidden: Boolean
)

internal fun episodeSpoilerText(
    title: String,
    overview: String?,
    hideSpoilers: Boolean,
    isWatched: Boolean,
    hiddenTitle: String
): EpisodeSpoilerText {
    val hide = hideSpoilers && !isWatched
    return if (hide) {
        EpisodeSpoilerText(title = hiddenTitle, overview = "", hidden = true)
    } else {
        EpisodeSpoilerText(title = title, overview = overview?.trim().orEmpty(), hidden = false)
    }
}

/** "Episode 4", or just "Episode" when the number is unknown. */
internal fun neutralEpisodeTitle(episodeWord: String, episodeNumber: Int?): String =
    episodeNumber?.let { "$episodeWord $it" } ?: episodeWord
