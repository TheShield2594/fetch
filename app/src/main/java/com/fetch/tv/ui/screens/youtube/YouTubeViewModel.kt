package com.fetch.tv.ui.screens.youtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.data.local.YouTubeAuthDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.SubtitleStyleSettings
import com.fetch.tv.data.local.YouTubeSettingsDataStore
import com.fetch.tv.data.youtube.YouTubeCaptionCue
import com.fetch.tv.data.youtube.YouTubeCaptionService
import com.fetch.tv.data.youtube.YouTubeCaptionTrack
import com.fetch.tv.data.youtube.pickDefaultCaptionTrack
import com.fetch.tv.data.trailer.InAppYouTubeExtractor
import com.fetch.tv.data.youtube.SponsorBlockService
import com.fetch.tv.data.youtube.YouTubeBrowseTarget
import com.fetch.tv.data.youtube.YouTubeChannelRef
import com.fetch.tv.data.youtube.YouTubeChannelTab
import com.fetch.tv.data.youtube.YouTubeFeed
import com.fetch.tv.data.youtube.YouTubeLikeStatus
import com.fetch.tv.data.youtube.YouTubeLinkKind
import com.fetch.tv.data.youtube.YouTubePage
import com.fetch.tv.data.youtube.YouTubePlaylistOption
import com.fetch.tv.data.youtube.YouTubeService
import com.fetch.tv.data.youtube.YouTubeShelf
import com.fetch.tv.data.youtube.YouTubeSignedOutException
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeVideoActions
import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.data.youtube.YouTubeWatchSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

data class YouTubeFeedState(
    val shelves: List<YouTubeShelf> = emptyList(),
    val continuation: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadingShelves: Set<Int> = emptySet(),
    val failed: Boolean = false,
    val loaded: Boolean = false
)

data class YouTubePlaybackState(
    val video: YouTubeVideo,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val skipSegments: List<YouTubeSkipSegment> = emptyList(),
    val captionTracks: List<YouTubeCaptionTrack> = emptyList(),
    /** The track showing; null while captions are off. */
    val selectedCaption: YouTubeCaptionTrack? = null,
    val captionCues: List<YouTubeCaptionCue> = emptyList(),
    val captionsLoading: Boolean = false,
    /** The selected track gave no captions from any of its links. */
    val captionsFailed: Boolean = false,
    val captionStyle: SubtitleStyleSettings = SubtitleStyleSettings(),
    /** YouTube's pick to play next; the end card counts down to it when autoplay is on. */
    val upNext: YouTubeVideo? = null,
    val related: List<YouTubeVideo> = emptyList(),
    val autoplay: Boolean = YouTubeSettingsDataStore.DEFAULT_AUTOPLAY_ENABLED,
    /** The like and subscribe state, once the watch page has loaded. */
    val actions: YouTubeVideoActions? = null,
    /** YouTube refused the last like or subscribe; the change was undone. */
    val actionFailed: Boolean = false
)

/**
 * A video's long-press menu, and what it learns about the video once open: the channel's
 * playlists (to save it to, or take it out of) and whether the channel is subscribed to.
 */
data class YouTubeVideoMenuState(
    val video: YouTubeVideo,
    /** The page under the menu, when it's a playlist; the menu can take the video out of it. */
    val openPlaylist: YouTubeBrowseTarget? = null,
    /** Null while loading, and when they couldn't be read ([playlistsFailed]). */
    val playlists: List<YouTubePlaylistOption>? = null,
    val playlistsFailed: Boolean = false,
    /** The subscribe state of the video's channel, once the watch page has loaded. */
    val actions: YouTubeVideoActions? = null,
    /** The list of playlists to save to is showing, in place of the menu. */
    val pickingPlaylist: Boolean = false
) {
    val watchLater: YouTubePlaylistOption?
        get() = playlists?.firstOrNull { it.isWatchLater }

    /** The video is in Watch Later; before the playlists load, only known on Watch Later's own page. */
    val inWatchLater: Boolean
        get() = watchLater?.containsVideo ?: (openPlaylist?.browseId == YouTubeService.WATCH_LATER_BROWSE_ID)

    /** The open playlist, when it's one of the channel's own: only those can be edited. */
    val editablePlaylist: YouTubePlaylistOption?
        get() = openPlaylist?.let { page ->
            playlists?.firstOrNull { YouTubeService.PLAYLIST_BROWSE_PREFIX + it.playlistId == page.browseId }
        }

    /** The open page is Liked videos, which the video leaves by being unliked. */
    val inLikedVideos: Boolean
        get() = openPlaylist?.browseId == YouTubeService.LIKED_BROWSE_ID
}

