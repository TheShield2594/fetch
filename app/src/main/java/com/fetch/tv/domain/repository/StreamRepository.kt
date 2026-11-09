package com.fetch.tv.domain.repository

import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamSearchUpdate
import kotlinx.coroutines.flow.Flow

interface StreamRepository {
    /**
     * Fetches streams from all installed addons for a given video ID
     * @param type The content type (movie, series, etc.)
     * @param videoId The video ID (for movies: IMDB ID, for series: IMDB_ID:season:episode)
     * @param season Optional season number for TV shows (used by media servers)
     * @param episode Optional episode number for TV shows (used by media servers)
     * @return Flow of AddonStreams grouped by addon
     */
    fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false
    ): Flow<NetworkResult<List<AddonStreams>>>

    /**
     * The same search as [getStreamsFromAllAddons], also saying how each source finished
     * (streams, nothing, failed, timed out) as soon as it does, so source chips can settle
     * without guessing. A search replayed from the session cache brings its outcomes back too.
     * An update can repeat the previous result with only the outcomes changed.
     */
    fun searchStreamsFromAllSources(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false
    ): Flow<StreamSearchUpdate>

    /**
     * Returns the streams a recent [getStreamsFromAllAddons] search already holds for this video,
     * or null when none is cached. Never starts a search.
     */
    suspend fun getCachedStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null
    ): List<AddonStreams>?

    /**
     * Fetches streams from a specific addon.
     *
     * Takes the installed [Addon] rather than a bare base URL so the display name and logo
     * stamped onto the returned streams come from the manifest already held in memory.
     *
     * @param addon The installed addon to query
     * @param type The content type
     * @param videoId The video ID
     * @param forceRefresh Skip the HTTP cache and ask the addon again
     * @return NetworkResult containing list of streams
     */
    suspend fun getStreamsFromAddon(
        addon: Addon,
        type: String,
        videoId: String,
        forceRefresh: Boolean = false
    ): NetworkResult<List<Stream>>
}
