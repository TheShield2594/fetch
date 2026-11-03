package com.fetch.tv.data.website

import com.fetch.tv.core.tracking.TrackingEpisode
import com.fetch.tv.core.tracking.TrackingExternalIds
import com.fetch.tv.core.tracking.TrackingHistoryItem
import com.fetch.tv.core.tracking.TrackingMediaKind
import com.fetch.tv.core.tracking.TrackingMediaReference
import com.fetch.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The projection and the edits shared with the desktop app are tested in :shared
// (FetchWebsiteTrackingProjectionTest).
class FetchWebsiteTrackingTest {
    private fun snapshot(
        playback: List<WebsitePlayback> = emptyList(),
        watchState: WebsiteWatchState = WebsiteWatchState(),
        lists: List<WebsiteList> = emptyList(),
        listItems: Map<String, List<WebsiteListItem>> = emptyMap()
    ) = FetchWebsiteTrackingSnapshot(1, "p1", playback, watchState, lists, listItems, fetchedAtMs = 1L)

    private val pausedEpisode = WebsitePlayback(
        id = "pb-1",
        type = "episode",
        imdbId = "tt0903747",
        season = 2,
        episode = 7,
        progress = 39.9,
        positionSeconds = 1130,
        durationSeconds = 2832,
        pausedAt = "2026-10-20T20:15:00.000Z",
        name = "Breaking Bad",
        poster = "https://img/bb.jpg"
    )

    // ─── What the TV sends ───

    private fun episodeRef(imdb: String? = "tt-show", season: Int? = 2, number: Int = 5) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        ids = TrackingExternalIds(imdb = imdb),
        episode = TrackingEpisode(season = season, number = number)
    )

    @Test
    fun `a scrobble names the episode under its series`() {
        val body = fetchWebsiteScrobbleBody(TrackingScrobbleEvent(episodeRef(), 41.5))!!

        assertEquals("episode", body["type"]?.jsonPrimitive?.content)
        assertEquals("tt-show", body["imdbId"]?.jsonPrimitive?.content)
        assertEquals("tt-show", body["seriesImdbId"]?.jsonPrimitive?.content)
        assertEquals(2, body["season"]?.jsonPrimitive?.int)
        assertEquals(5, body["episode"]?.jsonPrimitive?.int)
        assertEquals(41.5, body["progress"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun `a title without an IMDb id, or an episode without a season, isn't sent`() {
        assertNull(fetchWebsiteScrobbleBody(TrackingScrobbleEvent(episodeRef(imdb = null), 10.0)))
        assertNull(fetchWebsiteScrobbleBody(TrackingScrobbleEvent(episodeRef(season = null), 10.0)))
    }

    @Test
    fun `a film's watch is sent with its date`() {
        val film = TrackingMediaReference(kind = TrackingMediaKind.MOVIE, ids = TrackingExternalIds(imdb = "tt1"))

        val body = fetchWebsiteWatchBody(TrackingHistoryItem(film, watchedAtEpochMs = 0L))!!

        assertEquals("movie", body["type"]?.jsonPrimitive?.content)
        assertEquals("tt1", body["imdbId"]?.jsonPrimitive?.content)
        assertNull(body["season"])
        assertEquals(JsonPrimitive("1970-01-01T00:00:00Z"), body["watchedAt"])
    }

    @Test
    fun `marking watched clears the title's resume point in the held snapshot`() {
        val start = snapshot(playback = listOf(pausedEpisode))
        val item = TrackingHistoryItem(episodeRef(imdb = "tt0903747", season = 2, number = 7), watchedAtEpochMs = 5L)

        val updated = start.withWatched(listOf(item))

        assertTrue(updated.playback.isEmpty())
        assertEquals(listOf(2 to 7), updated.watchState.episodes.map { it.season to it.episode })
        assertTrue(updated.withoutWatched(listOf(FetchWebsiteTarget("tt0903747", 2, 7))).watchState.episodes.isEmpty())
    }
}
