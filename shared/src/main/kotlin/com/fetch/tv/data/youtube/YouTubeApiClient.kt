package com.fetch.tv.data.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val VISITOR_DATA_REGEX = Regex(""""visitorData":"(.*?)"""")
private val BASE_JS_REGEXES = listOf(
    Regex("""id="base-js" src="(.*?)""""),
    Regex("""\.src = '(.*?m=base)'"""),
    Regex("""\.src = '(.*?)'; .\.id = 'base-js'""")
)
private val CLIENT_REGEX =
    Regex("""clientId:"([-\w]+\.apps\.googleusercontent\.com)",\n?[$\w]+:"(\w+)"""")

/**
 * A YouTube sign-in as read from where it's kept. [location] names that place (the TV's profile
 * id; null on desktop, where each profile has its own client), so whatever a request learns about
 * the sign-in is written back there, not to wherever is active by the time it answers.
 */
data class YouTubeStoredAuth(
    val location: Any?,
    val accessToken: String?,
    val refreshToken: String?,
    val accessTokenExpiresAt: Long?,
    val clientId: String?,
    val clientSecret: String?,
    /** The brand account requests act as; null for the Google account's own channel. */
    val pageId: String?
) {
    /** Which sign-in and channel this is, without the tokens that change on every refresh. */
    val identity: YouTubeSignInId get() = YouTubeSignInId(location, refreshToken, pageId)
}

/** A sign-in and the channel it acts as; two reads with the same identity are the same account. */
data class YouTubeSignInId(val location: Any?, val refreshToken: String?, val pageId: String?)

/**
 * Where [YouTubeApiClient] keeps the sign-in. Each write names the sign-in it was worked out from
 * and is dropped if that place no longer holds it (same [YouTubeStoredAuth.location], same
 * refresh token): a sign-out, a new sign-in or a channel switch made meanwhile always wins.
 */
interface YouTubeAuthStorage {
    /** The sign-in requests go out with now. */
    suspend fun read(): YouTubeStoredAuth

    /** A refreshed access token for [from]'s sign-in. A token answer may carry a new refresh token. */
    suspend fun saveRefreshedToken(from: YouTubeStoredAuth, token: YouTubeToken, nowMs: Long)

    /** YouTube refused [from]'s access token before it ran out; the next request refreshes it. */
    suspend fun expireAccessToken(from: YouTubeStoredAuth)

    /** Google says [from]'s sign-in is gone (removed from the account, or lapsed). */
    suspend fun clearRevokedSignIn(from: YouTubeStoredAuth)
}

/**
 * YouTube for the TV app's YouTubeService and the desktop's YouTubeClient, which extend it with
 * how each keeps the sign-in: signs in the way YouTube's own TV app does and reads the signed-in
 * feeds.
 *
 * Sign-in is Google's device flow for TVs: the app shows a code, the user enters it at
 * youtube.com/activate, and the app polls until it's approved. The feeds come from InnerTube, the
 * API behind YouTube's apps, using the TV client. Playback isn't here.
 *
 * Requests are cancellable: cancelling the coroutine that waits on one cancels the HTTP call, so a
 * timeout around a feed load really frees it.
 */
open class YouTubeApiClient(
    private val http: OkHttpClient,
    private val storage: YouTubeAuthStorage,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val now: () -> Long = System::currentTimeMillis
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()
    private val refreshMutex = Mutex()
    private val tvAppMutex = Mutex()
    // One history report at a time, so the periodic one and the one sent on closing never cross.
    private val historyMutex = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What youtube.com/tv tells the TV app at start-up. Read once per run. */
    data class TvAppConfig(val clientId: String, val clientSecret: String, val visitorData: String?)

    @Volatile private var tvAppConfig: TvAppConfig? = null

    /** When a failed read's fallback stops being used and youtube.com/tv is tried again. */
    @Volatile private var fallbackConfigUntilMs = 0L

    /** Reports what went wrong where it doesn't reach the user. The TV writes it to logcat. */
    protected open fun log(message: String, error: Throwable? = null) = Unit

    /** The title given to the library's Watch later and Liked videos shortcuts; null leaves it to the screen. */
    protected open fun libraryShortcutTitle(kind: YouTubeLinkKind): String? = null

    // --- Sign-in ---

    /** Asks Google for a code to show; the user enters it at [YouTubeDeviceCode.verificationUrl]. */
    suspend fun requestDeviceCode(): YouTubeDeviceCode = withContext(Dispatchers.IO) {
        val config = tvAppConfig()
        val body = buildJsonObject {
            put("client_id", config.clientId)
            put("device_id", UUID.randomUUID().toString().replace("-", ""))
            put("model_name", "ytlr::")
            put("scope", YouTubeInnerTube.SCOPE)
        }
        val (status, response) = post(YouTubeInnerTube.DEVICE_CODE_URL, body)
        val code = parseDeviceCode(response) ?: error(errorText(response) ?: "YouTube sign-in failed ($status)")
        code.copy(clientId = config.clientId, clientSecret = config.clientSecret)
    }

    /** One poll of a pending sign-in. Throws when Google can't be reached. */
    protected suspend fun exchangeDeviceCode(deviceCode: String, clientId: String, clientSecret: String): TokenPoll =
        withContext(Dispatchers.IO) {
            val (_, response) = post(
                YouTubeInnerTube.TOKEN_URL,
                buildJsonObject {
                    put("code", deviceCode)
                    put("client_id", clientId)
                    put("client_secret", clientSecret)
                    put("grant_type", YouTubeInnerTube.DEVICE_GRANT_TYPE)
                }
            )
            parseTokenPoll(response)
        }

    /** Tells Google to drop the sign-in [refreshToken] belongs to. Best effort. */
    protected suspend fun revoke(refreshToken: String) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${YouTubeInnerTube.REVOKE_URL}?token=${java.net.URLEncoder.encode(refreshToken, "UTF-8")}")
                .post(ByteArray(0).toRequestBody())
                .build()
            http.newCall(request).awaitText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not revoke YouTube sign-in: ${e.message}")
        }
    }

    /** The channel [pageId] names among the account's, or the account's own when it's null or gone. */
    protected suspend fun fetchAccount(accessToken: String? = null, pageId: String? = null): YouTubeAccount? {
        val accounts = YouTubeResponseParser.parseAccounts(accountsList(accessToken))
        return accounts.firstOrNull { pageId != null && it.pageId == pageId }
            ?: accounts.firstOrNull { it.pageId == null && pageId == null && it.isSelected }
            ?: accounts.firstOrNull { it.pageId == null }
            ?: accounts.firstOrNull()
    }

    /**
     * The account the sign-in acts as now, read again, with the sign-in it was read for; null when
     * it can't be read. Save it only while the stored sign-in still has that identity.
     */
    protected suspend fun currentAccount(): Pair<YouTubeStoredAuth, YouTubeAccount>? = withContext(Dispatchers.IO) {
        try {
            val (accessToken, signIn) = validSignIn()
            fetchAccount(accessToken, signIn.pageId)?.let { signIn to it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not read the YouTube account: ${e.message}")
            null
        }
    }

    // --- Feeds ---

    suspend fun loadFeed(feed: YouTubeFeed, query: String? = null, filter: YouTubeSearchFilter = YouTubeSearchFilter()): YouTubePage =
        withContext(Dispatchers.IO) {
            when (feed) {
                YouTubeFeed.HOME -> loadHome()
                YouTubeFeed.SUBSCRIPTIONS -> browse(YouTubeInnerTube.SUBSCRIPTIONS_BROWSE_ID)
                YouTubeFeed.HISTORY -> YouTubeResponseParser.asHistory(browse(YouTubeInnerTube.HISTORY_BROWSE_ID))
                YouTubeFeed.LIBRARY -> loadLibrary()
                YouTubeFeed.SEARCH -> {
                    val trimmed = query?.trim().orEmpty()
                    if (trimmed.isEmpty()) {
                        YouTubePage(emptyList(), null)
                    } else {
                        YouTubeResponseParser.parsePage(
                            innertube("search") {
                                put("query", trimmed)
                                filter.params?.let { put("params", it) }
                            }
                        )
                    }
                }
            }
        }

    /** A page opened from a feed: a channel, a playlist, or all of a row. */
    suspend fun loadBrowse(target: YouTubeBrowseTarget): YouTubePage = withContext(Dispatchers.IO) {
        YouTubeResponseParser.parsePage(
            innertube("browse") {
                put("browseId", target.browseId)
                target.params?.let { put("params", it) }
            }
        )
    }

    /** The page after [token]. Search pages through the search endpoint; every other feed through browse. */
    suspend fun loadMore(feed: YouTubeFeed?, token: String): YouTubePage = withContext(Dispatchers.IO) {
        val endpoint = if (feed == YouTubeFeed.SEARCH) "search" else "browse"
        val page = YouTubeResponseParser.parsePage(innertube(endpoint) { put("continuation", token) })
        if (feed == YouTubeFeed.HISTORY) YouTubeResponseParser.asHistory(page) else page
    }

    /** The channels this Google account can act as: its own and any brand accounts. */
    suspend fun listAccounts(): List<YouTubeAccount> = withContext(Dispatchers.IO) {
        YouTubeResponseParser.parseAccounts(accountsList(null))
    }

    /** What plays after [videoId]: YouTube's autoplay pick (the playlist's next, from one) and the related videos. */
    suspend fun loadWatchNext(videoId: String, playlistId: String? = null, playlistIndex: Int? = null): YouTubeWatchNext =
        withContext(Dispatchers.IO) {
            val response = innertube("next") {
                put("videoId", videoId)
                // Played from a playlist, Up Next is the playlist's next video, as on YouTube.
                playlistId?.let { put("playlistId", it) }
                playlistIndex?.let { put("playlistIndex", it) }
            }
            YouTubeResponseParser.parseWatchNext(response, videoId)
        }

    /**
     * The video's chapters from the web watch page, for when the TV one leaves them out. Asked
     * signed out: chapters are the same for everyone, and a refusal here can't touch the sign-in.
     */
    suspend fun loadChapters(videoId: String): List<YouTubeChapter> = withContext(Dispatchers.IO) {
        YouTubeResponseParser.parseChapters(innertube("next", Auth.SignedOut, webClient = true) { put("videoId", videoId) })
    }

    /**
     * The video's description, view count and upload date, and the first comments, from the web
     * watch page, asked signed out (the same for everyone). Null when it can't be read.
     */
    suspend fun loadVideoDetails(videoId: String): YouTubeVideoDetails? = withContext(Dispatchers.IO) {
        try {
            val next = innertube("next", Auth.SignedOut, webClient = true) { put("videoId", videoId) }
            val details = YouTubeDetailsParser.parseVideoDetails(next) ?: return@withContext null
            val commentsToken = YouTubeDetailsParser.parseCommentsContinuation(next)
            val comments = commentsToken?.let { token -> loadComments(token) }
            details.copy(commentCount = comments?.count, comments = comments?.comments.orEmpty(), commentsContinuation = comments?.continuation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("No details for $videoId: ${e.message}")
            null
        }
    }

    /** The comments after [token] (from [loadVideoDetails] or an earlier page). Null when they can't be read. */
    suspend fun loadComments(token: String): YouTubeCommentsPage? = withContext(Dispatchers.IO) {
        try {
            YouTubeDetailsParser.parseComments(innertube("next", Auth.SignedOut, webClient = true) { put("continuation", token) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("No comments: ${e.message}")
            null
        }
    }

    /** What YouTube's search box would suggest for [query], from its suggest service. Empty when it can't be read. */
    suspend fun searchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        try {
            val loc = locale()
            val url = YouTubeInnerTube.SUGGEST_URL.toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("client", "youtube")
                .addQueryParameter("ds", "yt")
                .addQueryParameter("hl", loc.language.ifBlank { "en" })
                .apply { loc.country.takeIf { it.isNotBlank() }?.let { addQueryParameter("gl", it) } }
                .addQueryParameter("q", trimmed)
                .build()
            parseSuggestions(get(url.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("No search suggestions: ${e.message}")
            emptyList()
        }
    }

    /** `window.google.ac.h(["query",[["suggestion",0,[…]],…],{…}])`: the first item of each entry. */
    fun parseSuggestions(body: String): List<String> {
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching { json.parseToJsonElement(body.substring(start, end + 1)) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return (root.getOrNull(1) as? JsonArray).orEmpty().mapNotNull { entry ->
            val text = when (entry) {
                is JsonArray -> (entry.firstOrNull() as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> entry.contentOrNull
                else -> null
            }
            text?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct().take(YouTubeInnerTube.MAX_SUGGESTIONS)
    }

    // --- Actions ---

    /** Likes, dislikes, or clears the rating of a video, as the selected channel. Throws if YouTube refuses. */
    suspend fun setLikeStatus(videoId: String, status: YouTubeLikeStatus) = withContext(Dispatchers.IO) {
        val endpoint = when (status) {
            YouTubeLikeStatus.LIKE -> "like/like"
            YouTubeLikeStatus.DISLIKE -> "like/dislike"
            YouTubeLikeStatus.INDIFFERENT -> "like/removelike"
        }
        innertube(endpoint) { putJsonObject("target") { put("videoId", videoId) } }
        Unit
    }

    /** Subscribes to [channelId], or unsubscribes, with the params the watch page's button gave. Throws if YouTube refuses. */
    suspend fun setSubscribed(channelId: String, subscribed: Boolean, params: String?) = withContext(Dispatchers.IO) {
        innertube(if (subscribed) "subscription/subscribe" else "subscription/unsubscribe") {
            putJsonArray("channelIds") { add(channelId) }
            params?.let { put("params", it) }
        }
        Unit
    }

    /**
     * Sends a tile menu's feedback token: "Remove from watch history" or "Not interested". The
     * body and the TV context are SmartTube's (FeedbackApiHelper.getNotInterestedQuery). Throws if
     * YouTube refuses.
     */
    suspend fun sendFeedback(feedbackToken: String) = withContext(Dispatchers.IO) {
        val response = innertube("feedback") { putJsonArray("feedbackTokens") { add(feedbackToken) } }
        if (!YouTubeResponseParser.parseFeedbackProcessed(response)) throw IllegalStateException("YouTube didn't process the feedback")
        Unit
    }

    /** The channel's playlists, Watch Later first, each saying whether it holds [videoId] (YouTube's "Save" menu). */
    suspend fun loadPlaylistOptions(videoId: String): List<YouTubePlaylistOption> = withContext(Dispatchers.IO) {
        YouTubeResponseParser.parsePlaylistOptions(
            innertube("playlist/get_add_to_playlist") {
                putJsonArray("videoIds") { add(videoId) }
                put("excludeWatchLater", false)
            }
        ).sortedByDescending { it.isWatchLater }
    }

    /** Adds [videoId] to [playlistId], or takes it out. Throws if YouTube refuses. */
    suspend fun setInPlaylist(playlistId: String, videoId: String, inPlaylist: Boolean) = withContext(Dispatchers.IO) {
        val response = innertube("browse/edit_playlist") {
            put("playlistId", playlistId)
            putJsonArray("actions") {
                add(
                    buildJsonObject {
                        if (inPlaylist) {
                            put("action", "ACTION_ADD_VIDEO")
                            put("addedVideoId", videoId)
                        } else {
                            put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                            put("removedVideoId", videoId)
                        }
                    }
                )
            }
        }
        // YouTube answers 200 with a failed status when the playlist isn't the channel's to edit.
        val status = ((response as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
        if (status != null && status != "STATUS_SUCCEEDED") throw IllegalStateException("YouTube edit_playlist: $status")
        Unit
    }

    /**
     * Creates a playlist named [title] holding [videoId], in one request, and returns its id. The
     * body is SmartTube's (PlaylistApiHelper.getCreatePlaylistQuery), sent with the web client as
     * SmartTube does, plus the privacy YouTube's web app sends. Throws if YouTube refuses.
     */
    suspend fun createPlaylist(title: String, videoId: String, privacy: YouTubePlaylistPrivacy): String = withContext(Dispatchers.IO) {
        val response = innertube("playlist/create", webClient = true) {
            put("title", title)
            putJsonArray("videoIds") { add(videoId) }
            put("privacyStatus", privacy.apiValue)
        }
        parseCreatedPlaylistId(response) ?: throw IllegalStateException("YouTube playlist/create gave no playlist")
    }

    fun parseCreatedPlaylistId(response: JsonElement?): String? =
        ((response as? JsonObject)?.get("playlistId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    // --- Watch history ---

    /**
     * What the TV player says about the video, asked signed in: its history tracking links, caption
     * tracks and storyboard. When the TV response has no storyboard, the web player is asked for
     * one, signed out. Null when the request fails; the video still plays.
     */
    suspend fun loadPlayerInfo(videoId: String): YouTubePlayerInfo? = withContext(Dispatchers.IO) {
        try {
            // Tracking links belong to the account that asked for them; history goes back to it alone.
            val signIn = try {
                storage.read().identity
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val response = innertube("player") { put("videoId", videoId) }
            val storyboard = YouTubeResponseParser.parseStoryboardSpec(response)
                ?: try {
                    YouTubeResponseParser.parseStoryboardSpec(innertube("player", Auth.SignedOut, webClient = true) { put("videoId", videoId) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            YouTubePlayerInfo(
                trackingLinks = parseWatchTracking(response),
                signIn = signIn,
                captions = (response as? JsonObject)?.get("captions"),
                storyboardSpec = storyboard
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("No signed-in player info for $videoId: ${e.message}")
            null
        }
    }

    /** A history session for [videoId] from its tracking links, or null when YouTube gave none. */
    fun newWatchSession(videoId: String, info: YouTubePlayerInfo?, startPositionSec: Float): YouTubeWatchSession? {
        val (playbackUrl, watchtimeUrl) = info?.trackingLinks ?: return null
        return YouTubeWatchSession(
            videoId = videoId,
            playbackUrl = playbackUrl,
            watchtimeUrl = watchtimeUrl,
            clientPlaybackNonce = newClientPlaybackNonce(),
            signIn = info.signIn,
            // Like SmartTube, a start in the first three minutes counts from the beginning.
            lastPositionSec = if (startPositionSec < YouTubeInnerTube.HISTORY_START_THRESHOLD_SEC) 0f else startPositionSec
        )
    }

    /**
     * Tells YouTube how far the video got. The first report creates the history entry; later ones
     * move its position. Within the last 5% the video is marked watched, as SmartTube does
     * (track/TrackingService.java). Reports go one at a time, in the order they were asked for.
     */
    suspend fun reportWatchTime(session: YouTubeWatchSession, positionSec: Float, lengthSec: Float) = withContext(Dispatchers.IO) {
        if (lengthSec <= 0f) return@withContext
        historyMutex.withLock {
            try {
                val (accessToken, signIn) = validSignIn()
                if (session.signIn != null && session.signIn != signIn.identity) {
                    // Signed out, into another account or onto another channel since it started:
                    // this account's links mustn't go out with another's token.
                    log("Skipped a YouTube history report: the sign-in changed")
                    return@withLock
                }
                val almostWatched = lengthSec - positionSec < lengthSec * 0.05f
                val from = if (almostWatched) lengthSec else session.lastPositionSec
                val to = if (almostWatched) lengthSec else positionSec
                if (!session.recorded || almostWatched) {
                    ping(watchTrackingUrl(session.playbackUrl, session, lengthSec, cmt = from, final = almostWatched), accessToken)
                    session.recorded = true
                }
                ping(watchTrackingUrl(session.watchtimeUrl, session, lengthSec, cmt = to, from = from, to = to, final = almostWatched), accessToken)
                session.lastPositionSec = to
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Could not update YouTube history: ${e.message}")
            }
        }
    }

    /** [reportWatchTime] in this client's own scope, so it outlives the screen that asked. */
    fun reportWatchTimeInBackground(session: YouTubeWatchSession, positionSec: Float, lengthSec: Float) {
        backgroundScope.launch { reportWatchTime(session, positionSec, lengthSec) }
    }

    fun watchTrackingUrl(
        baseUrl: String,
        session: YouTubeWatchSession,
        lengthSec: Float,
        cmt: Float,
        from: Float? = null,
        to: Float? = null,
        final: Boolean = false
    ): String {
        val url = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Bad tracking url")
        return url.newBuilder()
            .setQueryParameter("ns", "yt")
            .setQueryParameter("ver", "2")
            .setQueryParameter("docid", session.videoId)
            .setQueryParameter("cpn", session.clientPlaybackNonce)
            .setQueryParameter("len", formatSeconds(lengthSec))
            .setQueryParameter("cmt", formatSeconds(cmt))
            .apply {
                from?.let { setQueryParameter("st", formatSeconds(it)) }
                to?.let { setQueryParameter("et", formatSeconds(it)) }
                if (final) setQueryParameter("final", "1")
            }
            .build()
            .toString()
    }

    fun parseWatchTracking(response: JsonElement?): Pair<String, String>? {
        val tracking = (response as? JsonObject)?.get("playbackTracking") as? JsonObject ?: return null
        fun baseUrl(key: String) = ((tracking[key] as? JsonObject)?.get("baseUrl") as? JsonPrimitive)?.contentOrNull
        val playback = baseUrl("videostatsPlaybackUrl") ?: return null
        val watchtime = baseUrl("videostatsWatchtimeUrl") ?: return null
        return playback to watchtime
    }

    private suspend fun ping(url: String, accessToken: String) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", YouTubeInnerTube.TV_USER_AGENT)
            .get()
            .build()
        http.newCall(request).awaitText()
    }

    private fun formatSeconds(seconds: Float) = String.format(Locale.US, "%.3f", seconds)

    /** YouTube's client playback nonce: 16 random url-safe characters per playback. */
    private fun newClientPlaybackNonce(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val random = java.security.SecureRandom()
        return String(CharArray(16) { alphabet[random.nextInt(alphabet.length)] })
    }

    // --- Pages ---

    /**
     * Watch Later and Liked videos, then the account's playlists. The two lead even when the
     * playlists can't be read, and aren't repeated when YouTube lists them among the playlists.
     */
    private suspend fun loadLibrary(): YouTubePage {
        val playlists = try {
            browse(YouTubeInnerTube.PLAYLISTS_BROWSE_ID)
        } catch (e: YouTubeSignedOutException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Couldn't load the library's playlists", e)
            YouTubePage(emptyList(), null)
        }
        val listed = playlists.shelves.flatMap { shelf -> shelf.links.map { it.target.browseId } }.toSet()
        fun shortcut(browseId: String, kind: YouTubeLinkKind) =
            YouTubeLink(YouTubeBrowseTarget(browseId, title = libraryShortcutTitle(kind)), kind, title = null)
        val shortcuts = listOf(
            shortcut(YouTubeInnerTube.WATCH_LATER_BROWSE_ID, YouTubeLinkKind.WATCH_LATER),
            shortcut(YouTubeInnerTube.LIKED_BROWSE_ID, YouTubeLinkKind.LIKED)
        ).filter { it.target.browseId !in listed }
        // They join the first row of playlists, so a library of one row still shows as one grid.
        val first = playlists.shelves.firstOrNull { it.videos.isEmpty() }
        val shelves = if (first == null) {
            listOf(YouTubeShelf(title = null, videos = emptyList(), links = shortcuts)) + playlists.shelves
        } else {
            playlists.shelves.map { if (it === first) it.copy(links = shortcuts + it.links) else it }
        }
        return playlists.copy(shelves = shelves)
    }

    /** Tries each home browse id in turn, so a renamed one falls back to the next. */
    private suspend fun loadHome(): YouTubePage {
        var lastError: Exception? = null
        for (browseId in YouTubeInnerTube.HOME_BROWSE_IDS) {
            try {
                val page = browse(browseId)
                if (page.shelves.isNotEmpty()) return page
            } catch (e: YouTubeSignedOutException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        return YouTubePage(emptyList(), null)
    }

    private suspend fun browse(browseId: String): YouTubePage =
        YouTubeResponseParser.parsePage(innertube("browse") { put("browseId", browseId) })

    /** Asked as the Google account itself, so every channel it owns is listed. */
    private suspend fun accountsList(accessToken: String?): JsonElement =
        innertube("account/accounts_list", accessToken?.let(Auth::Explicit) ?: Auth.Stored, actAsSelectedChannel = false) {
            putJsonObject("accountReadMask") {
                put("returnOwner", true)
                put("returnBrandAccounts", true)
                put("returnPersonaAccounts", false)
            }
        }

    // --- Requests ---

    /** How an InnerTube request signs in. */
    protected sealed interface Auth {
        /** With the saved sign-in, refreshed first when it's about to run out. */
        data object Stored : Auth
        /** With this access token (one just issued, before it's saved). */
        data class Explicit(val accessToken: String) : Auth
        /** Signed out. */
        data object SignedOut : Auth
    }

    private suspend fun innertube(
        endpoint: String,
        auth: Auth = Auth.Stored,
        actAsSelectedChannel: Boolean = true,
        /** Sends the web client's context instead of the TV app's, for calls SmartTube makes as web. */
        webClient: Boolean = false,
        params: JsonObjectBuilder.() -> Unit
    ): JsonElement {
        val visitorData = try {
            tvAppConfig().visitorData
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val userAgent = if (webClient) YouTubeInnerTube.WEB_USER_AGENT else YouTubeInnerTube.TV_USER_AGENT
        val body = requestBody(webClient, userAgent, visitorData, params).toString()
        var retried = false
        var first: YouTubeSignInId? = null
        while (true) {
            val signIn: Pair<String, YouTubeStoredAuth?>? = when (auth) {
                Auth.Stored -> validSignIn()
                is Auth.Explicit -> auth.accessToken to null
                Auth.SignedOut -> null
            }
            // A retry goes out as the account the request started as, or not at all.
            signIn?.second?.identity?.let { identity ->
                if (first == null) first = identity
                else if (identity != first) throw IllegalStateException("The YouTube sign-in changed during $endpoint")
            }
            // The channel comes from the same read as the token, so the two always belong together.
            val pageId = if (actAsSelectedChannel) signIn?.second?.pageId else null
            val request = Request.Builder()
                .url("${YouTubeInnerTube.INNERTUBE_URL}/$endpoint?prettyPrint=false")
                .apply { signIn?.let { header("Authorization", "Bearer ${it.first}") } }
                .header("User-Agent", userAgent)
                .header("X-YouTube-Client-Name", if (webClient) YouTubeInnerTube.WEB_CLIENT_NAME_ID else YouTubeInnerTube.TV_CLIENT_NAME_ID)
                .header("X-YouTube-Client-Version", if (webClient) YouTubeInnerTube.WEB_CLIENT_VERSION else YouTubeInnerTube.TV_CLIENT_VERSION)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", if (webClient) YouTubeInnerTube.WEB_URL else YouTubeInnerTube.TV_URL)
                .apply { visitorData?.let { header("X-Goog-Visitor-Id", it) } }
                // Acts as the chosen brand account, as SmartTube's YouTubeSignInService does.
                .apply { pageId?.let { header("X-Goog-Pageid", it) } }
                .post(body.toRequestBody(jsonMediaType))
                .build()
            val (code, text) = http.newCall(request).awaitText()
            if (code == 401 && signIn != null) {
                val stored = signIn.second
                // Refused before it ran out (often after the device slept): refresh and ask once more.
                if (stored != null) {
                    storage.expireAccessToken(stored)
                    if (!retried) {
                        retried = true
                        continue
                    }
                }
                throw IllegalStateException("YouTube refused the sign-in (401)")
            }
            if (code !in 200..299) throw IllegalStateException("YouTube $endpoint failed ($code)")
            return json.parseToJsonElement(text)
        }
    }

    /** The context SmartTube sends for TV browse requests (common/helpers/AppClient.kt). */
    private fun requestBody(
        webClient: Boolean,
        userAgent: String,
        visitorData: String?,
        params: JsonObjectBuilder.() -> Unit
    ): JsonObject {
        val loc = locale()
        return buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    if (webClient) {
                        put("clientName", YouTubeInnerTube.WEB_CLIENT_NAME)
                        put("clientVersion", YouTubeInnerTube.WEB_CLIENT_VERSION)
                        put("userAgent", userAgent)
                    } else {
                        put("clientName", YouTubeInnerTube.TV_CLIENT_NAME)
                        put("clientVersion", YouTubeInnerTube.TV_CLIENT_VERSION)
                        put("clientScreen", "WATCH")
                        put("userAgent", userAgent)
                        putJsonObject("tvAppInfo") {
                            // Keeps Shorts out of the subscriptions feed.
                            put("appQuality", "TV_APP_QUALITY_FULL_ANIMATION")
                            put("zylonLeftNav", true)
                        }
                        put("webpSupport", false)
                        put("animatedWebpSupport", true)
                    }
                    put("hl", loc.language.ifBlank { "en" })
                    loc.country.takeIf { it.isNotBlank() }?.let { put("gl", it) }
                    put("utcOffsetMinutes", java.util.TimeZone.getDefault().getOffset(now()) / 60_000)
                    visitorData?.let { put("visitorData", it) }
                }
                putJsonObject("user") {
                    put("enableSafetyMode", false)
                    put("lockedSafetyMode", false)
                }
            }
            put("racyCheckOk", true)
            put("contentCheckOk", true)
            params()
        }
    }

    /** A current access token and the sign-in it came from, refreshed first when it's about to run out. */
    private suspend fun validSignIn(): Pair<String, YouTubeStoredAuth> = refreshMutex.withLock { refreshIfNeeded() }

    private suspend fun refreshIfNeeded(): Pair<String, YouTubeStoredAuth> {
        val state = storage.read()
        val refreshToken = state.refreshToken?.takeIf { it.isNotBlank() } ?: throw YouTubeSignedOutException()
        val expiresAt = state.accessTokenExpiresAt ?: 0L
        val access = state.accessToken
        if (!access.isNullOrBlank() && now() < expiresAt - YouTubeInnerTube.TOKEN_REFRESH_MARGIN_MS) return access to state
        val (status, response) = post(
            YouTubeInnerTube.TOKEN_URL,
            buildJsonObject {
                put("refresh_token", refreshToken)
                put("client_id", state.clientId ?: YouTubeInnerTube.CLIENT_ID)
                put("client_secret", state.clientSecret ?: YouTubeInnerTube.CLIENT_SECRET)
                put("grant_type", "refresh_token")
            }
        )
        val token = parseToken(response)
        if (token == null) {
            if (errorCode(response) == "invalid_grant") {
                // The user removed Fetch from their Google account, or the sign-in lapsed.
                storage.clearRevokedSignIn(state)
                throw YouTubeSignedOutException()
            }
            throw IllegalStateException(errorText(response) ?: "Could not refresh YouTube sign-in ($status)")
        }
        val nowMs = now()
        storage.saveRefreshedToken(state, token, nowMs)
        // The snapshot of what's now stored, so a 401 on the new token can expire it in turn.
        return token.accessToken to state.copy(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken?.takeIf { it.isNotBlank() } ?: state.refreshToken,
            accessTokenExpiresAt = nowMs + token.expiresInSeconds * 1000L
        )
    }

    /**
     * Reads the TV app's client id and secret from its script, the way SmartTube does
     * (app/models/AppInfo.java and ClientData.java), and its visitor id. Falls back to the known
     * client when YouTube can't be reached or the page has changed shape.
     */
    private suspend fun tvAppConfig(): TvAppConfig {
        tvAppConfig?.let { return it }
        val fallback = TvAppConfig(YouTubeInnerTube.CLIENT_ID, YouTubeInnerTube.CLIENT_SECRET, null)
        if (now() < fallbackConfigUntilMs) return fallback
        return tvAppMutex.withLock {
            tvAppConfig?.let { return@withLock it }
            if (now() < fallbackConfigUntilMs) return@withLock fallback
            // Only a config read from the page is kept for the run. After a failure (often the
            // network not being up yet) the fallback is used briefly, then the page is read again.
            try {
                fetchTvAppConfig().also { tvAppConfig = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Could not read youtube.com/tv: ${e.message}")
                fallbackConfigUntilMs = now() + YouTubeInnerTube.FALLBACK_CONFIG_TTL_MS
                fallback
            }
        }
    }

    private suspend fun fetchTvAppConfig(): TvAppConfig {
        val page = get(YouTubeInnerTube.TV_URL)
        val visitorData = VISITOR_DATA_REGEX.find(page)?.groupValues?.get(1)
        val scriptUrl = BASE_JS_REGEXES.firstNotNullOfOrNull { it.find(page)?.groupValues?.get(1) }
            ?.replace("\\/", "/")
            ?.let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
        val credentials = scriptUrl?.let { url ->
            scriptCredentials(url)
                // An older script layout keeps the client in m=main.
                ?: scriptCredentials(url.replace("/dg=0/", "/exm=base/ed=1/").replace("/m=base", "/m=main"))
        }
        return TvAppConfig(
            clientId = credentials?.first ?: YouTubeInnerTube.CLIENT_ID,
            clientSecret = credentials?.second ?: YouTubeInnerTube.CLIENT_SECRET,
            visitorData = visitorData
        )
    }

    private suspend fun scriptCredentials(url: String): Pair<String, String>? = try {
        parseClientCredentials(get(url))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The first client in the script is the TV's; later ones are refused (SmartTube's ClientData note). */
    fun parseClientCredentials(script: String): Pair<String, String>? =
        CLIENT_REGEX.find(script)?.let { it.groupValues[1] to it.groupValues[2] }

    private suspend fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", YouTubeInnerTube.TV_USER_AGENT).get().build()
        val (code, text) = http.newCall(request).awaitText()
        if (code !in 200..299) throw IllegalStateException("GET $url failed ($code)")
        return text
    }

    private suspend fun post(url: String, body: JsonObject): Pair<Int, JsonElement?> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", YouTubeInnerTube.TV_USER_AGENT)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        val (code, text) = http.newCall(request).awaitText()
        return code to runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    // --- Response parsing, kept apart from the network so it can be tested ---

    sealed interface TokenPoll {
        data class Token(val token: YouTubeToken) : TokenPoll
        data class Status(val status: YouTubeTokenPollResult) : TokenPoll
    }

    fun parseDeviceCode(response: JsonElement?): YouTubeDeviceCode? {
        val obj = response as? JsonObject ?: return null
        return YouTubeDeviceCode(
            deviceCode = obj.string("device_code") ?: return null,
            userCode = obj.string("user_code") ?: return null,
            verificationUrl = obj.string("verification_url") ?: obj.string("verification_uri") ?: YouTubeInnerTube.ACTIVATION_URL,
            expiresInSeconds = obj.int("expires_in") ?: 1800,
            intervalSeconds = obj.int("interval") ?: 5
        )
    }

    fun parseTokenPoll(response: JsonElement?): TokenPoll {
        parseToken(response)?.let { return TokenPoll.Token(it) }
        val status = when (errorCode(response)) {
            "authorization_pending" -> YouTubeTokenPollResult.Pending
            "slow_down" -> YouTubeTokenPollResult.SlowDown(((response as? JsonObject)?.int("interval") ?: 5) + 5)
            "expired_token" -> YouTubeTokenPollResult.Expired
            "access_denied" -> YouTubeTokenPollResult.Denied
            else -> YouTubeTokenPollResult.Failed(errorText(response) ?: "Unexpected response from YouTube")
        }
        return TokenPoll.Status(status)
    }

    fun parseToken(response: JsonElement?): YouTubeToken? {
        val obj = response as? JsonObject ?: return null
        return YouTubeToken(
            accessToken = obj.string("access_token") ?: return null,
            refreshToken = obj.string("refresh_token"),
            expiresInSeconds = obj.int("expires_in") ?: 3600
        )
    }

    private fun errorCode(response: JsonElement?): String? = (response as? JsonObject)?.string("error")

    private fun errorText(response: JsonElement?): String? {
        val obj = response as? JsonObject ?: return null
        return obj.string("error_description") ?: obj.string("error")
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/**
 * Runs the call on OkHttp's dispatcher and reads the answer there, so no coroutine thread blocks
 * on the network: cancelling the waiting coroutine cancels the call, even mid-body. The status
 * code and the body as text.
 */
internal suspend fun Call.awaitText(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                continuation.resumeWithException(e)
                return
            }
            continuation.resume(result)
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}
