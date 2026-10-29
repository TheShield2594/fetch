package com.fetch.tv.ui.screens.stream

import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus

data class StreamScreenUiState(
    val isLoading: Boolean = true,
    /** Set when a kids profile can't watch now: its screen time is used up, or it's bedtime. */
    val screenTimeBlock: com.fetch.tv.core.parental.ScreenTimeStatus? = null,
    val isDirectAutoPlayFlow: Boolean = false,
    val showDirectAutoPlayOverlay: Boolean = false,
    val autoPlayDecided: Boolean = false,
    val externalPlayerOverlayVisible: Boolean = false,
    val directAutoPlayMessage: String? = null,
    val directAutoPlayProgress: Float? = null,
    val videoId: String = "",
    val contentType: String = "",
    val title: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    // Episode-specific fields
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    val runtime: Int? = null,
    // Movie-specific fields
    val genres: String? = null,
    val year: String? = null,
    val addonStreams: List<AddonStreams> = emptyList(),
    val allStreams: List<Stream> = emptyList(),
    val selectedAddonFilter: String? = null, // null means "All"
    val filteredStreams: List<Stream> = emptyList(),
    /** Why Best match passed over a stream, keyed by [Stream.stableKey]; empty outside Best match. */
    val streamExclusions: Map<String, com.fetch.tv.core.streams.ranking.ExclusionReason> = emptyMap(),
    /** True when the All tab is ordered by Best match, so its first stream is the pick. */
    val bestMatchFirst: Boolean = false,
    val availableAddons: List<String> = emptyList(),
    val sourceChips: List<SourceChipItem> = emptyList(),
    val autoPlayStream: Stream? = null,
    /** Best match among the streams that have arrived so far, while auto-play still waits. */
    val bestSoFarStream: Stream? = null,
    val bestSoFar: com.fetch.tv.core.streams.BestMatchPreview? = null,
    /** SystemClock.elapsedRealtime() at which auto-play stops waiting for slower addons. */
    val autoPlayDeadlineElapsedMs: Long? = null,
    val autoPlayPlaybackInfo: StreamPlaybackInfo? = null,
    val error: String? = null,
    val playbackErrorMessage: String? = null
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

sealed class StreamScreenEvent {
    data class OnAddonFilterSelected(val addonName: String?) : StreamScreenEvent()
    data class OnStreamSelected(val stream: Stream) : StreamScreenEvent()
    data object OnAutoPlayConsumed : StreamScreenEvent()
    data object OnRefresh : StreamScreenEvent()
    data object OnRetry : StreamScreenEvent()
    data object OnBackPress : StreamScreenEvent()
    data object OnResume : StreamScreenEvent()
    /** Play the best stream found so far instead of waiting for the remaining addons. */
    data object OnPlayBestSoFar : StreamScreenEvent()
    /** Leave the auto-play loader for the full source list. */
    data object OnShowAllSources : StreamScreenEvent()
}

data class SharedStream(
    val hostName: String,
    val stream: Stream
)
