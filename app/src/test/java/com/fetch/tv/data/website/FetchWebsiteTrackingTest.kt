package com.fetch.tv.data.website

import com.fetch.tv.core.tracking.TrackingEpisode
import com.fetch.tv.core.tracking.TrackingExternalIds
import com.fetch.tv.core.tracking.TrackingHistoryItem
import com.fetch.tv.core.tracking.TrackingMediaKind
import com.fetch.tv.core.tracking.TrackingMediaReference
import com.fetch.tv.core.tracking.TrackingScrobbleEvent
import com.fetch.tv.domain.model.LibraryEntryInput
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.WatchProgress
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    // ─── Continue Watching ───

    @Test
    fun `a resume point with seconds lands on the second`() {
        val progress = FetchWebsiteProgressProjection(snapshot(playback = listOf(pausedEpisode))).progress.single()

        assertEquals("tt0903747", progress.contentId)
        assertEquals("series", progress.contentType)
        assertEquals("tt0903747:2:7", progress.videoId)
        assertEquals(1_130_000L, progress.position)
        assertEquals(2_832_000L, progress.duration)
        assertNull(progress.progressPercent)
        assertEquals("Breaking Bad", progress.name)
        assertEquals(WatchProgress.SOURCE_REMOTE_PLAYBACK, progress.source)
        assertEquals("fetch_website", progress.trackingProviderId)
        assertEquals("pb-1", progress.trackingProviderItemId)
        assertEquals(fetchWebsiteTimestamp("2026-10-20T20:15:00.000Z"), progress.lastWatched)
    }

    @Test
    fun `a resume point with only a percentage resumes by it`() {
        val movie = WebsitePlayback(id = "pb-2", type = "movie", imdbId = "tt1", progress = 42.0, pausedAt = "2026-10-20T20:15:00Z")

        val progress = FetchWebsiteProgressProjection(snapshot(playback = listOf(movie))).progress.single()

        assertEquals("tt1", progress.videoId)
        assertNull(progress.season)
        assertEquals(0L, progress.position)
        assertEquals(42f, progress.progressPercent)
    }

    @Test
    fun `a title watched since its pause, or a dropped show, leaves Continue Watching`() {
        val movie = WebsitePlayback(id = "pb-2", type = "movie", imdbId = "tt1", progress = 42.0, pausedAt = "2026-10-20T20:00:00Z")
        val state = WebsiteWatchState(
            movies = listOf(WebsiteWatchedMovie("tt1", "2026-10-21T00:00:00Z")),
            dropped = listOf("tt0903747")
        )

        val projection = FetchWebsiteProgressProjection(snapshot(playback = listOf(pausedEpisode, movie), watchState = state))

        assertTrue(projection.progress.isEmpty())
        assertTrue(projection.isHidden("tt0903747"))
    }

    @Test
    fun `a rewatch paused after the last watch stays`() {
        val movie = WebsitePlayback(id = "pb-2", type = "movie", imdbId = "tt1", progress = 30.0, pausedAt = "2026-10-21T20:00:00Z")
        val state = WebsiteWatchState(movies = listOf(WebsiteWatchedMovie("tt1", "2026-09-01T00:00:00Z")))

        val projection = FetchWebsiteProgressProjection(snapshot(playback = listOf(movie), watchState = state))

        assertEquals(listOf("tt1"), projection.progress.map { it.contentId })
    }

    @Test
    fun `an episode resume point without numbers is dropped`() {
        val broken = pausedEpisode.copy(season = null)
        assertTrue(FetchWebsiteProgressProjection(snapshot(playback = listOf(broken))).progress.isEmpty())
    }

    // ─── Watched state ───

    @Test
    fun `watched films and episodes answer by id and by video id`() {
        val state = WebsiteWatchState(
            movies = listOf(WebsiteWatchedMovie("tt1", "2026-10-01T00:00:00Z", "A Film")),
            episodes = listOf(
                WebsiteWatchedEpisode("tt-show", 1, 1, "2026-10-02T00:00:00Z", "A Show"),
                WebsiteWatchedEpisode("tt-show", 1, 2, "2026-10-03T00:00:00Z", "A Show")
            )
        )

        val projection = FetchWebsiteProgressProjection(snapshot(watchState = state))

        assertEquals(setOf("tt1"), projection.watchedMovieIds)
        assertEquals(setOf(1 to 1, 1 to 2), projection.watchedShowEpisodes["tt-show"])
        assertTrue(projection.isWatched("tt1", null, null))
        assertTrue(projection.isWatched("tt-show", 1, 2))
        assertFalse(projection.isWatched("tt-show", 1, 3))
        assertEquals(true, projection.isWatchedByVideoId("tt-show:1:2", 2))
        assertEquals(false, projection.isWatchedByVideoId("tt-show:1:3", 3))
        assertNull(projection.isWatchedByVideoId("kitsu:1:3", 3))
        assertEquals(listOf("tt-show", "tt-show", "tt1"), projection.watchedItems.map { it.contentId })
        assertEquals("A Film", projection.watchedItems.last().title)
    }

    @Test
    fun `Next Up starts from the latest watched episode, skipping specials and dropped shows`() {
        val state = WebsiteWatchState(
            episodes = listOf(
                WebsiteWatchedEpisode("tt-show", 1, 1, "2026-10-02T00:00:00Z"),
                WebsiteWatchedEpisode("tt-show", 1, 2, "2026-10-03T00:00:00Z"),
                WebsiteWatchedEpisode("tt-show", 0, 1, "2026-10-04T00:00:00Z"),
                WebsiteWatchedEpisode("tt-dropped", 1, 1, "2026-10-05T00:00:00Z")
            ),
            dropped = listOf("tt-dropped")
        )

        val seeds = FetchWebsiteProgressProjection(snapshot(watchState = state)).nextUp(preferFurthest = false)

        val seed = seeds.single()
        assertEquals("tt-show", seed.contentId)
        assertEquals(1 to 2, seed.season to seed.episode)
        assertTrue(seed.isCompleted())
    }

    @Test
    fun `no snapshot shows nothing`() {
        val projection = FetchWebsiteProgressProjection.Empty
        assertTrue(projection.progress.isEmpty())
        assertTrue(projection.watchedItems.isEmpty())
        assertFalse(projection.isWatched("tt1", null, null))
    }

    // ─── Library ───

    @Test
    fun `lists become tabs, watchlist first, and titles carry every list they are in`() {
        val lists = listOf(
            WebsiteList("l-custom", "Halloween", "custom"),
            WebsiteList("l-watch", "Watchlist", "watchlist")
        )
        val items = mapOf(
            "l-watch" to listOf(
                WebsiteListItem("movie", "tt1", "2026-10-01T00:00:00Z", metadata = WebsiteListItemMetadata("A Film", "https://img/1.jpg", 1999, listOf("Horror"), 7.5))
            ),
            "l-custom" to listOf(
                WebsiteListItem("series", "tt2", "2026-10-02T00:00:00Z", title = "Captured Title"),
                WebsiteListItem("movie", "tt1", "2026-10-03T00:00:00Z")
            )
        )

        val library = FetchWebsiteLibraryProjection(snapshot(lists = lists, listItems = items))

        assertEquals(listOf("fetch:list:l-watch", "fetch:list:l-custom"), library.tabs.map { it.key })
        assertEquals(LibraryListTab.Type.WATCHLIST, library.tabs.first().type)
        assertEquals("fetch:list:l-watch", library.watchlistKey)
        val film = library.entries.single { it.id == "tt1" }
        assertEquals(setOf("fetch:list:l-watch", "fetch:list:l-custom"), film.listKeys)
        assertEquals("A Film", film.name)
        assertEquals("1999", film.releaseInfo)
        assertEquals(fetchWebsiteTimestamp("2026-10-03T00:00:00Z"), film.listedAt)
        assertEquals("Captured Title", library.entries.single { it.id == "tt2" }.name)
        assertEquals(setOf("fetch:list:l-custom"), library.membership("tt2", "series"))
        assertEquals(setOf("fetch:list:l-custom"), library.membership("tt2", "tv"))
        assertTrue(library.membership("tt2", "movie").isEmpty())
    }

    @Test
    fun `list keys round-trip and foreign keys are not ours`() {
        assertEquals("abc", fetchWebsiteListId(fetchWebsiteListKey("abc")))
        assertNull(fetchWebsiteListId("mdblist:list:7"))
        assertNull(fetchWebsiteListId("fetch:list:"))
    }

    @Test
    fun `adding to and removing from lists edits the held snapshot`() {
        val start = snapshot(
            lists = listOf(WebsiteList("a", "A", "watchlist"), WebsiteList("b", "B", "custom")),
            listItems = mapOf("b" to listOf(WebsiteListItem("movie", "tt1")))
        )
        val input = LibraryEntryInput(itemId = "tt1", itemType = "movie", title = "A Film")

        val updated = start.withListMembership(listOf("a"), listOf("b"), "movie", "tt1", input)

        assertEquals(listOf("tt1"), updated.listItems["a"].orEmpty().map { it.imdbId })
        assertEquals("A Film", updated.listItems["a"].orEmpty().single().metadata?.name)
        assertTrue(updated.listItems["b"].orEmpty().isEmpty())
    }

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
    fun `a resume point is saved in whole seconds when the player knows them`() {
        val progress = WatchProgress(
            contentId = "tt-show", contentType = "series", name = "A Show", poster = null, backdrop = null, logo = null,
            videoId = "tt-show:1:2", season = 1, episode = 2, episodeTitle = null,
            position = 61_500L, duration = 2_700_000L, lastWatched = 1L
        )

        val body = fetchWebsitePlaybackBody(progress)!!

        assertEquals("episode", body["type"]?.jsonPrimitive?.content)
        assertEquals(61L, body["positionSeconds"]?.jsonPrimitive?.long)
        assertEquals(2700L, body["durationSeconds"]?.jsonPrimitive?.long)
        assertNull(body["progress"])
    }

    @Test
    fun `without a duration the percentage is saved instead`() {
        val progress = WatchProgress(
            contentId = "tt1", contentType = "movie", name = "A Film", poster = null, backdrop = null, logo = null,
            videoId = "tt1", season = null, episode = null, episodeTitle = null,
            position = 0L, duration = 0L, lastWatched = 1L, progressPercent = 37.25f
        )

        val body = fetchWebsitePlaybackBody(progress)!!

        assertEquals("movie", body["type"]?.jsonPrimitive?.content)
        assertEquals(37.3, body["progress"]!!.jsonPrimitive.double, 0.001)
        assertNull(body["positionSeconds"])
        assertNull(fetchWebsitePlaybackBody(progress.copy(contentId = "tmdb:5")))
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
