package com.fetch.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.livetv.LiveTvChannel
import com.fetch.tv.core.livetv.LiveTvChannels
import com.fetch.tv.core.livetv.LiveTvProgram
import com.fetch.tv.core.livetv.LiveTvRecording
import com.fetch.tv.core.livetv.LiveTvRepository
import com.fetch.tv.core.livetv.LiveTvSource
import com.fetch.tv.core.util.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the guide shows for the source picked at the top. */
data class LiveTvUiState(
    val sources: List<LiveTvSource> = emptyList(),
    val selectedKey: String? = null,
    val loadingSources: Boolean = true,
    val loadingChannels: Boolean = false,
    val channels: List<LiveTvChannel> = emptyList(),
    /** By [LiveTvChannel.key], sorted by start. */
    val programs: Map<String, List<LiveTvProgram>> = emptyMap(),
    val recordings: List<LiveTvRecording> = emptyList(),
    val error: String? = null,
    /** The guide's left edge: the start of the current half hour. */
    val windowStartMs: Long = LiveTvGuideWindow.startFor(System.currentTimeMillis())
) {
    val selectedSource: LiveTvSource? get() = sources.firstOrNull { it.key == selectedKey }
}

/** The guide's time window: from the current half hour, three hours wide. */
object LiveTvGuideWindow {
    const val SLOT_MS = 30 * 60 * 1000L
    const val WIDTH_MS = 3 * 60 * 60 * 1000L

    fun startFor(nowMs: Long): Long = nowMs - (nowMs % SLOT_MS)

    /** The programmes of a channel that overlap the window, clipped to it, in order. */
    fun visible(programs: List<LiveTvProgram>, windowStartMs: Long): List<LiveTvProgram> {
        val windowEnd = windowStartMs + WIDTH_MS
        return programs
            .filter { it.endMs > windowStartMs && it.startMs < windowEnd }
            .sortedBy { it.startMs }
    }
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repository: LiveTvRepository
) : ViewModel() {
    private val _state = MutableStateFlow(LiveTvUiState())
    val state: StateFlow<LiveTvUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        repository.probeServers()
        refreshSources()
        // A server found to have channels after the screen opened is added once the probe ends.
        viewModelScope.launch {
            repository.serverSources.collect { servers ->
                if (servers.isEmpty()) return@collect
                val known = _state.value.sources.filterIsInstance<LiveTvSource.MediaServer>().map { it.connectionId }.toSet()
                if (!known.containsAll(servers)) {
                    val sources = runSuspendCatching { repository.sources() }.getOrDefault(emptyList())
                    _state.update { it.copy(sources = sources) }
                    if (_state.value.selectedKey == null) sources.firstOrNull()?.let { select(it.key) }
                    loadRecordings()
                }
            }
        }
    }

    fun refreshSources() {
        viewModelScope.launch {
            val sources = runSuspendCatching { repository.sources() }.getOrDefault(emptyList())
            val keep = _state.value.selectedKey?.takeIf { key -> sources.any { it.key == key } }
            _state.update { it.copy(sources = sources, loadingSources = false) }
            val selected = keep ?: sources.firstOrNull()?.key
            if (selected != null) select(selected)
            loadRecordings()
        }
    }

    fun select(sourceKey: String) {
        val source = _state.value.sources.firstOrNull { it.key == sourceKey } ?: return
        loadJob?.cancel()
        val windowStart = LiveTvGuideWindow.startFor(System.currentTimeMillis())
        _state.update {
            it.copy(
                selectedKey = sourceKey,
                loadingChannels = true,
                channels = emptyList(),
                programs = emptyMap(),
                error = null,
                windowStartMs = windowStart
            )
        }
        loadJob = viewModelScope.launch {
            val channels = runSuspendCatching { repository.channels(source) }
            channels.onFailure { error ->
                _state.update { it.copy(loadingChannels = false, error = error.message ?: error.javaClass.simpleName) }
            }
            val list = channels.getOrNull() ?: return@launch
            _state.update { it.copy(loadingChannels = false, channels = list) }
            val programs = runSuspendCatching {
                repository.programs(source, list, windowStart, windowStart + LiveTvGuideWindow.WIDTH_MS)
            }.getOrDefault(emptyList())
            _state.update { state ->
                state.copy(programs = programs.groupBy { it.channelKey }.mapValues { (_, p) -> p.sortedBy { it.startMs } })
            }
        }
    }

    /** Marks [channel] as live for the player before it's opened. */
    fun onPlay(channel: LiveTvChannel) {
        LiveTvChannels.markLive(channel.videoId)
    }

    private fun loadRecordings() {
        viewModelScope.launch {
            val recordings = runSuspendCatching { repository.recordings() }.getOrDefault(emptyList())
            _state.update { it.copy(recordings = recordings) }
        }
    }
}
