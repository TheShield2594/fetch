package com.fetch.tv.data.youtube

/** What the profile chose to leave out of its YouTube feeds, search and Up Next. */
data class YouTubeFeedFilter(
    val showShorts: Boolean = true,
    val showLive: Boolean = true,
    val hideWatched: Boolean = false
) {
    /** Whether [video] shows in [feed]. History keeps watched videos; they're what it lists. */
    fun allows(video: YouTubeVideo, feed: YouTubeFeed? = null): Boolean = when {
        !showShorts && video.isShort -> false
        !showLive && video.isLive -> false
        hideWatched && feed != YouTubeFeed.HISTORY && (video.watchedPercent ?: 0) >= WATCHED_PERCENT -> false
        else -> true
    }

    /** [page] without the videos left out, and without the rows that leaves empty. */
    fun apply(page: YouTubePage, feed: YouTubeFeed): YouTubePage = page.copy(
        shelves = page.shelves.mapNotNull { shelf ->
            if (!showShorts && shelf.isShorts) return@mapNotNull null
            shelf.copy(videos = shelf.videos.filter { allows(it, feed) }).takeIf { !it.isEmpty() }
        }
    )

    fun apply(watchNext: YouTubeWatchNext): YouTubeWatchNext {
        val related = watchNext.related.filter { allows(it) }
        return watchNext.copy(next = watchNext.next?.takeIf { allows(it) } ?: related.firstOrNull(), related = related)
    }

    companion object {
        /** How far in a video counts as watched, as YouTube's own progress bar nears its end. */
        const val WATCHED_PERCENT = 90
    }
}