/** A short message about something done from the menu, shown once. */
sealed interface YouTubeNotice {
    data class Saved(val playlist: String) : YouTubeNotice
    data class Removed(val playlist: String) : YouTubeNotice
    data object RemovedFromLiked : YouTubeNotice
    data class Subscribed(val channel: String?) : YouTubeNotice
    data class Unsubscribed(val channel: String?) : YouTubeNotice
    data object Failed : YouTubeNotice
}

/** A page opened over the tabs: a channel, a playlist, or all of a row. Back closes it. */
data class YouTubeBrowsePage(
    val target: YouTubeBrowseTarget,
    val feed: YouTubeFeedState = YouTubeFeedState()
)

data class YouTubeUiState(
    val selectedFeed: YouTubeFeed = YouTubeFeed.HOME,
    val feeds: Map<YouTubeFeed, YouTubeFeedState> = emptyMap(),
    val searchQuery: String = "",
    val signedOut: Boolean = false,
    val playback: YouTubePlaybackState? = null,
    /** Pages opened from the feeds, the one showing last. */
    val browse: List<YouTubeBrowsePage> = emptyList(),
    val menu: YouTubeVideoMenuState? = null,
    val notice: YouTubeNotice? = null
) {
    val currentFeed: YouTubeFeedState
        get() = browse.lastOrNull()?.feed ?: feeds[selectedFeed] ?: YouTubeFeedState()
}

/** Which list a load is for: one of the tabs, or the page opened [Browse.depth] deep over them. */
private sealed interface FeedKey {
    /** The feed it pages and filters as. Opened pages page through browse, as the home feed does. */
    val feed: YouTubeFeed

    data class Tab(override val feed: YouTubeFeed) : FeedKey

    data class Browse(val depth: Int, val target: YouTubeBrowseTarget) : FeedKey {
        override val feed: YouTubeFeed
            get() = YouTubeFeed.HOME
    }
}

