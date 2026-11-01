package com.fetch.tv.data.youtube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A video another screen asked the YouTube screen to play, such as a card in Home's YouTube row.
 * The asker sets it and opens the YouTube screen, which plays it once and clears it.
 */
@Singleton
class YouTubePlayRequests @Inject constructor() {
    private val _pending = MutableStateFlow<YouTubeVideo?>(null)
    val pending: StateFlow<YouTubeVideo?> = _pending.asStateFlow()

    fun request(video: YouTubeVideo) {
        _pending.value = video
    }

    /** The waiting video, which is then no longer waiting. */
    fun take(): YouTubeVideo? = _pending.getAndUpdate { null }
}
