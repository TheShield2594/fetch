package com.fetch.tv.core.torrent

import android.content.Context
import com.fetch.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Entry point for on-device P2P playback of torrent streams.
 *
 * The upstream app shipped a closed-source native torrent engine here. Fetch does not
 * bundle one: torrent results are resolved through the built-in debrid integration
 * (Settings → Debrid) or handed to an external player. The API is kept so an
 * open-source engine can be plugged in later without touching the player.
 */
@Singleton
class TorrentService @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("unused") private val torrentSettings: TorrentSettings
) {
    companion object {
        /** True when an on-device torrent engine is compiled into this build. */
        const val ENGINE_AVAILABLE = false
    }

    private val _state = MutableStateFlow<TorrentState>(TorrentState.Idle)
    val state: StateFlow<TorrentState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(TorrentCacheState())
    val cacheState: StateFlow<TorrentCacheState> = _cacheState.asStateFlow()

    @Suppress("UNUSED_PARAMETER")
    suspend fun startStream(
        infoHash: String,
        fileIdx: Int?,
        filename: String? = null,
        trackers: List<String> = emptyList()
    ): String {
        val message = context.getString(R.string.torrent_engine_unavailable)
        _state.value = TorrentState.Error(message)
        throw TorrentEngineUnavailableException(message)
    }

    suspend fun clearCache(): TorrentCacheClearResult =
        TorrentCacheClearResult(reclaimedBytes = 0L, remainingBytes = 0L, protectedBytes = 0L)

    fun stopStream() {
        _state.value = TorrentState.Idle
    }

    fun shutdown() {
        _state.value = TorrentState.Idle
    }
}

class TorrentEngineUnavailableException(message: String) : IllegalStateException(message)
