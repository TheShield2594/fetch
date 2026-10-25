package com.fetch.tv.data.youtube

/** One video tile from a YouTube feed, search result or shelf. */
data class YouTubeVideo(
    val videoId: String,
    val title: String,
    val channelName: String? = null,
    val detail: String? = null,
    val thumbnailUrl: String,
    val duration: String? = null,
    val watchedPercent: Int? = null,
    /** A Short; its thumbnail is portrait when it comes from a Shorts shelf. */
    val isShort: Boolean = false,
    /** Streaming now; [duration] then holds YouTube's "LIVE" label. */
    val isLive: Boolean = false
) {
    val watchUrl: String
        get() = "https://www.youtube.com/watch?v=$videoId"
}

/** A titled row of videos. Videos YouTube sends outside any shelf go in one with no title. */
data class YouTubeShelf(
    val title: String?,
    val videos: List<YouTubeVideo>,
    /** Loads more of this row, when YouTube pages it on its own. */
    val continuation: String? = null,
    /** A row of Shorts, shown with portrait cards. */
    val isShorts: Boolean = false
)

/** One page of a feed. [continuation] loads the next page; null on the last one. */
data class YouTubePage(
    val shelves: List<YouTubeShelf>,
    val continuation: String?
)

/** The signed-in Google account, as YouTube names it. */
data class YouTubeAccount(
    val name: String?,
    val handle: String?,
    val photoUrl: String?,
    val email: String? = null,
    /** Set for a brand account; sent as X-Goog-Pageid so requests act as that channel. */
    val pageId: String? = null,
    val isSelected: Boolean = false
)

/** A part of a video SponsorBlock users marked, which playback skips. */
data class YouTubeSkipSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String
)

/** YouTube's Up Next for a video. [next] falls back to the first related video. */
data class YouTubeWatchNext(
    val next: YouTubeVideo?,
    val related: List<YouTubeVideo>
)

/** What the signed-in TV player says about a video, beyond the streams Fetch gets elsewhere. */
data class YouTubePlayerInfo(
    val trackingLinks: Pair<String, String>?,
    val captions: kotlinx.serialization.json.JsonElement?
)

/** One playback that YouTube is told about, so it shows in the account's history. */
class YouTubeWatchSession internal constructor(
    val videoId: String,
    internal val playbackUrl: String,
    internal val watchtimeUrl: String,
    internal val clientPlaybackNonce: String,
    @Volatile internal var lastPositionSec: Float
) {
    @Volatile
    internal var recorded: Boolean = false
}

/** The sections of the YouTube screen, in tab order. */
enum class YouTubeFeed {
    HOME,
    SUBSCRIPTIONS,
    HISTORY,
    SEARCH
}

/** A pending sign-in: the code the user enters at [verificationUrl]. */
data class YouTubeDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

data class YouTubeToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Int
)

sealed interface YouTubeTokenPollResult {
    data object Pending : YouTubeTokenPollResult
    data class SlowDown(val intervalSeconds: Int) : YouTubeTokenPollResult
    data object Expired : YouTubeTokenPollResult
    data object Denied : YouTubeTokenPollResult
    data class Approved(val accountName: String?) : YouTubeTokenPollResult
    data class Failed(val reason: String) : YouTubeTokenPollResult
}

/** Thrown when YouTube no longer accepts the saved sign-in, so the user must sign in again. */
class YouTubeSignedOutException : Exception("YouTube sign-in expired")
