package com.fetch.tv.ui.screens.player

/**
 * Live TV and VOD both commonly use HLS (.m3u8). The URL is not a reliable
 * discriminator. Use the player's live-window signal (ExoPlayer
 * [androidx.media3.common.Player.isCurrentMediaItemLive], or MPV unseekable)
 * plus the Stremio catalog type `channel`.
 *
 * Do not treat `tv` as live: in this app it is the series synonym.
 */
object LivePlaybackUiPolicy {
    fun isLiveContentType(contentType: String?): Boolean {
        return contentType.equals("channel", ignoreCase = true)
    }

    fun nextLiveLatch(playerReportsLive: Boolean, previouslyLatched: Boolean): Boolean {
        return previouslyLatched || playerReportsLive
    }

    /**
     * Live streams with a DVR window (HLS/DASH timeshift, many M3U and sports feeds) can seek
     * within that window, so only a live stream the player reports as unseekable blocks seeking.
     */
    fun blocksSeeking(isLive: Boolean, playerSeekable: Boolean): Boolean {
        return isLive && !playerSeekable
    }

    fun isLivePlayback(
        playerReportsLive: Boolean,
        contentType: String?,
        latchedLive: Boolean = false
    ): Boolean {
        return latchedLive ||
            playerReportsLive ||
            isLiveContentType(contentType)
    }
}

/** Accumulates wall-clock time spent actually playing a live stream. */
class LivePlaybackWatchClock {
    private var accumulatedMs = 0L
    private var segmentStartedAtElapsedMs: Long? = null

    fun reset() {
        accumulatedMs = 0L
        segmentStartedAtElapsedMs = null
    }

    fun watchedDurationMs(
        isLive: Boolean,
        isPlaying: Boolean,
        nowElapsedMs: Long
    ): Long {
        if (!isLive) {
            if (accumulatedMs != 0L || segmentStartedAtElapsedMs != null) {
                reset()
            }
            return 0L
        }
        if (isPlaying) {
            if (segmentStartedAtElapsedMs == null) {
                segmentStartedAtElapsedMs = nowElapsedMs
            }
        } else {
            val started = segmentStartedAtElapsedMs
            if (started != null) {
                accumulatedMs += (nowElapsedMs - started).coerceAtLeast(0L)
                segmentStartedAtElapsedMs = null
            }
        }
        val running = segmentStartedAtElapsedMs
            ?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
            ?: 0L
        return accumulatedMs + running
    }
}
