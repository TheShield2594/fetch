package com.fetch.tv.ui.screens.youtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.data.local.YouTubeAuthDataStore
import com.fetch.tv.data.trailer.InAppYouTubeExtractor
import com.fetch.tv.data.youtube.YouTubeFeed
import com.fetch.tv.data.youtube.YouTubeService
import com.fetch.tv.data.youtube.YouTubeShelf
import com.fetch.tv.data.youtube.YouTubeSignedOutException
import com.fetch.tv.data.youtube.YouTubeVideo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class YouTubeFeedState(
    val shelves: List<YouTubeShelf> = emptyList(),
    val isLoading: Boolean = false,
    val failed: Boolean = false,
    val loaded: Boolean = false
)

data class YouTubePlaybackState(
    val video: YouTubeVideo,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val isLoading: Boolean = true,
    val failed: Boolean = false
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
    authDataStore: YouTubeAuthDataStore
) : ViewModel() {
    private companion object {
        /** TV screens are 1080p or 4K; 1080p keeps 4K streams off devices and networks that struggle. */
        const val MAX_VIDEO_HEIGHT = 1080
    }

    private val _uiState = MutableStateFlow(YouTubeUiState())
    val uiState: StateFlow<YouTubeUiState> = _uiState.asStateFlow()

    private val loadJobs = mutableMapOf<YouTubeFeed, Job>()
    private var playbackJob: Job? = null

    init {
        viewModelScope.launch {
            authDataStore.isSignedIn.collect { signedIn ->
                _uiState.update { it.copy(signedOut = !signedIn) }
                if (signedIn) {
                    // A new sign-in (or another profile's) shows different feeds; drop the old ones.
                    _uiState.update { it.copy(feeds = emptyMap()) }
                    load(_uiState.value.selectedFeed)
                }
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

    fun play(video: YouTubeVideo) {
        playbackJob?.cancel()
        _uiState.update { it.copy(playback = YouTubePlaybackState(video)) }
        playbackJob = viewModelScope.launch {
            val source = try {
                extractor.extractPlaybackSource(video.watchUrl, maxVideoHeight = MAX_VIDEO_HEIGHT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _uiState.update { state ->
                val playback = state.playback?.takeIf { it.video.videoId == video.videoId } ?: return@update state
                state.copy(
                    playback = if (source == null || source.videoUrl.isBlank()) {
                        playback.copy(isLoading = false, failed = true)
                    } else {
                        playback.copy(videoUrl = source.videoUrl, audioUrl = source.audioUrl, isLoading = false)
                    }
                )
            }
        }
    }

    fun retryPlayback() {
        _uiState.value.playback?.video?.let(::play)
    }

    fun closePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _uiState.update { it.copy(playback = null) }
    }

    private fun load(feed: YouTubeFeed) {
        loadJobs[feed]?.cancel()
        val query = _uiState.value.searchQuery
        updateFeed(feed) { it.copy(isLoading = true, failed = false) }
        loadJobs[feed] = viewModelScope.launch {
            try {
                val shelves = youTubeService.loadFeed(feed, query)
                updateFeed(feed) { YouTubeFeedState(shelves = shelves, loaded = true) }
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

    private fun updateFeed(feed: YouTubeFeed, transform: (YouTubeFeedState) -> YouTubeFeedState) {
        _uiState.update { state ->
            state.copy(feeds = state.feeds + (feed to transform(state.feeds[feed] ?: YouTubeFeedState())))
        }
    }
}
