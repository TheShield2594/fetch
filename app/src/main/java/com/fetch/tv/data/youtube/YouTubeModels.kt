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
    val isLive: Boolean = false,
    /** The uploader's channel (a UC… browse id), when the tile links to it. */
    val channelId: String? = null,
    /**
     * Every channel the tile links to, the uploader first. A collaboration ("YouTube and 3 more")
     * credits several and may have no [channelId]; its byline or long-press menu lists them.
     */
    val channels: List<YouTubeChannelRef> = emptyList()
) {
    val watchUrl: String
        get() = "https://www.youtube.com/watch?v=$videoId"
}

/** A channel a video is credited to. */
data class YouTubeChannelRef(
    val channelId: String,
    val name: String? = null
)

/** A tile that opens a page instead of playing: a channel, a playlist, or one of a channel's tabs. */
data class YouTubeLink(
    val target: YouTubeBrowseTarget,
    val kind: YouTubeLinkKind,
    /** Null for the library shortcuts, which the screen names. */
    val title: String?,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    /** The channel tab a channel page's shortcut opens, when it's one of those. */
    val tab: YouTubeChannelTab? = null
)

/** The tabs of a channel page that its row of shortcuts opens. */
enum class YouTubeChannelTab {
    VIDEOS,
    SHORTS,
    LIVE,
    PLAYLISTS,
    RELEASES,
    PODCASTS,
    COMMUNITY
}

enum class YouTubeLinkKind {
    CHANNEL,
    PLAYLIST,
    WATCH_LATER,
    LIKED
}

/** A titled row of videos. Videos YouTube sends outside any shelf go in one with no title. */
data class YouTubeShelf(
    val title: String?,
    val videos: List<YouTubeVideo>,
    /** Loads more of this row, when YouTube pages it on its own. */
    val continuation: String? = null,
    /** A row of Shorts, shown with portrait cards. */
    val isShorts: Boolean = false,
    /** The page with all of this row, when YouTube links its header to one. */
    val viewAll: YouTubeBrowseTarget? = null,
    /** Channels and playlists in the row, after its videos. */
    val links: List<YouTubeLink> = emptyList()
) {
    fun isEmpty() = videos.isEmpty() && links.isEmpty()
}

/** A YouTube page Fetch can open on its own: a channel, a playlist, or all of a row. */
data class YouTubeBrowseTarget(
    val browseId: String,
    val params: String? = null,
    val title: String? = null
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
    val related: List<YouTubeVideo>,
    val actions: YouTubeVideoActions? = null
)

/** Where the signed-in channel stands with a video and its uploader, from the TV watch page. */
data class YouTubeVideoActions(
    /** Null when YouTube offers no like button, as for a video with ratings turned off. */
    val likeStatus: YouTubeLikeStatus?,
    val channelId: String?,
    val channelName: String?,
    /** Null when there's no subscribe button, as on the account's own videos. */
    val subscribed: Boolean?,
    /** What the subscribe button's own requests send along, as YouTube's TV app does. */
    val subscribeParams: String? = null,
    val unsubscribeParams: String? = null
)

/** One of the signed-in channel's playlists a video can be saved to, Watch Later among them. */
data class YouTubePlaylistOption(
    /** The playlist's own id: "WL" for Watch Later, otherwise a PL… id. Its page is "VL" + this. */
    val playlistId: String,
    val title: String,
    /** The video is already in it. */
    val containsVideo: Boolean
) {
    val isWatchLater: Boolean
        get() = playlistId == YouTubeService.WATCH_LATER_PLAYLIST_ID
}

/** Who can see a new playlist. [apiValue] is InnerTube's `privacyStatus`. */
enum class YouTubePlaylistPrivacy(val apiValue: String) {
    PRIVATE("PRIVATE"),
    UNLISTED("UNLISTED"),
    PUBLIC("PUBLIC")
}

enum class YouTubeLikeStatus {
    LIKE,
    DISLIKE,
    INDIFFERENT
}

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
    LIBRARY,
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
