package com.fetch.tv.ui.screens.detail

import com.fetch.tv.domain.model.Video

/**
 * "Mark watched on…": logging a watch at a time other than now, for something watched before it
 * was marked. Plain "Mark as watched" stays one press and means now; this is the item beside it.
 */

/** What is being marked. */
sealed interface WatchedOnTarget {
    data object Movie : WatchedOnTarget
    data class Episode(val video: Video) : WatchedOnTarget
    /** Every unwatched episode of the season, each on its own release date where that is the choice. */
    data class Season(val season: Int) : WatchedOnTarget
}
