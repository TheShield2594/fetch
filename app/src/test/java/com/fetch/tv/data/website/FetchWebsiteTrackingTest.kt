package com.fetch.tv.data.website

import com.fetch.tv.core.tracking.TrackingCatalogReference
import com.fetch.tv.core.tracking.TrackingEpisode
import com.fetch.tv.core.tracking.TrackingExternalIds
import com.fetch.tv.core.tracking.TrackingHistoryItem
import com.fetch.tv.core.tracking.TrackingMediaKind
import com.fetch.tv.core.tracking.TrackingMediaReference
import com.fetch.tv.core.tracking.TrackingScrobbleEvent
import com.fetch.tv.domain.model.LibraryEntryInput
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

    private fun episodeRef(imdb: String? = "tt0959621", season: Int? = 2, number: Int = 5) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        ids = TrackingExternalIds(imdb = imdb),
        episode = TrackingEpisode(season = season, number = number)
    )

    @Test
    fun `a scrobble names the episode under its series`() {
        val body = fetchWebsiteScrobbleBody(TrackingScrobbleEvent(episodeRef(), 41.5))!!

        assertEquals("episode", body["type"]?.jsonPrimitive?.content)
        assertEquals("tt0959621", body["imdbId"]?.jsonPrimitive?.content)
        assertEquals("tt0959621", body["seriesImdbId"]?.jsonPrimitive?.content)
        assertEquals(2, body["season"]?.jsonPrimitive?.int)
        assertEquals(5, body["episode"]?.jsonPrimitive?.int)
        assertEquals(41.5, body["progress"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun `a title with no id the website knows, or an episode without a season, isn't sent`() {
        assertNull(fetchWebsiteScrobbleBody(TrackingScrobbleEvent(episodeRef(imdb = null), 10.0)))
        assertNull(fetchWebsiteScrobbleBody(TrackingScrobbleEvent(episodeRef(season = null), 10.0)))
        val custom = episodeRef(imdb = null).copy(catalog = TrackingCatalogReference("yt_id:abc", "series"))
        assertNull(fetchWebsiteScrobbleBody(TrackingScrobbleEvent(custom, 10.0)))
    }

    @Test
    fun `an anime episode without an IMDb id is sent under its catalog id, named`() {
        val anime = TrackingMediaReference(
            kind = TrackingMediaKind.ANIME,
            title = "Cowboy Bebop",
            ids = TrackingExternalIds(kitsu = 1376, mal = 1),
            episode = TrackingEpisode(season = 1, number = 5),
            catalog = TrackingCatalogReference("kitsu:1376", "series", videoId = "kitsu:1376:5"),
            posterUrl = "https://media.kitsu.app/anime/1376/poster.jpg"
        )

        val body = fetchWebsiteScrobbleBody(TrackingScrobbleEvent(anime, 12.0))!!

        assertEquals("kitsu:1376", body["imdbId"]?.jsonPrimitive?.content)
        assertEquals("kitsu:1376", body["seriesImdbId"]?.jsonPrimitive?.content)
        assertEquals(1, body["season"]?.jsonPrimitive?.int)
        assertEquals(5, body["episode"]?.jsonPrimitive?.int)
        assertEquals("Cowboy Bebop", body["name"]?.jsonPrimitive?.content)
        assertEquals("https://media.kitsu.app/anime/1376/poster.jpg", body["poster"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a TMDB-only film is sent as tmdb, and an IMDb id wins when the TV knows both`() {
        val tmdbOnly = TrackingMediaReference(
            kind = TrackingMediaKind.MOVIE,
            ids = TrackingExternalIds(tmdb = 603),
            catalog = TrackingCatalogReference("tmdb:603", "movie")
        )
        assertEquals("tmdb:603", fetchWebsiteWatchBody(TrackingHistoryItem(tmdbOnly))!!["imdbId"]?.jsonPrimitive?.content)

        val both = tmdbOnly.copy(ids = TrackingExternalIds(imdb = "tt0133093", tmdb = 603))
        assertEquals("tt0133093", fetchWebsiteWatchBody(TrackingHistoryItem(both))!!["imdbId"]?.jsonPrimitive?.content)

        // Known only by ids, with no catalog id to follow: TMDB's is preferred over the rest.
        val idsOnly = TrackingMediaReference(kind = TrackingMediaKind.MOVIE, ids = TrackingExternalIds(trakt = 481, tmdb = 603))
        assertEquals(FetchWebsiteTarget("tmdb:603", null, null), idsOnly.websiteTarget())
    }

    @Test
    fun `a library entry is listed under its IMDb id, its own prefixed id, or its TMDB id`() {
        assertEquals("tt0133093", LibraryEntryInput("tmdb:603", "movie", "The Matrix", imdbId = "tt0133093").websiteContentId())
        assertEquals("tmdb:603", LibraryEntryInput("tmdb:603", "movie", "The Matrix").websiteContentId())
        assertEquals("kitsu:1376", LibraryEntryInput("kitsu:1376", "series", "Cowboy Bebop").websiteContentId())
        assertEquals("tmdb:603", LibraryEntryInput("custom-id", "movie", "The Matrix", tmdbId = 603).websiteContentId())
        assertNull(LibraryEntryInput("custom-id", "movie", "Unknown").websiteContentId())
    }

    @Test
    fun `a prefixed id read back from the website is the same id the TV sent`() {
        val playback = pausedEpisode.copy(id = "pb-2", imdbId = "kitsu:1376", season = 1, episode = 5, name = "Cowboy Bebop")
        val projection = FetchWebsiteProgressProjection(
            snapshot(
                playback = listOf(playback),
                watchState = WebsiteWatchState(
                    movies = listOf(WebsiteWatchedMovie("tmdb:603", "2026-10-19T20:15:00.000Z", "The Matrix"))
                )
            )
        )

        val resume = projection.progress.single()
        assertEquals("kitsu:1376", resume.contentId)
        assertEquals("Cowboy Bebop", resume.name)
        assertEquals(1 to 5, resume.season to resume.episode)
        assertTrue(projection.isWatched("tmdb:603", null, null))
        assertEquals(false, projection.isWatchedByVideoId("tmdb:603:1:1", 1))
        // A kitsu video id carries no season, so it can't be read as one.
        assertNull(projection.isWatchedByVideoId("kitsu:1376:5", 5))
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
