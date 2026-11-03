package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.desktop.player.MpvProgress
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SkipPlanner
import com.fetch.desktop.youtube.DeArrowClient
import com.fetch.desktop.youtube.ReturnYouTubeDislikeClient
import com.fetch.desktop.youtube.SponsorBlockClient
import com.fetch.desktop.youtube.YOUTUBE_HOME_ROW_CACHE_TTL_MS
import com.fetch.desktop.youtube.MpvOsdNote
import com.fetch.desktop.youtube.YouTubeAccount
import com.fetch.desktop.youtube.YouTubeCaptionPreference
import com.fetch.desktop.youtube.YouTubeCaptionTrack
import com.fetch.desktop.youtube.YouTubeCaptions
import com.fetch.desktop.youtube.YouTubeLikeStatus
import com.fetch.desktop.youtube.YouTubePlayerExtras
import com.fetch.desktop.youtube.YouTubeSkipNotice
import com.fetch.desktop.youtube.YouTubeStoryboard
import com.fetch.desktop.youtube.YouTubeUpNext
import com.fetch.desktop.youtube.YouTubeUpNextPrompt
import com.fetch.desktop.youtube.YouTubeVideoActions
import com.fetch.desktop.youtube.youTubeExtras
import com.fetch.desktop.youtube.YouTubeAuthStore
import com.fetch.desktop.youtube.YouTubeBrowseTarget
import com.fetch.desktop.youtube.YouTubeChannelTab
import com.fetch.desktop.youtube.YouTubeClient
import com.fetch.desktop.youtube.YouTubeDeviceCode
import com.fetch.desktop.youtube.YouTubeFeed
import com.fetch.desktop.youtube.YouTubeLinkKind
import com.fetch.desktop.youtube.YouTubeLocalData
import com.fetch.desktop.youtube.YouTubePage
import com.fetch.desktop.youtube.YouTubePlayback
import com.fetch.desktop.youtube.YouTubePlaylistOption
import com.fetch.desktop.youtube.YouTubePlaylistPrivacy
import com.fetch.desktop.youtube.YouTubeSettings
import com.fetch.desktop.youtube.YouTubeShelf
import com.fetch.desktop.youtube.YouTubeSignedOutException
import com.fetch.desktop.youtube.YouTubeTokenPollResult
import com.fetch.desktop.youtube.YouTubeVideo
import com.fetch.desktop.youtube.YouTubeWatchNext
import com.fetch.desktop.youtube.YouTubeWatchSession
import com.fetch.desktop.youtube.addRecentSearch
import com.fetch.desktop.youtube.appendPage
import com.fetch.desktop.youtube.updateResumePoints
import com.fetch.desktop.youtube.withBranding
import com.fetch.desktop.youtube.youTubeHomeRowAllowed
import com.fetch.desktop.youtube.youTubeHomeRowVideos
import com.fetch.desktop.youtube.youTubeResumeStart
import com.fetch.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** What YouTube playback needs from the rest of the app. [AppState] provides it. */
interface YouTubeHost {
    val playerSettings: PlayerSettings
    val youTubeSettings: YouTubeSettings
    /** A restricted profile (rating limit, screen time, bedtime or autoplay cap) has no YouTube. */
    val restricted: Boolean
    fun message(text: String)
    /**
     * Whether to play in the built-in player (true) or mpv's window (false); asks when the
     * profile's Default Player is "Ask every time". Null when the question was dismissed.
     */
    suspend fun chooseBuiltIn(): Boolean?
    /** The built-in player, opened if it isn't yet; null when libmpv can't start. */
    suspend fun openBuiltInPlayer(): PlayerSession?
    val currentPlayer: PlayerSession?
    fun closePlayer()
    /** Leaves the player for a YouTube channel or playlist page (the player's "Go to channel"). */
    fun openBrowse(target: YouTubeBrowseTarget) {}
}

/** One feed or opened page: its rows, and the token for more. */
data class YouTubeFeedState(
    val shelves: List<YouTubeShelf> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val failed: Boolean = false,
    val loaded: Boolean = false,
    /** Rows loading more of themselves, by index. */
    val loadingShelves: Set<Int> = emptySet()
)

/** A feed tab, or a channel, playlist or "View all" page opened from one. */
sealed interface YouTubeFeedKey {
    val feed: YouTubeFeed?

    data class Tab(override val feed: YouTubeFeed) : YouTubeFeedKey
    data class Browse(val target: YouTubeBrowseTarget) : YouTubeFeedKey {
        override val feed: YouTubeFeed? get() = null
    }
}

/** "Save to playlist": the video and the channel's playlists, once they've loaded. */
data class YouTubePlaylistPicker(
    val video: YouTubeVideo,
    val options: List<YouTubePlaylistOption>? = null,
    val failed: Boolean = false
)

/** What's playing: the video and what YouTube says plays after it. */
data class YouTubeNowPlaying(
    val video: YouTubeVideo,
    val upNext: YouTubeVideo? = null,
    val watchNext: YouTubeWatchNext? = null
)