@HiltViewModel
class YouTubeViewModel @Inject constructor(
    private val youTubeService: YouTubeService,
    private val extractor: InAppYouTubeExtractor,
    private val sponsorBlock: SponsorBlockService,
    private val settings: YouTubeSettingsDataStore,
    private val captionService: YouTubeCaptionService,
    private val playerSettings: PlayerSettingsDataStore,
    authDataStore: YouTubeAuthDataStore
) : ViewModel() {
    private companion object {
        /** TV screens are 1080p or 4K; 1080p keeps 4K streams off devices and networks that struggle. */
        const val MAX_VIDEO_HEIGHT = 1080

        /** How often a playing video's position is sent to YouTube history. */
        const val HISTORY_REPORT_INTERVAL_MS = 30_000L

        /** How many pages in a row the feed filter may hide entirely before loading stops for now. */
        const val MAX_HIDDEN_PAGES = 3
    }

    private val _uiState = MutableStateFlow(YouTubeUiState())
    val uiState: StateFlow<YouTubeUiState> = _uiState.asStateFlow()

    private val loadJobs = mutableMapOf<FeedKey, Job>()
    private val loadMoreJobs = mutableListOf<Job>()
    private var playbackJob: Job? = null
    private var captionJob: Job? = null
    private var menuJob: Job? = null
    private var watchSession: YouTubeWatchSession? = null
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var lastReportAtMs = 0L
    private var lastIdentity: Pair<String?, String?>? = null
    private var feedFilter = YouTubeSettingsDataStore.DEFAULT_FEED_FILTER

    init {
        viewModelScope.launch {
            // Signing in, out, or switching channel or profile shows other feeds; start them over.
            authDataStore.state.collect { auth ->
                val signedIn = auth.isSignedIn
                val identity = auth.refreshToken to auth.pageId
                if (identity == lastIdentity && _uiState.value.signedOut == !signedIn) return@collect
                lastIdentity = identity
                // Nothing the old account asked for may land in the new one's feeds.
                cancelFeedLoads()
                menuJob?.cancel()
                _uiState.update {
                    it.copy(signedOut = !signedIn, feeds = emptyMap(), searchQuery = "", browse = emptyList(), menu = null)
                }
                val selected = _uiState.value.selectedFeed
                if (signedIn && selected != YouTubeFeed.SEARCH) load(FeedKey.Tab(selected))
            }
        }
        viewModelScope.launch {
            // Turning Shorts, live streams or watched videos on or off loads the feeds again with the new choice.
            settings.feedFilter.collect { filter ->
                if (filter == feedFilter) return@collect
                feedFilter = filter
                cancelFeedLoads()
                val state = _uiState.value
                val selected = state.selectedFeed
                val reload = state.browse.isNotEmpty() || state.feeds[selected] != null &&
                    (selected != YouTubeFeed.SEARCH || state.searchQuery.isNotBlank())
                // Opened pages stay open; each loads again when it next shows.
                _uiState.update { it.copy(feeds = emptyMap(), browse = it.browse.map { page -> YouTubeBrowsePage(page.target) }) }
                if (!state.signedOut && reload) load(currentKey())
            }
        }
    }

    private fun cancelFeedLoads() {
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        loadMoreJobs.forEach { it.cancel() }
        loadMoreJobs.clear()
    }

    fun selectFeed(feed: YouTubeFeed) {
        // A tab closes the pages opened over the tabs.
        loadJobs.keys.filterIsInstance<FeedKey.Browse>().forEach { loadJobs.remove(it)?.cancel() }
        _uiState.update { it.copy(selectedFeed = feed, browse = emptyList()) }
        val state = _uiState.value.feeds[feed]
        if (feed != YouTubeFeed.SEARCH && (state == null || state.failed)) load(FeedKey.Tab(feed))
    }

    fun refresh() = load(currentKey())

    /** Opens [target] over the tabs: a channel, a playlist, or all of a row. */
    fun openBrowse(target: YouTubeBrowseTarget) {
        _uiState.update { it.copy(browse = it.browse + YouTubeBrowsePage(target)) }
        load(currentKey())
    }

    /** Opens [channel], one of a collaboration's, or else the video's uploader. */
    fun openChannel(video: YouTubeVideo, channel: YouTubeChannelRef? = null) {
        val channelId = channel?.channelId ?: video.channelId ?: video.channels.firstOrNull()?.channelId ?: return
        // A collaboration's byline names all of its channels; the channel's own name is better.
        val name = channel?.name ?: video.channels.firstOrNull { it.channelId == channelId }?.name ?: video.channelName
        openBrowse(YouTubeBrowseTarget(browseId = channelId, title = name))
    }

    /** Back from an opened page, to the page or tab under it. */
    fun closeBrowse() {
        val key = currentKey() as? FeedKey.Browse ?: return
        loadJobs.remove(key)?.cancel()
        _uiState.update { it.copy(browse = it.browse.dropLast(1)) }
        // The filter changing while it was covered leaves what's under it to load now.
        val below = currentKey()
        val state = feedState(below)
        val needsLoad = if (state == null) {
            below is FeedKey.Tab && (below.feed != YouTubeFeed.SEARCH || _uiState.value.searchQuery.isNotBlank())
        } else {
            !state.loaded && !state.isLoading
        }
        if (needsLoad && !_uiState.value.signedOut) load(below)
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun submitSearch() {
        if (_uiState.value.searchQuery.isBlank()) return
        load(FeedKey.Tab(YouTubeFeed.SEARCH))
    }

    /** Loads the feed's next page, when the user nears its end. */
    fun loadMore() {
        val key = currentKey()
        val state = feedState(key) ?: return
        // Search sends its results as one row that pages on its own, rather than the page.
        if (state.continuation == null && state.shelves.singleOrNull()?.continuation != null) return loadMoreInShelf(0)
        val token = state.continuation ?: return
        if (state.isLoading || state.isLoadingMore) return
        updateFeed(key) { it.copy(isLoadingMore = true) }
        trackLoadMore(viewModelScope.launch {
            val page = fetchMore(key.feed, token)?.let { visiblePage(key, it) { next -> next.continuation } }
            updateExistingFeed(key) { current ->
                when {
                    // The feed was reloaded (a new search, a refresh) while this page loaded; it isn't theirs.
                    current.continuation != token -> current
                    page == null -> current.copy(isLoadingMore = false)
                    else -> current.copy(
                        shelves = appendPage(current.shelves, page),
                        continuation = page.continuation,
                        isLoadingMore = false
                    )
                }
            }
        })
    }

    /** Loads more of one row, when focus nears its last video. */
    fun loadMoreInShelf(shelfIndex: Int) {
        val key = currentKey()
        val state = feedState(key) ?: return
        val token = state.shelves.getOrNull(shelfIndex)?.continuation ?: return
        if (shelfIndex in state.loadingShelves) return
        updateFeed(key) { it.copy(loadingShelves = it.loadingShelves + shelfIndex) }
        trackLoadMore(viewModelScope.launch {
            val page = fetchMore(key.feed, token)?.let { first ->
                visiblePage(key, first) { next -> next.continuation ?: next.shelves.firstNotNullOfOrNull { it.continuation } }
            }
            updateExistingFeed(key) { current ->
                val shelves = current.shelves.toMutableList()
                // After a reload the index may name another row; only the row that asked takes the page.
                val shelf = shelves.getOrNull(shelfIndex)?.takeIf { it.continuation == token }
                if (page != null && shelf != null) {
                    shelves[shelfIndex] = shelf.copy(
                        videos = (shelf.videos + page.shelves.flatMap { it.videos }).distinctBy { it.videoId },
                        links = (shelf.links + page.shelves.flatMap { it.links }).distinctBy { it.target },
                        continuation = page.continuation ?: page.shelves.firstNotNullOfOrNull { it.continuation }
                    )
                }
                current.copy(shelves = shelves, loadingShelves = current.loadingShelves - shelfIndex)
            }
        })
    }

    private fun trackLoadMore(job: Job) {
        loadMoreJobs.removeAll { !it.isActive }
        loadMoreJobs += job
    }

    fun play(video: YouTubeVideo) {
        playbackJob?.cancel()
        captionJob?.cancel()
        finishWatchSession()
        _uiState.update { it.copy(playback = YouTubePlaybackState(video)) }
        playbackJob = viewModelScope.launch {
            val segments = async {
                if (setting(true) { settings.isSponsorBlockEnabled() }) sponsorBlock.segments(video.videoId) else emptyList()
            }
            val playerInfo = async { youTubeService.loadPlayerInfo(video.videoId) }
            val watchNext = async { orNull { youTubeService.loadWatchNext(video.videoId) } }
            val autoplay = async { setting(YouTubeSettingsDataStore.DEFAULT_AUTOPLAY_ENABLED) { settings.autoplayEnabled.first() } }
            val source = try {
                extractor.extractPlaybackSource(video.watchUrl, maxVideoHeight = MAX_VIDEO_HEIGHT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            updatePlayback(video) { playback ->
                if (source == null || source.videoUrl.isBlank()) {
                    playback.copy(isLoading = false, failed = true)
                } else {
                    playback.copy(videoUrl = source.videoUrl, audioUrl = source.audioUrl, isLoading = false)
                }
            }
            val info = playerInfo.await()
            if (setting(true) { settings.isWatchHistoryEnabled() }) {
                watchSession = youTubeService.newWatchSession(video.videoId, info, startPositionSec = 0f)
            }
            // Captions are extra: if setting them up fails, the video plays without them.
            if (source != null) orNull { setUpCaptions(video, source.captionsJson, info?.captions) }
            val skipSegments = segments.await()
            updatePlayback(video) { it.copy(skipSegments = skipSegments) }
            val next = watchNext.await()?.let { feedFilter.apply(it) }
            val autoplayEnabled = autoplay.await()
            updatePlayback(video) {
                it.copy(upNext = next?.next, related = next?.related.orEmpty(), autoplay = autoplayEnabled, actions = next?.actions)
            }
        }
    }

    /** Shows [track], or turns captions off with null. Remembered for the next video. */
    fun selectCaption(track: YouTubeCaptionTrack?) {
        val video = _uiState.value.playback?.video ?: return
        captionJob?.cancel()
        viewModelScope.launch { orNull { settings.setCaptionPreference(track != null, track?.languageCode) } }
        updatePlayback(video) {
            it.copy(selectedCaption = track, captionCues = emptyList(), captionsFailed = false, captionsLoading = track != null)
        }
        if (track == null) return
        captionJob = viewModelScope.launch {
            val cues = captionService.cues(track)
            updatePlayback(video) { playback ->
                if (playback.selectedCaption?.id != track.id) return@updatePlayback playback
                playback.copy(captionCues = cues, captionsLoading = false, captionsFailed = cues.isEmpty())
            }
        }
    }

    private suspend fun setUpCaptions(video: YouTubeVideo, extractorCaptions: String?, signedInCaptions: JsonElement?) {
        val style = playerSettings.playerSettings.first().subtitleStyle
        val (enabled, savedLanguage) = settings.captionPreference()
        // The last language picked here, then the subtitle languages from Fetch's playback settings.
        val languages = listOfNotNull(savedLanguage, style.preferredLanguage, style.secondaryPreferredLanguage)
        val blocks = listOf(
            extractorCaptions?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
            signedInCaptions
        )
        val tracks = captionService.tracks(blocks, translateInto = languages)
        updatePlayback(video) { it.copy(captionTracks = tracks, captionStyle = style) }
        if (enabled) pickDefaultCaptionTrack(tracks, languages)?.let(::selectCaption)
    }

    /** Called about every quarter second while a video plays. */
    fun onPlaybackProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L || positionMs <= 0L) return
        lastPositionMs = positionMs
        lastDurationMs = durationMs
        val now = System.currentTimeMillis()
        if (watchSession != null && now - lastReportAtMs >= HISTORY_REPORT_INTERVAL_MS) {
            lastReportAtMs = now
            reportHistory()
        }
    }

    fun retryPlayback() {
        _uiState.value.playback?.video?.let(::play)
    }

    fun closePlayback() {
        playbackJob?.cancel()
        captionJob?.cancel()
        playbackJob = null
        markProgressOnCard()
        _uiState.update { it.copy(playback = null) }
    }

    /** Plays [next] from the end card, keeping the finished video's progress on its card. */
    /** Rates the playing video. Shown at once, and undone if YouTube refuses. */
    fun rate(status: YouTubeLikeStatus) {
        val playback = _uiState.value.playback ?: return
        val actions = playback.actions?.takeIf { it.likeStatus != null } ?: return
        changeActions(playback.video, actions, actions.copy(likeStatus = status)) {
            youTubeService.setLikeStatus(playback.video.videoId, status)
        }
    }

    /** Subscribes to the playing video's channel, or unsubscribes. Shown at once, and undone if YouTube refuses. */
    fun toggleSubscription() {
        val playback = _uiState.value.playback ?: return
        val actions = playback.actions ?: return
        val channelId = actions.channelId ?: return
        val subscribed = actions.subscribed ?: return
        changeActions(playback.video, actions, actions.copy(subscribed = !subscribed)) {
            youTubeService.setSubscribed(
                channelId,
                subscribed = !subscribed,
                params = if (subscribed) actions.unsubscribeParams else actions.subscribeParams
            )
        }
    }

    /** Leaves the player for the playing video's channel. */
    fun openPlayingChannel() {
        val playback = _uiState.value.playback ?: return
        val channelId = playback.actions?.channelId ?: playback.video.channelId ?: return
        val name = playback.actions?.channelName ?: playback.video.channelName
        closePlayback()
        openBrowse(YouTubeBrowseTarget(browseId = channelId, title = name))
    }

    private fun changeActions(
        video: YouTubeVideo,
        before: YouTubeVideoActions,
        after: YouTubeVideoActions,
        request: suspend () -> Unit
    ) {
        updatePlayback(video) { it.copy(actions = after, actionFailed = false) }
        viewModelScope.launch {
            val done = orNull { request() } != null
            if (!done) updatePlayback(video) { playback ->
                // Only undone if nothing changed it since.
                if (playback.actions == after) playback.copy(actions = before, actionFailed = true) else playback
            }
        }
    }

    /** Opens [video]'s long-press menu, and reads its playlists and channel for the actions that need them. */
    fun openMenu(video: YouTubeVideo) {
        menuJob?.cancel()
        val openPlaylist = _uiState.value.browse.lastOrNull()?.target
            ?.takeIf { it.browseId.startsWith(YouTubeService.PLAYLIST_BROWSE_PREFIX) }
        _uiState.update { it.copy(menu = YouTubeVideoMenuState(video, openPlaylist)) }
        menuJob = viewModelScope.launch {
            val actions = async { orNull { youTubeService.loadWatchNext(video.videoId).actions } }
            val playlists = orNull { youTubeService.loadPlaylistOptions(video.videoId) }
            updateMenu(video) { it.copy(playlists = playlists, playlistsFailed = playlists == null) }
            val loaded = actions.await()
            updateMenu(video) { it.copy(actions = loaded) }
        }
    }

    fun closeMenu() {
        menuJob?.cancel()
        menuJob = null
        _uiState.update { it.copy(menu = null) }
    }

    /** Shows the list of playlists to save the menu's video to, or goes back to the menu. */
    fun showPlaylistPicker(show: Boolean) {
        _uiState.update { state -> state.copy(menu = state.menu?.copy(pickingPlaylist = show)) }
    }

    /**
     * Saves the menu's video to [option], or takes it out when it's there already. Shown at once,
     * and undone if YouTube refuses. Taken out of the playlist that's open, it leaves its page too.
     */
    fun togglePlaylist(option: YouTubePlaylistOption, closeMenu: Boolean = false) {
        val video = _uiState.value.menu?.video ?: return
        val add = !option.containsVideo
        fun mark(contains: Boolean) = updateMenu(video) { menu ->
            menu.copy(playlists = menu.playlists?.map {
                if (it.playlistId == option.playlistId) it.copy(containsVideo = contains) else it
            })
        }
        mark(add)
        if (closeMenu) closeMenu()
        viewModelScope.launch {
            val done = orNull { youTubeService.setInPlaylist(option.playlistId, video.videoId, inPlaylist = add) } != null
            if (!done) {
                mark(!add)
                notify(YouTubeNotice.Failed)
                return@launch
            }
            if (!add) removeFromPage(YouTubeService.PLAYLIST_BROWSE_PREFIX + option.playlistId, video.videoId)
            notify(if (add) YouTubeNotice.Saved(option.title) else YouTubeNotice.Removed(option.title))
        }
    }

    /** Saves the menu's video to Watch Later, or takes it out, and closes the menu. */
    fun toggleWatchLater() {
        val menu = _uiState.value.menu ?: return
        // Before the playlists load, untitled: the screen names it.
        val option = menu.watchLater
            ?: YouTubePlaylistOption(YouTubeService.WATCH_LATER_PLAYLIST_ID, title = "", containsVideo = menu.inWatchLater)
        togglePlaylist(option, closeMenu = true)
    }

    /** Takes the menu's video out of Liked videos by clearing its like, and closes the menu. */
    fun removeFromLiked() {
        val video = _uiState.value.menu?.video ?: return
        closeMenu()
        viewModelScope.launch {
            val done = orNull { youTubeService.setLikeStatus(video.videoId, YouTubeLikeStatus.INDIFFERENT) } != null
            if (done) removeFromPage(YouTubeService.LIKED_BROWSE_ID, video.videoId)
            notify(if (done) YouTubeNotice.RemovedFromLiked else YouTubeNotice.Failed)
        }
    }

    /** Subscribes to the menu's video's channel, or unsubscribes, and closes the menu. */
    fun toggleMenuSubscription() {
        val actions = _uiState.value.menu?.actions ?: return
        val channelId = actions.channelId ?: return
        val subscribed = actions.subscribed ?: return
        closeMenu()
        viewModelScope.launch {
            val done = orNull {
                youTubeService.setSubscribed(
                    channelId,
                    subscribed = !subscribed,
                    params = if (subscribed) actions.unsubscribeParams else actions.subscribeParams
                )
            } != null
            notify(
                when {
                    !done -> YouTubeNotice.Failed
                    subscribed -> YouTubeNotice.Unsubscribed(actions.channelName)
                    else -> YouTubeNotice.Subscribed(actions.channelName)
                }
            )
        }
    }

    /** The notice was shown. */
    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun notify(notice: YouTubeNotice) {
        _uiState.update { it.copy(notice = notice) }
    }

    private fun updateMenu(video: YouTubeVideo, transform: (YouTubeVideoMenuState) -> YouTubeVideoMenuState) {
        _uiState.update { state ->
            val menu = state.menu?.takeIf { it.video.videoId == video.videoId } ?: return@update state
            state.copy(menu = transform(menu))
        }
    }

    /** Drops [videoId] from the open pages of [browseId], once it's been taken out of that playlist. */
    private fun removeFromPage(browseId: String, videoId: String) {
        _uiState.update { state ->
            state.copy(browse = state.browse.map { page ->
                if (page.target.browseId != browseId) return@map page
                val shelves = page.feed.shelves
                    .map { shelf -> shelf.copy(videos = shelf.videos.filterNot { it.videoId == videoId }) }
                    .filterNot { it.isEmpty() }
                page.copy(feed = page.feed.copy(shelves = shelves))
            })
        }
    }

    fun playNext(next: YouTubeVideo) {
        markProgressOnCard()
        play(next)
    }

    /** Sends the last position to history and shows it on the video's card, without reloading the feed. */
    private fun markProgressOnCard() {
        val video = _uiState.value.playback?.video
        finishWatchSession()
        if (video != null && lastDurationMs > 0L) {
            val percent = (lastPositionMs * 100 / lastDurationMs).toInt().coerceIn(0, 100)
            fun YouTubeFeedState.marked() = copy(shelves = shelves.map { shelf ->
                shelf.copy(videos = shelf.videos.map {
                    if (it.videoId == video.videoId) it.copy(watchedPercent = percent) else it
                })
            })
            _uiState.update { state ->
                state.copy(
                    feeds = state.feeds.mapValues { (_, feed) -> feed.marked() },
                    browse = state.browse.map { it.copy(feed = it.feed.marked()) }
                )
            }
        }
        lastPositionMs = 0L
        lastDurationMs = 0L
    }

    override fun onCleared() {
        finishWatchSession()
        super.onCleared()
    }

    private fun finishWatchSession() {
        if (watchSession != null && lastDurationMs > 0L) reportHistory()
        watchSession = null
        lastReportAtMs = 0L
    }

    private fun reportHistory() {
        val session = watchSession ?: return
        val positionSec = lastPositionMs / 1000f
        val lengthSec = lastDurationMs / 1000f
        // In the service's scope, so the last position still reaches YouTube after this screen is gone.
        youTubeService.reportWatchTimeInBackground(session, positionSec, lengthSec)
    }

    /** A setting's value, or [default] if it can't be read; playback goes on either way. */
    private suspend fun <T> setting(default: T, read: suspend () -> T): T = orNull { read() } ?: default

    /** [block]'s result, or null if it fails for any reason but cancellation. */
    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchMore(feed: YouTubeFeed, token: String): YouTubePage? = try {
        youTubeService.loadMore(feed, token)
    } catch (e: CancellationException) {
        throw e
    } catch (e: YouTubeSignedOutException) {
        _uiState.update { it.copy(signedOut = true) }
        null
    } catch (e: Exception) {
        null
    }

    /**
     * [page] as the feed filter shows it, with the token after it from [nextToken]. When the filter
     * hides all of a page, the pages after it are read in its place, up to [MAX_HIDDEN_PAGES].
     */
    private suspend fun visiblePage(
        key: FeedKey,
        page: YouTubePage,
        nextToken: (YouTubePage) -> String?
    ): YouTubePage {
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
     * [page] through the feed filter. An opened page also drops "View all" links back to itself:
     * the TV client answers a channel's Videos link with the channel page it's on. A channel's
     * row of its own tabs (Videos, Shorts, Playlists), which YouTube sends last, goes first,
     * without the Releases tab: its albums aren't tiles Fetch reads yet, so it would open empty.
     */
    private fun shown(key: FeedKey, page: YouTubePage): YouTubePage {
        val filtered = feedFilter.apply(page, key.feed)
        if (key !is FeedKey.Browse) return filtered
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

    private fun currentKey(): FeedKey {
        val state = _uiState.value
        val depth = state.browse.lastIndex
        return if (depth >= 0) FeedKey.Browse(depth, state.browse[depth].target) else FeedKey.Tab(state.selectedFeed)
    }

    private fun feedState(key: FeedKey): YouTubeFeedState? {
        val state = _uiState.value
        return when (key) {
            is FeedKey.Tab -> state.feeds[key.feed]
            is FeedKey.Browse -> state.browse.getOrNull(key.depth)?.takeIf { it.target == key.target }?.feed
        }
    }

    private fun load(key: FeedKey) {
        loadJobs[key]?.cancel()
        val query = _uiState.value.searchQuery
        updateFeed(key) { it.copy(isLoading = true, failed = false) }
        loadJobs[key] = viewModelScope.launch {
            try {
                val first = when (key) {
                    is FeedKey.Tab -> youTubeService.loadFeed(key.feed, query)
                    is FeedKey.Browse -> youTubeService.loadBrowse(key.target)
                }
                val page = visiblePage(key, first) { it.continuation }
                updateFeed(key) {
                    YouTubeFeedState(shelves = page.shelves, continuation = page.continuation, loaded = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeSignedOutException) {
                updateFeed(key) { it.copy(isLoading = false) }
                _uiState.update { it.copy(signedOut = true) }
            } catch (e: Exception) {
                updateFeed(key) { it.copy(isLoading = false, failed = true, loaded = true) }
            }
        }
    }

    private fun updatePlayback(video: YouTubeVideo, transform: (YouTubePlaybackState) -> YouTubePlaybackState) {
        _uiState.update { state ->
            val playback = state.playback?.takeIf { it.video.videoId == video.videoId } ?: return@update state
            state.copy(playback = transform(playback))
        }
    }

    /** Like [updateFeed], but leaves a feed that's gone (reset since) gone, so it loads afresh when opened. */
    private fun updateExistingFeed(key: FeedKey, transform: (YouTubeFeedState) -> YouTubeFeedState) =
        updateFeedState(key, create = false, transform)

    private fun updateFeed(key: FeedKey, transform: (YouTubeFeedState) -> YouTubeFeedState) =
        updateFeedState(key, create = true, transform)

    /** An opened page is only ever updated while it's still open; closing it drops what it was loading. */
    private fun updateFeedState(key: FeedKey, create: Boolean, transform: (YouTubeFeedState) -> YouTubeFeedState) {
        _uiState.update { state ->
            when (key) {
                is FeedKey.Tab -> {
                    val current = state.feeds[key.feed] ?: if (create) YouTubeFeedState() else return@update state
                    state.copy(feeds = state.feeds + (key.feed to transform(current)))
                }
                is FeedKey.Browse -> {
                    val page = state.browse.getOrNull(key.depth)?.takeIf { it.target == key.target }
                        ?: return@update state
                    state.copy(browse = state.browse.toMutableList().also { it[key.depth] = page.copy(feed = transform(page.feed)) })
                }
            }
        }
    }
}

/**
 * Adds the next page to a feed. Titled rows are new rows; untitled videos continue the feed's
 * untitled row (subscriptions and history are one long untitled row), or the only row there is.
 */
internal fun appendPage(shelves: List<YouTubeShelf>, page: YouTubePage): List<YouTubeShelf> {
    val result = shelves.toMutableList()
    page.shelves.forEach { shelf ->
        val target = if (shelf.title == null) {
            result.indexOfFirst { it.title == null && it.isShorts == shelf.isShorts }.takeIf { it >= 0 }
                ?: result.singleOrNull()?.let { 0 }
        } else {
            null
        }
        if (target != null) {
            val existing = result[target]
            result[target] = existing.copy(
                videos = (existing.videos + shelf.videos).distinctBy { it.videoId },
                links = (existing.links + shelf.links).distinctBy { it.target }
            )
        } else {
            result += shelf
        }
    }
    return result
}
