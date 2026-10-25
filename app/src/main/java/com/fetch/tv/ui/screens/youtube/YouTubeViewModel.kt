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
import com.fetch.tv.data.youtube.YouTubeFeed
import com.fetch.tv.data.youtube.YouTubePage
import com.fetch.tv.data.youtube.YouTubeService
import com.fetch.tv.data.youtube.YouTubeShelf
import com.fetch.tv.data.youtube.YouTubeSignedOutException
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.data.youtube.YouTubeWatchSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val captionStyle: SubtitleStyleSettings = SubtitleStyleSettings()
)

data class YouTubeUiState(
    val selectedFeed: YouTubeFeed = YouTubeFeed.HOME,
    val feeds: Map<YouTubeFeed, YouTubeFeedState> = emptyMap(),
    val searchQuery: String = "",
    val signedOut: Boolean = false,
    val playback: YouTubePlaybackState? = null
) {
    val currentFeed: YouTubeFeedState
        get() = feeds[selectedFeed] ?: YouTubeFeedState()
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
    }

    private val _uiState = MutableStateFlow(YouTubeUiState())
    val uiState: StateFlow<YouTubeUiState> = _uiState.asStateFlow()

    private val loadJobs = mutableMapOf<YouTubeFeed, Job>()
    private var playbackJob: Job? = null
    private var captionJob: Job? = null
    private var watchSession: YouTubeWatchSession? = null
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var lastReportAtMs = 0L
    private var lastIdentity: Pair<String?, String?>? = null

    init {
        viewModelScope.launch {
            // Signing in, out, or switching channel or profile shows other feeds; start them over.
            authDataStore.state.collect { auth ->
                val signedIn = auth.isSignedIn
                val identity = auth.refreshToken to auth.pageId
                if (identity == lastIdentity && _uiState.value.signedOut == !signedIn) return@collect
                lastIdentity = identity
                _uiState.update { it.copy(signedOut = !signedIn, feeds = emptyMap()) }
                if (signedIn) load(_uiState.value.selectedFeed)
            }
        }
    }

    fun selectFeed(feed: YouTubeFeed) {
        _uiState.update { it.copy(selectedFeed = feed) }
        val state = _uiState.value.feeds[feed]
        if (feed != YouTubeFeed.SEARCH && (state == null || state.failed)) load(feed)
    }

    fun refresh() = load(_uiState.value.selectedFeed)

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun submitSearch() {
        if (_uiState.value.searchQuery.isBlank()) return
        load(YouTubeFeed.SEARCH)
    }

    /** Loads the feed's next page, when the user nears its end. */
    fun loadMore() {
        val feed = _uiState.value.selectedFeed
        val state = _uiState.value.feeds[feed] ?: return
        val token = state.continuation ?: return
        if (state.isLoading || state.isLoadingMore) return
        updateFeed(feed) { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val page = fetchMore(feed, token)
            updateFeed(feed) { current ->
                if (page == null) current.copy(isLoadingMore = false)
                else current.copy(
                    shelves = appendPage(current.shelves, page),
                    continuation = page.continuation,
                    isLoadingMore = false
                )
            }
        }
    }

    /** Loads more of one row, when focus nears its last video. */
    fun loadMoreInShelf(shelfIndex: Int) {
        val feed = _uiState.value.selectedFeed
        val state = _uiState.value.feeds[feed] ?: return
        val token = state.shelves.getOrNull(shelfIndex)?.continuation ?: return
        if (shelfIndex in state.loadingShelves) return
        updateFeed(feed) { it.copy(loadingShelves = it.loadingShelves + shelfIndex) }
        viewModelScope.launch {
            val page = fetchMore(feed, token)
            updateFeed(feed) { current ->
                val shelves = current.shelves.toMutableList()
                val shelf = shelves.getOrNull(shelfIndex)
                if (page != null && shelf != null) {
                    shelves[shelfIndex] = shelf.copy(
                        videos = (shelf.videos + page.shelves.flatMap { it.videos }).distinctBy { it.videoId },
                        continuation = page.continuation ?: page.shelves.firstNotNullOfOrNull { it.continuation }
                    )
                }
                current.copy(shelves = shelves, loadingShelves = current.loadingShelves - shelfIndex)
            }
        }
    }

    fun play(video: YouTubeVideo) {
        playbackJob?.cancel()
        finishWatchSession()
        _uiState.update { it.copy(playback = YouTubePlaybackState(video)) }
        playbackJob = viewModelScope.launch {
            val segments = async {
                if (settings.isSponsorBlockEnabled()) sponsorBlock.segments(video.videoId) else emptyList()
            }
            val playerInfo = async { youTubeService.loadPlayerInfo(video.videoId) }
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
            if (settings.isWatchHistoryEnabled()) {
                watchSession = youTubeService.newWatchSession(video.videoId, info, startPositionSec = 0f)
            }
            if (source != null) setUpCaptions(video, source.captionsJson, info?.captions)
            val skipSegments = segments.await()
            updatePlayback(video) { it.copy(skipSegments = skipSegments) }
        }
    }

    /** Shows [track], or turns captions off with null. Remembered for the next video. */
    fun selectCaption(track: YouTubeCaptionTrack?) {
        val video = _uiState.value.playback?.video ?: return
        captionJob?.cancel()
        viewModelScope.launch { settings.setCaptionPreference(track != null, track?.languageCode) }
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
        val video = _uiState.value.playback?.video
        playbackJob?.cancel()
        captionJob?.cancel()
        playbackJob = null
        finishWatchSession()
        if (video != null && lastDurationMs > 0L) {
            // Show the new progress on the card without reloading the feed.
            val percent = (lastPositionMs * 100 / lastDurationMs).toInt().coerceIn(0, 100)
            _uiState.update { state ->
                state.copy(feeds = state.feeds.mapValues { (_, feed) ->
                    feed.copy(shelves = feed.shelves.map { shelf ->
                        shelf.copy(videos = shelf.videos.map {
                            if (it.videoId == video.videoId) it.copy(watchedPercent = percent) else it
                        })
                    })
                })
            }
        }
        lastPositionMs = 0L
        lastDurationMs = 0L
        _uiState.update { it.copy(playback = null) }
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
        // Runs past a closed player or screen, so the last position still reaches YouTube.
        viewModelScope.launch {
            withContext(NonCancellable) { youTubeService.reportWatchTime(session, positionSec, lengthSec) }
        }
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

    private fun load(feed: YouTubeFeed) {
        loadJobs[feed]?.cancel()
        val query = _uiState.value.searchQuery
        updateFeed(feed) { it.copy(isLoading = true, failed = false) }
        loadJobs[feed] = viewModelScope.launch {
            try {
                val page = youTubeService.loadFeed(feed, query)
                updateFeed(feed) {
                    YouTubeFeedState(shelves = page.shelves, continuation = page.continuation, loaded = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouTubeSignedOutException) {
                updateFeed(feed) { it.copy(isLoading = false) }
                _uiState.update { it.copy(signedOut = true) }
            } catch (e: Exception) {
                updateFeed(feed) { it.copy(isLoading = false, failed = true, loaded = true) }
            }
        }
    }

    private fun updatePlayback(video: YouTubeVideo, transform: (YouTubePlaybackState) -> YouTubePlaybackState) {
        _uiState.update { state ->
            val playback = state.playback?.takeIf { it.video.videoId == video.videoId } ?: return@update state
            state.copy(playback = transform(playback))
        }
    }

    private fun updateFeed(feed: YouTubeFeed, transform: (YouTubeFeedState) -> YouTubeFeedState) {
        _uiState.update { state ->
            state.copy(feeds = state.feeds + (feed to transform(state.feeds[feed] ?: YouTubeFeedState())))
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
            result.indexOfFirst { it.title == null }.takeIf { it >= 0 }
                ?: result.singleOrNull()?.let { 0 }
        } else {
            null
        }
        if (target != null) {
            val existing = result[target]
            result[target] = existing.copy(videos = (existing.videos + shelf.videos).distinctBy { it.videoId })
        } else {
            result += shelf
        }
    }
    return result
}
