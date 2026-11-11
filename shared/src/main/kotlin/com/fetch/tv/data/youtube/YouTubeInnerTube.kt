package com.fetch.tv.data.youtube

/**
 * What the TV app's YouTubeService and the desktop's YouTubeClient both send to YouTube: the TV
 * client they sign in as, InnerTube's endpoints and browse ids. One copy, so a client version
 * YouTube stops accepting is bumped once for both apps.
 */
object YouTubeInnerTube {
    // YouTube's TV app. Read from youtube.com/tv on each run, like SmartTube does, so a rotated
    // client still signs in; these are the fallback when that page can't be read.
    const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
    const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
    const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
    const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    const val DEVICE_GRANT_TYPE = "http://oauth.net/grant_type/device/1.0"
    const val ACTIVATION_URL = "https://www.youtube.com/activate"
    const val SUGGEST_URL = "https://suggestqueries-clients6.youtube.com/complete/search"
    const val MAX_SUGGESTIONS = 8

    const val INNERTUBE_URL = "https://www.youtube.com/youtubei/v1"
    const val TV_CLIENT_NAME = "TVHTML5"
    const val TV_CLIENT_NAME_ID = "7"
    // Kept in step with SmartTube's MediaServiceCore (innertube/utils/Constants.kt).
    const val TV_CLIENT_VERSION = "7.20260901.15.00"
    const val TV_URL = "https://www.youtube.com/tv"
    const val TV_USER_AGENT =
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
    // The web client, for the calls SmartTube sends with it rather than the TV one
    // (PostDataHelper.createQueryWeb): creating a playlist.
    const val WEB_CLIENT_NAME = "WEB"
    const val WEB_CLIENT_NAME_ID = "1"
    const val WEB_CLIENT_VERSION = "2.20260901.00.00"
    const val WEB_URL = "https://www.youtube.com/"
    const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    /** Refresh this long before the access token runs out, so a request never carries a stale one. */
    const val TOKEN_REFRESH_MARGIN_MS = 60_000L

    // TV browse ids. "default" is the TV home; FEwhat_to_watch is the older name for it.
    val HOME_BROWSE_IDS = listOf("default", "FEwhat_to_watch")
    const val SUBSCRIPTIONS_BROWSE_ID = "FEsubscriptions"
    const val HISTORY_BROWSE_ID = "FEhistory"
    /** The account's playlists, as SmartTube reads them for its library. */
    const val PLAYLISTS_BROWSE_ID = "FEplaylist_aggregation"
    const val WATCH_LATER_PLAYLIST_ID = "WL"
    const val LIKED_PLAYLIST_ID = "LL"
    /** A playlist's page is its id behind this prefix. */
    const val PLAYLIST_BROWSE_PREFIX = "VL"
    const val WATCH_LATER_BROWSE_ID = PLAYLIST_BROWSE_PREFIX + WATCH_LATER_PLAYLIST_ID
    const val LIKED_BROWSE_ID = PLAYLIST_BROWSE_PREFIX + LIKED_PLAYLIST_ID

    const val HISTORY_START_THRESHOLD_SEC = 180f

    /** How long a failed youtube.com/tv read falls back to the known client, as the extractor does. */
    const val FALLBACK_CONFIG_TTL_MS = 10 * 60 * 1000L
}