/**
 * YouTube for one profile, as the TV app's YouTube screen has it: a device-code sign-in, the
 * Home, Subscriptions, History and Library feeds, search with suggestions, channel and playlist
 * pages, the tile menu's actions, and playback in mpv through yt-dlp with resume, watch history,
 * SponsorBlock skips, DeArrow titles, Return YouTube Dislike counts and autoplay.
 */
class YouTubeState(
    private val scope: CoroutineScope,
    private val host: YouTubeHost,
    private val authStore: YouTubeAuthStore,
    private val dataStore: JsonFile<YouTubeLocalData>,
    http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    val client: YouTubeClient = YouTubeClient(http, authStore),
    private val sponsorBlock: SponsorBlockClient = SponsorBlockClient(http),
    private val deArrow: DeArrowClient = DeArrowClient(http),
    private val returnDislike: ReturnYouTubeDislikeClient = ReturnYouTubeDislikeClient(http),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val settings: YouTubeSettings get() = host.youTubeSettings

    // --- Sign-in ---

    var signedIn by mutableStateOf(authStore.load().isSignedIn)
        private set
    var accountName: String? by mutableStateOf(authStore.load().accountName)
        private set
    /** The code waiting to be entered at youtube.com/activate. */
    var pendingCode: YouTubeDeviceCode? by mutableStateOf(null)
        private set
    var signInError: String? by mutableStateOf(null)
        private set
    /** Set when YouTube stopped accepting the sign-in, so the page says to sign in again. */
    var signedOut by mutableStateOf(false)
        private set
    /** The channels the account can act as (Settings › YouTube › Channel), once asked for. */
    var accounts: List<YouTubeAccount>? by mutableStateOf(null)
        private set
    var accountsFailed by mutableStateOf(false)
        private set
    private var signInJob: Job? = null

    /** Whether YouTube shows in the sidebar: signed in, on a profile without limits (TV's rule). */
    val available: Boolean get() = signedIn && !host.restricted

    fun signIn() {
        signInJob?.cancel()
        signInError = null
        signInJob = scope.launch {
            val code = try {
                client.startDeviceAuth()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signInError = "Couldn't reach YouTube to start sign-in."
                return@launch
            }
            pendingCode = code
            var interval = code.intervalSeconds.coerceAtLeast(1) * 1000L
            val deadline = now() + code.expiresInSeconds * 1000L
            while (now() < deadline) {
                delay(interval)
                when (val poll = client.pollDeviceToken(code)) {
                    YouTubeTokenPollResult.Pending -> Unit
                    is YouTubeTokenPollResult.SlowDown -> interval = poll.intervalSeconds * 1000L
                    YouTubeTokenPollResult.Expired -> return@launch failSignIn("The code expired. Try again for a new one.")
                    YouTubeTokenPollResult.Denied -> return@launch failSignIn("Sign-in was cancelled on YouTube.")
                    // A network hiccup while waiting: keep polling until the code runs out.
                    is YouTubeTokenPollResult.Failed -> Unit
                    is YouTubeTokenPollResult.Approved -> {
                        pendingCode = null
                        onSignedIn()
                        host.message("Signed in to YouTube. Find it in the sidebar.")
                        return@launch
                    }
                }
            }
            failSignIn("The code expired. Try again for a new one.")
        }
    }

    private fun failSignIn(reason: String) {
        pendingCode = null
        signInError = reason
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        pendingCode = null
    }

    fun signOut() {
        scope.launch {
            client.signOut()
            signedIn = false
            accountName = null
            accounts = null
            resetFeeds()
            homeRow = emptyList()
            homeRowFetchedAt = 0L
            host.message("Signed out of YouTube")
        }
    }

    private fun onSignedIn() {
        val auth = authStore.load()
        signedIn = auth.isSignedIn
        accountName = auth.accountName
        signedOut = false
        resetFeeds()
        homeRowFetchedAt = 0L
        if (accountName == null) scope.launch {
            client.refreshAccount()
            accountName = authStore.load().accountName
        }
    }

    fun loadAccounts() {
        accountsFailed = false
        scope.launch {
            accounts = try {
                client.listAccounts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                accountsFailed = true
                null
            }
        }
    }

    val selectedPageId: String? get() = authStore.load().pageId

    /** Acts as [account] from now on; the feeds load again as that channel. */
    fun selectAccount(account: YouTubeAccount) {
        client.selectAccount(account)
        accountName = account.name
        resetFeeds()
        homeRowFetchedAt = 0L
    }

    // --- Feeds and pages ---

    var selectedFeed by mutableStateOf(YouTubeFeed.HOME)
        private set
    val pages = mutableStateMapOf<YouTubeFeedKey, YouTubeFeedState>()
    private val loadJobs = mutableMapOf<YouTubeFeedKey, Job>()

    fun selectFeed(feed: YouTubeFeed) {
        selectedFeed = feed
        val key = YouTubeFeedKey.Tab(feed)
        if (feed != YouTubeFeed.SEARCH && pages[key]?.loaded != true && pages[key]?.loading != true) load(key)
    }

    /** Loads [key] if it hasn't been yet (a page opening). */
    fun ensureLoaded(key: YouTubeFeedKey) {
        val current = pages[key]
        if (current == null || (!current.loaded && !current.loading)) load(key)
    }

    fun refresh(key: YouTubeFeedKey) = load(key)

    private fun resetFeeds() {
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        pages.clear()
    }

    private fun load(key: YouTubeFeedKey) {
        loadJobs[key]?.cancel()
        val query = searchQuery
        pages[key] = (pages[key] ?: YouTubeFeedState()).copy(loading = true, failed = false)
        loadJobs[key] = scope.launch {
            try {
                val first = when (key) {
                    is YouTubeFeedKey.Tab -> client.loadFeed(key.feed, query)
                    is YouTubeFeedKey.Browse -> client.loadBrowse(key.target)
                }
                val page = visiblePage(key, first) { it.continuation }
                pages[key] = YouTubeFeedState(shelves = page.shelves, continuation = page.continuation, loaded = true)
                brand(page.shelves.flatMap { it.videos })
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeSignedOutException) {
                pages[key] = (pages[key] ?: YouTubeFeedState()).copy(loading = false)
                expired()
            } catch (e: Exception) {
                pages[key] = (pages[key] ?: YouTubeFeedState()).copy(loading = false, failed = true, loaded = true)
            }
        }
    }

    private fun expired() {
        signedOut = true
        signedIn = authStore.load().isSignedIn
    }

    /** The next page of [key]: the feed's own, or (search) its single row's. */
    fun loadMore(key: YouTubeFeedKey) {
        val state = pages[key] ?: return
        if (state.continuation == null && state.shelves.singleOrNull()?.continuation != null) return loadMoreInShelf(key, 0)
        val token = state.continuation ?: return
        if (state.loading || state.loadingMore) return
        pages[key] = state.copy(loadingMore = true)
        scope.launch {
            val page = fetchMore(key.feed, token)?.let { visiblePage(key, it) { next -> next.continuation } }
            val current = pages[key] ?: return@launch
            pages[key] = when {
                // Reloaded (a new search, a refresh) while this page loaded: it isn't theirs.
                current.continuation != token -> current
                page == null -> current.copy(loadingMore = false)
                else -> current.copy(shelves = appendPage(current.shelves, page), continuation = page.continuation, loadingMore = false)
            }
            page?.let { brand(it.shelves.flatMap { shelf -> shelf.videos }) }
        }
    }

    /** More of one row, when it pages on its own (the TV home's rows, search results). */
    fun loadMoreInShelf(key: YouTubeFeedKey, shelfIndex: Int) {
        val state = pages[key] ?: return
        val token = state.shelves.getOrNull(shelfIndex)?.continuation ?: return
        if (shelfIndex in state.loadingShelves) return
        pages[key] = state.copy(loadingShelves = state.loadingShelves + shelfIndex)
        scope.launch {
            val page = fetchMore(key.feed, token)?.let { first ->
                visiblePage(key, first) { next -> next.continuation ?: next.shelves.firstNotNullOfOrNull { it.continuation } }
            }
            val current = pages[key] ?: return@launch
            val shelves = current.shelves.toMutableList()
            val shelf = shelves.getOrNull(shelfIndex)?.takeIf { it.continuation == token }
            if (page != null && shelf != null) {
                shelves[shelfIndex] = shelf.copy(
                    videos = (shelf.videos + page.shelves.flatMap { it.videos }).distinctBy { it.videoId },
                    links = (shelf.links + page.shelves.flatMap { it.links }).distinctBy { it.target },
                    continuation = page.continuation ?: page.shelves.firstNotNullOfOrNull { it.continuation }
                )
            }
            pages[key] = current.copy(shelves = shelves, loadingShelves = current.loadingShelves - shelfIndex)
            page?.let { brand(it.shelves.flatMap { s -> s.videos }) }
        }
    }

    private suspend fun fetchMore(feed: YouTubeFeed?, token: String): YouTubePage? = try {
        client.loadMore(feed, token)
    } catch (e: CancellationException) {
        throw e
    } catch (e: YouTubeSignedOutException) {
        expired()
        null
    } catch (e: Exception) {
        null
    }

    /** [page] as the filter shows it; when it hides all of a page, the next ones are read instead (up to 3). */
    private suspend fun visiblePage(key: YouTubeFeedKey, page: YouTubePage, nextToken: (YouTubePage) -> String?): YouTubePage {
        var current = page
        repeat(MAX_HIDDEN_PAGES) {
            val shown = shown(key, current)
            val token = nextToken(current)
            if (shown.shelves.isNotEmpty() || token == null) return shown.copy(continuation = token)
            current = fetchMore(key.feed, token) ?: return shown.copy(continuation = token)
        }
        return shown(key, current).copy(continuation = nextToken(current))
    }

    /**
     * [page] through the feed filter. An opened page also drops "View all" links back to itself,
     * puts a channel's row of its own tabs first and leaves out its Releases tab (albums aren't
     * tiles Fetch reads yet), as the TV does.
     */
    private fun shown(key: YouTubeFeedKey, page: YouTubePage): YouTubePage {
        val filtered = settings.feedFilter.apply(page, key.feed ?: YouTubeFeed.HOME)
        if (key !is YouTubeFeedKey.Browse) return filtered
        val shelves = filtered.shelves.mapNotNull { shelf ->
            val links = shelf.links.filter { it.tab != YouTubeChannelTab.RELEASES }
            val viewAll = shelf.viewAll?.takeIf { it.browseId != key.target.browseId }
            shelf.copy(viewAll = viewAll, links = links).takeIf { !it.isEmpty() }
        }
        val (tabs, rest) = shelves.partition { shelf ->
            shelf.videos.isEmpty() && shelf.links.isNotEmpty() &&
                shelf.links.all { it.kind == YouTubeLinkKind.CHANNEL && it.target.browseId == key.target.browseId }
        }
        return filtered.copy(shelves = tabs + rest)
    }

    /** With DeArrow on, swaps titles and thumbnails for the community's once they arrive. */
    private fun brand(videos: List<YouTubeVideo>) {
        if (!settings.deArrowEnabled || videos.isEmpty()) return
        scope.launch {
            val branding = runCatching { deArrow.branding(videos.map { it.videoId }) }.getOrDefault(emptyMap())
            if (branding.isEmpty()) return@launch
            pages.keys.toList().forEach { key ->
                val state = pages[key] ?: return@forEach
                pages[key] = state.copy(shelves = state.shelves.map { shelf ->
                    shelf.copy(videos = shelf.videos.map { it.withBranding(branding[it.videoId]) })
                })
            }
            homeRow = homeRow.map { it.withBranding(branding[it.videoId]) }
        }
    }

    // --- Search ---

    var searchQuery by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var localData by mutableStateOf(dataStore.load())
        private set
    private var suggestJob: Job? = null

    val recentSearches: List<String> get() = localData.recentSearches

    fun onSearchQueryChange(query: String) {
        searchQuery = query
        suggestJob?.cancel()
        if (query.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = scope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            suggestions = client.searchSuggestions(query)
        }
    }

    fun search(query: String = searchQuery) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        searchQuery = trimmed
        suggestJob?.cancel()
        suggestions = emptyList()
        saveLocal(localData.copy(recentSearches = addRecentSearch(localData.recentSearches, trimmed)))
        selectedFeed = YouTubeFeed.SEARCH
        load(YouTubeFeedKey.Tab(YouTubeFeed.SEARCH))
    }

    fun clearRecentSearches() = saveLocal(localData.copy(recentSearches = emptyList()))

    private fun saveLocal(data: YouTubeLocalData) {
        localData = data
        runCatching { dataStore.save(data) }
    }

    // --- Home row ---

    /** The newest subscription videos for Home's row; empty when the row shouldn't show. */
    var homeRow by mutableStateOf<List<YouTubeVideo>>(emptyList())
        private set
    private var homeRowFetchedAt = 0L
    private var homeRowJob: Job? = null

    val homeRowShown: Boolean
        get() = youTubeHomeRowAllowed(settings.homeRowEnabled, signedIn, host.restricted) && homeRow.isNotEmpty()

    /** Home is showing: fetches the subscriptions feed if the one it has is older than ten minutes. */
    fun onHomeVisible() {
        if (!youTubeHomeRowAllowed(settings.homeRowEnabled, signedIn, host.restricted)) return
        if (now() - homeRowFetchedAt in 0 until YOUTUBE_HOME_ROW_CACHE_TTL_MS) return
        if (homeRowJob?.isActive == true) return
        homeRowJob = scope.launch {
            // A failure (signed out, offline) leaves the row out until the cache time has passed.
            homeRowFetchedAt = now()
            val page = try {
                client.loadFeed(YouTubeFeed.SUBSCRIPTIONS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeSignedOutException) {
                expired()
                null
            } catch (e: Exception) {
                null
            }
            homeRow = page?.let { youTubeHomeRowVideos(it, settings.feedFilter) }.orEmpty()
            brand(homeRow)
        }
    }

    // --- Tile menu ---

    var playlistPicker: YouTubePlaylistPicker? by mutableStateOf(null)
        private set

    fun removeFromHistory(video: YouTubeVideo) {
        val token = video.removeFromHistoryToken ?: return
        feedback(video, token, "Removed from watch history")
    }

    fun markNotInterested(video: YouTubeVideo) {
        val token = video.notInterestedToken ?: return
        feedback(video, token, "Got it. You'll see fewer videos like this.")
    }

    private fun feedback(video: YouTubeVideo, token: String, done: String) {
        scope.launch {
            val ok = action { client.sendFeedback(token) }
            if (ok) {
                removeEverywhere(video.videoId)
                host.message(done)
            }
        }
    }

    /** Adds [video] to Watch later, or (on the Watch later page) takes it out. */
    fun setWatchLater(video: YouTubeVideo, save: Boolean) {
        scope.launch {
            if (action { client.setInPlaylist(YouTubeClient.WATCH_LATER_PLAYLIST_ID, video.videoId, save) }) {
                if (!save) removeFromPage(YouTubeClient.WATCH_LATER_BROWSE_ID, video.videoId)
                host.message(if (save) "Saved to Watch later" else "Removed from Watch later")
            }
        }
    }

    fun openPlaylistPicker(video: YouTubeVideo) {
        playlistPicker = YouTubePlaylistPicker(video)
        scope.launch {
            val options = try {
                client.loadPlaylistOptions(video.videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (playlistPicker?.video?.videoId != video.videoId) return@launch
            playlistPicker = YouTubePlaylistPicker(video, options, failed = options == null)
        }
    }

    fun closePlaylistPicker() {
        playlistPicker = null
    }

    fun togglePlaylist(option: YouTubePlaylistOption) {
        val picker = playlistPicker ?: return
        val add = !option.containsVideo
        fun mark(contains: Boolean) {
            val current = playlistPicker?.takeIf { it.video.videoId == picker.video.videoId } ?: return
            playlistPicker = current.copy(options = current.options?.map { if (it.playlistId == option.playlistId) it.copy(containsVideo = contains) else it })
        }
        mark(add)
        scope.launch {
            if (action { client.setInPlaylist(option.playlistId, picker.video.videoId, add) }) {
                if (!add) removeFromPage("VL" + option.playlistId, picker.video.videoId)
                host.message(if (add) "Saved to ${option.title}" else "Removed from ${option.title}")
            } else {
                mark(!add)
            }
        }
    }

    fun createPlaylist(title: String, privacy: YouTubePlaylistPrivacy) {
        val picker = playlistPicker ?: return
        val name = title.trim().takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            val id = try {
                client.createPlaylist(name, picker.video.videoId, privacy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.message("YouTube didn't accept that. Try again.")
                return@launch
            }
            val current = playlistPicker
            if (current?.video?.videoId == picker.video.videoId) {
                playlistPicker = current.copy(options = current.options.orEmpty() + YouTubePlaylistOption(id, name, containsVideo = true))
            }
            host.message("Saved to $name")
        }
    }

    /** Runs a YouTube action; false (with a message) if YouTube refused. */
    private suspend fun action(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: YouTubeSignedOutException) {
        expired()
        false
    } catch (e: Exception) {
        host.message("YouTube didn't accept that. Try again.")
        false
    }

    private fun removeEverywhere(videoId: String) {
        pages.keys.toList().forEach { key ->
            val state = pages[key] ?: return@forEach
            pages[key] = state.copy(shelves = state.shelves.map { s -> s.copy(videos = s.videos.filterNot { it.videoId == videoId }) }.filterNot { it.isEmpty() })
        }
        homeRow = homeRow.filterNot { it.videoId == videoId }
    }

    private fun removeFromPage(browseId: String, videoId: String) {
        pages.keys.filterIsInstance<YouTubeFeedKey.Browse>().filter { it.target.browseId == browseId }.forEach { key ->
            val state = pages[key] ?: return@forEach
            pages[key] = state.copy(shelves = state.shelves.map { s -> s.copy(videos = s.videos.filterNot { it.videoId == videoId }) })
        }
    }

    // --- Playback ---

    var nowPlaying: YouTubeNowPlaying? by mutableStateOf(null)
        private set
    private var plays = 0

    /**
     * Plays [video] in the built-in player or mpv's window, as the profile's Default Player says
     * ([builtIn] keeps the player autoplay came from): from where it was left, skipping
     * SponsorBlock segments, reporting it to the account's history, and going on to YouTube's Up
     * Next pick when it ends with autoplay on.
     */
    fun play(video: YouTubeVideo, builtIn: Boolean? = null) {
        if (host.restricted) {
            host.message("YouTube isn't available on this profile")
            return
        }
        val play = ++plays
        scope.launch {
            val useBuiltIn = builtIn ?: host.chooseBuiltIn() ?: return@launch
            val yt = settings
            val modes = yt.activeModes
            val segments = async {
                if (yt.sponsorBlockEnabled) sponsorBlock.segments(video.videoId, YouTubePlayback.categoriesToFetch(modes)) else emptyList()
            }
            // Signed in: history links, caption tracks and the storyboard for seek bar previews.
            val playerInfo = async { if (signedIn) client.loadPlayerInfo(video.videoId) else null }
            val watchNext = async {
                if (!signedIn) null else runCatching { client.loadWatchNext(video.videoId, video.playlistId, video.playlistIndex) }.getOrNull()
            }
            val votes = async { if (yt.returnDislikeEnabled) returnDislike.votes(video.videoId) else null }
            val start = youTubeResumeStart(localData.resume.firstOrNull { it.videoId == video.videoId }, video.watchedPercent)
            // Captions left on last time: mpv's window picks yt-dlp's track in that language; the
            // built-in player shows YouTube's own track once it's known (below), as the TV does.
            val captionPreference = localData.captions
            val options = YouTubePlayback.mpvOptions(
                host.playerSettings, yt, captions = if (useBuiltIn) YouTubeCaptionPreference() else captionPreference
            )
            cancelUpNext()
            val stream = Stream(
                name = "YouTube", title = video.title, description = null, url = video.watchUrl, ytId = null,
                infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "YouTube", addonLogo = null
            )
            nowPlaying = YouTubeNowPlaying(video)

            // This playback's own skips, history and position, so a video started meanwhile can't mix them up.
            var planner: SkipPlanner? = null
            var history: YouTubeWatchSession? = null
            var lastSample: MpvProgress.Sample? = null
            var lastResumeSave = 0L
            var lastReport = 0L
            var extras: YouTubePlayerExtras? = null
            val osd = MpvOsdNote()
            val onPosition: (Double) -> MpvProgress.Seek? = { position ->
                planner?.at(position)?.let { segment ->
                    // The built-in player offers to go back and watch it (the TV's "undo skip").
                    extras?.let { e -> scope.launch { e.skipNotice = YouTubeSkipNotice(segment.displayLabel, segment.startSec, now()) } }
                    MpvProgress.Seek(segment.endSec, "Skipped ${segment.displayLabel}")
                }
            }
            val onSample: (MpvProgress.Sample) -> Unit = { sample ->
                lastSample = sample
                val t = now()
                if (t - lastResumeSave >= RESUME_SAVE_INTERVAL_MS) {
                    lastResumeSave = t
                    scope.launch { saveResume(video.videoId, sample) }
                }
                if (t - lastReport >= HISTORY_REPORT_INTERVAL_MS) {
                    lastReport = t
                    history?.let { session -> scope.launch { client.reportWatchTime(session, sample.positionSec.toFloat(), sample.durationSec.toFloat()) } }
                }
            }

            // Playback starts first; SponsorBlock, history and Up Next join as they arrive.
            val session = if (useBuiltIn) host.openBuiltInPlayer() else null
            var load = 0
            val follow: suspend () -> MpvProgress.Ending
            if (session != null) {
                session.title = video.title
                session.target = null
                session.stream = stream
                session.sources = emptyList()
                session.nextEpisode = null
                session.upNextDismissed = false
                session.addonSubtitles = emptyList()
                session.segments = emptyList()
                session.autoSkip = emptySet()
                session.skipButton = true
                session.description = listOfNotNull(video.channelName, video.detail).joinToString(" · ").ifBlank { null }
                load = ++session.loads
                extras = YouTubePlayerExtras(video, load).also { session.youTube = it }
                session.mpv.load(video.watchUrl, video.title, start, options, emptyMap())
                follow = { session.mpv.follow(onPosition = onPosition, onSample = onSample) }
            } else {
                host.closePlayer()
                when (val result = ExternalPlayer.play(stream, video.title, start = start, options = options)) {
                    is ExternalPlayer.Result.Started -> {
                        host.message(if (start != null) "Resuming in ${result.player}" else "Opening in ${result.player}")
                        val process = result.process
                        val socket = result.ipcSocket
                        if (process == null || socket == null) {
                            nowPlaying = null
                            return@launch
                        }
                        follow = { MpvProgress.followToEnd(socket, process, onPosition = onPosition, onConnected = osd::attach, onSample = onSample) }
                    }
                    is ExternalPlayer.Result.Failed -> {
                        nowPlaying = null
                        host.message(result.message)
                        return@launch
                    }
                }
            }
            val current = { session == null || (host.currentPlayer === session && session.loads == load) }

            launch {
                val fetched = segments.await()
                val skips: List<Segment> = YouTubePlayback.skipSegments(fetched, modes)
                planner = SkipPlanner(skips, emptySet()).takeUnless { it.isEmpty }
                if (current()) {
                    session?.segments = skips
                    extras?.marks = YouTubePlayback.seekBarMarks(fetched, modes)
                }
            }
            launch {
                val info = playerInfo.await()
                // When playback already resumed, history starts from there rather than from 0.
                if (yt.watchHistoryEnabled) {
                    history = client.newWatchSession(video.videoId, info, startPositionSec = (lastSample?.positionSec ?: 0.0).toFloat())
                }
                val e = extras?.takeIf { current() } ?: return@launch
                e.storyboard = YouTubeStoryboard.parse(info?.storyboardSpec)
                val player = host.playerSettings
                val languages = listOfNotNull(localData.captions.languageCode, player.subtitleLanguage, player.secondarySubtitleLanguage)
                    .ifEmpty { listOf(java.util.Locale.getDefault().language.ifBlank { "en" }) }
                e.captionTracks = YouTubeCaptions.tracks(listOf(info?.captions), translateInto = languages)
                if (captionPreference.enabled && host.playerSettings.subtitleMode != com.fetch.desktop.data.SubtitleMode.OFF) {
                    val track = YouTubeCaptions.pickDefault(e.captionTracks, languages)
                    // mpv adds a subtitle only to a file it has opened (yt-dlp takes a few seconds).
                    var waited = 0
                    while (session != null && (session.mpv.buffering || session.mpv.duration <= 0) && waited < 60_000 && current()) {
                        delay(250)
                        waited += 250
                    }
                    if (track != null && session != null && current()) selectCaption(session, track)
                }
            }
            launch {
                val next = watchNext.await()?.let { settings.feedFilter.apply(it) } ?: return@launch
                if (plays == play) nowPlaying = nowPlaying?.takeIf { it.video.videoId == video.videoId }?.copy(upNext = next.next, watchNext = next)
                extras?.takeIf { current() }?.let { e ->
                    e.actions = next.actions
                    e.chapters = next.chapters
                }
                brandNowPlaying(video.videoId)
            }
            launch {
                val counts = votes.await() ?: return@launch
                if (session != null && current()) {
                    session.description = listOfNotNull(session.description, YouTubePlayback.votesLine(counts)).joinToString("\n")
                    extras?.votes = counts
                }
                // mpv's window: the counts on its OSD as playback starts.
                if (session == null) osd.show(YouTubePlayback.votesLine(counts))
            }

            val ending = withContext(Dispatchers.IO) { follow() }
            val last = ending.last ?: lastSample
            if (last != null) {
                saveResume(video.videoId, last)
                markProgress(video.videoId, last)
                history?.let { session -> scope.launch { client.reportWatchTime(session, last.positionSec.toFloat(), last.durationSec.toFloat()) } }
            }
            // Another video started meanwhile (in the same player or another window): it carries on.
            if (plays != play) return@launch
            val watched = nowPlaying?.watchNext
            nowPlaying = null
            val builtInStill = session != null && host.currentPlayer === session && current()
            val prompt = if (ending.reachedEnd && !host.restricted && (session == null || builtInStill)) {
                YouTubeUpNext.prompt(watched, settings.autoplayEnabled, builtIn = session != null)
            } else {
                null
            }
            if (prompt != null) {
                // The TV's end card: ten seconds to YouTube's pick, or a choice when autoplay is off.
                showUpNext(prompt, session)
            } else if (session != null && ending.reachedEnd && current()) {
                // Played to the end and nothing comes next: close, as mpv's window would.
                host.closePlayer()
            }
        }
    }

    // --- Up Next card ---

    /** The end card counting down to the next video; shown over the built-in player or the app. */
    var upNext: YouTubeUpNextPrompt? by mutableStateOf(null)
        private set
    private var upNextJob: Job? = null

    private fun showUpNext(prompt: YouTubeUpNextPrompt, session: PlayerSession?) {
        upNextJob?.cancel()
        upNext = prompt
        upNextJob = scope.launch {
            while (true) {
                val current = upNext ?: return@launch
                // The built-in player was closed (or plays something else): the card goes with it.
                if (session != null && host.currentPlayer !== session) {
                    upNext = null
                    return@launch
                }
                if (current.due) {
                    playUpNext(current.next ?: return@launch)
                    return@launch
                }
                delay(1_000)
                upNext = upNext?.tick()
            }
        }
    }

    /** Plays [video] from the end card ("Play now", or a related video). */
    fun playUpNext(video: YouTubeVideo) {
        val prompt = upNext ?: return
        cancelUpNext()
        play(video, builtIn = prompt.builtIn && host.currentPlayer != null)
    }

    /** Stops the countdown; the card stays to pick from (the TV stops it when focus moves). */
    fun stopUpNextCountdown() {
        upNext = upNext?.copy(counting = false)
    }

    /** Closes the end card, and the built-in player it was over. */
    fun closeUpNext() {
        val prompt = upNext ?: return
        cancelUpNext()
        if (prompt.builtIn) host.closePlayer()
    }

    private fun cancelUpNext() {
        upNextJob?.cancel()
        upNextJob = null
        upNext = null
    }

    // --- The built-in player's YouTube actions ---

    private val playingExtras: YouTubePlayerExtras? get() = host.currentPlayer?.youTubeExtras()

    /** Rates the playing video. Shown at once, and undone if YouTube refuses (the TV's). */
    fun ratePlaying(status: YouTubeLikeStatus) {
        val extras = playingExtras ?: return
        val actions = extras.actions?.takeIf { it.likeStatus != null } ?: return
        changeActions(extras, actions, actions.copy(likeStatus = status)) { client.setLikeStatus(extras.video.videoId, status) }
    }

    /** Subscribes to the playing video's channel, or unsubscribes. Shown at once, and undone if YouTube refuses. */
    fun togglePlayingSubscription() {
        val extras = playingExtras ?: return
        val actions = extras.actions ?: return
        val channelId = actions.channelId ?: return
        val subscribed = actions.subscribed ?: return
        changeActions(extras, actions, actions.copy(subscribed = !subscribed)) {
            client.setSubscribed(channelId, subscribed = !subscribed, params = if (subscribed) actions.unsubscribeParams else actions.subscribeParams)
        }
    }

    private fun changeActions(extras: YouTubePlayerExtras, before: YouTubeVideoActions, after: YouTubeVideoActions, request: suspend () -> Unit) {
        extras.actions = after
        extras.actionFailed = false
        scope.launch {
            val done = try {
                request()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeSignedOutException) {
                expired()
                false
            } catch (e: Exception) {
                false
            }
            // Only undone if nothing changed it since.
            if (!done && extras.actions == after) {
                extras.actions = before
                extras.actionFailed = true
            }
        }
    }

    /** Leaves the player for the playing video's channel. */
    fun openPlayingChannel() {
        val extras = playingExtras ?: return
        val channelId = extras.actions?.channelId ?: extras.video.channelId ?: return
        val name = extras.actions?.channelName ?: extras.video.channelName
        host.closePlayer()
        host.openBrowse(YouTubeBrowseTarget(browseId = channelId, title = name))
    }

    /** The playing video's Save list: the channel's playlists, read when it opens (the TV's player Save list). */
    fun loadPlayingPlaylists() {
        val extras = playingExtras ?: return
        extras.playlists = null
        extras.playlistsFailed = false
        scope.launch {
            val options = try {
                client.loadPlaylistOptions(extras.video.videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            extras.playlists = options
            extras.playlistsFailed = options == null
        }
    }

    /** Saves the playing video to [option], or takes it out. Shown at once, and undone if YouTube refuses. */
    fun togglePlayingPlaylist(option: YouTubePlaylistOption) {
        val extras = playingExtras ?: return
        val add = !option.containsVideo
        fun mark(contains: Boolean) {
            extras.playlists = extras.playlists?.map { if (it.playlistId == option.playlistId) it.copy(containsVideo = contains) else it }
        }
        mark(add)
        scope.launch {
            if (action { client.setInPlaylist(option.playlistId, extras.video.videoId, add) }) {
                if (!add) removeFromPage("VL" + option.playlistId, extras.video.videoId)
                host.currentPlayer?.mpv?.showText(if (add) "Saved to ${option.title}" else "Removed from ${option.title}")
            } else {
                mark(!add)
            }
        }
    }

    fun createPlayingPlaylist(title: String, privacy: YouTubePlaylistPrivacy) {
        val extras = playingExtras ?: return
        val name = title.trim().takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            val id = try {
                client.createPlaylist(name, extras.video.videoId, privacy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.currentPlayer?.mpv?.showText("YouTube didn't accept that. Try again.")
                return@launch
            }
            extras.playlists = extras.playlists.orEmpty() + YouTubePlaylistOption(id, name, containsVideo = true)
            host.currentPlayer?.mpv?.showText("Saved to $name")
        }
    }

    /**
     * Shows [track] (a YouTube caption, loaded into mpv as WebVTT), or turns captions off with null.
     * Remembered for the next video, as on TV.
     */
    fun selectCaption(session: PlayerSession, track: YouTubeCaptionTrack?) {
        val extras = session.youTubeExtras() ?: return
        saveLocal(localData.copy(captions = YouTubeCaptionPreference(enabled = track != null, languageCode = track?.languageCode ?: localData.captions.languageCode)))
        extras.selectedCaption = track?.id
        if (track == null) {
            session.mpv.selectSubtitle(null)
            return
        }
        val existing = extras.addedCaptions[track.id]?.takeIf { id -> session.mpv.tracks.any { it.type == "sub" && it.id == id } }
        if (existing != null) {
            session.mpv.selectSubtitle(existing)
            return
        }
        val url = track.webVttUrl ?: return
        val before = session.mpv.tracks.filter { it.type == "sub" }.map { it.id }.toSet()
        session.mpv.addSubtitle(url, track.label, track.languageCode)
        // Learn mpv's id for it once the track list updates, so picking it again reuses it.
        scope.launch {
            repeat(20) {
                delay(250)
                session.mpv.tracks.firstOrNull { it.type == "sub" && it.id !in before }?.let { added ->
                    extras.addedCaptions[track.id] = added.id
                    return@launch
                }
            }
        }
    }

    private suspend fun brandNowPlaying(videoId: String) {
        if (!settings.deArrowEnabled) return
        val next = nowPlaying?.upNext ?: return
        val branding = runCatching { deArrow.branding(listOf(next.videoId)) }.getOrDefault(emptyMap())[next.videoId] ?: return
        val playing = nowPlaying ?: return
        if (playing.video.videoId == videoId) nowPlaying = playing.copy(upNext = playing.upNext?.withBranding(branding))
    }

    private fun saveResume(videoId: String, sample: MpvProgress.Sample) {
        if (sample.durationSec <= 0) return
        saveLocal(
            localData.copy(
                resume = updateResumePoints(localData.resume, videoId, (sample.positionSec * 1000).toLong(), (sample.durationSec * 1000).toLong(), now())
            )
        )
    }

    /** The finished video's card shows how far it got, as on TV. */
    private fun markProgress(videoId: String, sample: MpvProgress.Sample) {
        if (sample.durationSec <= 0) return
        val percent = (sample.positionSec / sample.durationSec * 100).toInt().coerceIn(0, 100)
        fun List<YouTubeVideo>.marked() = map { if (it.videoId == videoId) it.copy(watchedPercent = percent) else it }
        pages.keys.toList().forEach { key ->
            val state = pages[key] ?: return@forEach
            pages[key] = state.copy(shelves = state.shelves.map { it.copy(videos = it.videos.marked()) })
        }
        homeRow = homeRow.marked()
    }

    private companion object {
        const val SUGGEST_DEBOUNCE_MS = 250L
        const val MAX_HIDDEN_PAGES = 3
        const val HISTORY_REPORT_INTERVAL_MS = 30_000L
        const val RESUME_SAVE_INTERVAL_MS = 15_000L
    }
}
