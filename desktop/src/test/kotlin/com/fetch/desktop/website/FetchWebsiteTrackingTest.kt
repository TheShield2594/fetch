package com.fetch.desktop.website

import com.fetch.desktop.ListProvider
import com.fetch.desktop.ListPrivacy
import com.fetch.desktop.ListsState
import com.fetch.desktop.TrackingState
import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.ProgressFile
import com.fetch.desktop.localListsFile
import com.fetch.desktop.tracking.FakeHttp
import com.fetch.desktop.tracking.ScrobbleAction
import com.fetch.desktop.tracking.TrackingOptions
import com.fetch.desktop.tracking.TrackingStore
import com.fetch.desktop.tracking.WatchProgressSourceOption
import com.fetch.desktop.tracking.LibrarySourceOption
import com.fetch.desktop.tracking.playbackForSource
import com.fetch.desktop.tracking.testCredentials
import com.fetch.tv.data.website.FetchWebsiteProgressProjection
import com.fetch.tv.data.website.FetchWebsiteTrackingSnapshot
import com.fetch.tv.data.website.WebsiteList
import com.fetch.tv.data.website.WebsiteListItem
import com.fetch.tv.data.website.WebsitePlayback
import com.fetch.tv.data.website.WebsiteWatchState
import com.fetch.tv.data.website.WebsiteWatchedEpisode
import com.fetch.tv.data.website.WebsiteWatchedMovie
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FetchWebsiteTrackingTest {
    private val dir: File = Files.createTempDirectory("web-tracking").toFile()

    private val profilesJson = """{"profiles":[{"slot":1,"id":"p-main","name":"Me"},{"slot":2,"id":"p-kid","name":"Kid","hasPin":true}]}"""

    private val playbackJson = """{"playback":[
        {"id":"pb-1","type":"episode","imdbId":"tt0903747","season":2,"episode":7,"progress":39.9,
         "positionSeconds":1130,"durationSeconds":2832,"pausedAt":"2026-10-20T20:15:00Z","name":"Breaking Bad"},
        {"id":"pb-2","type":"movie","imdbId":"tt1","progress":42.0,"pausedAt":"2026-10-19T20:00:00Z"}
    ]}"""

    private val watchStateJson = """{"movies":[{"imdbId":"tt2","watchedAt":"2026-10-01T00:00:00Z","name":"Heat"}],
        "episodes":[{"imdbId":"tt-show","season":1,"episode":3,"watchedAt":"2026-10-21T00:00:00Z","name":"A Show"}],"dropped":[]}"""

    private val listsJson = """{"lists":[{"id":"l-custom","name":"Halloween","kind":"custom"},{"id":"l-watch","name":"Watchlist","kind":"watchlist"}]}"""

    /** The website's routes for one profile, with the list items paged as it pages them. */
    private fun routes(request: Request): Pair<Int, String> {
        val path = request.url.encodedPath
        val cursor = request.url.queryParameter("cursor")
        return when {
            path == "/devices/me/profiles" -> 200 to profilesJson
            path == "/playback" && request.method == "GET" -> 200 to playbackJson
            path == "/watch/state" -> 200 to watchStateJson
            path == "/lists" && request.method == "GET" -> 200 to listsJson
            path == "/lists" && request.method == "POST" -> 201 to """{"list":{"id":"l-new","name":"Weekend","kind":"custom"}}"""
            path == "/lists/l-watch/items" && request.method == "GET" && cursor == null ->
                200 to """{"items":[{"type":"movie","imdbId":"tt1","addedAt":"2026-10-01T00:00:00Z","metadata":{"name":"A Film","year":1999}}],"nextCursor":"c2"}"""
            path == "/lists/l-watch/items" && request.method == "GET" ->
                200 to """{"items":[{"type":"series","imdbId":"tt-show","title":"A Show"}]}"""
            path == "/lists/l-custom/items" && request.method == "GET" -> 200 to """{"items":[]}"""
            path == "/lists/l-watch" && request.method == "DELETE" -> 400 to """{"error":"The watchlist can't be deleted"}"""
            else -> 200 to "{}"
        }
    }

    private fun paired(fake: FakeHttp): FetchWebsiteState {
        val store = WebsiteStore(File(dir, "website.json"))
        store.save(WebsiteFile(WebsiteSession("http://web.test", "tok", "PC")))
        return FetchWebsiteState(store, fake.client)
    }

    private fun tracker(scope: CoroutineScope, fake: FakeHttp, slot: Int = 1, website: FetchWebsiteState = paired(fake)) =
        FetchWebsiteTracker(website, slot, scope, conflictRetryMs = 1L)

    private suspend fun CoroutineScope.settle() = coroutineContext.job.children.forEach { it.join() }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private val episode = PlaybackTarget(
        contentId = "tt0903747", contentType = "series", name = "Breaking Bad", poster = "https://img/bb.jpg", backdrop = null, logo = null,
        video = Video("tt0903747:2:7", "Ep 7", null, null, season = 2, episode = 7, overview = null)
    )
    private val film = PlaybackTarget("tt1", "movie", "A Film", null, null, null, video = null)

    private val preview = MetaPreview(
        id = "tt0111161", type = ContentType.MOVIE, name = "The Shawshank Redemption", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = "1994", imdbRating = null, genres = emptyList()
    )

    // --- Reading ---

    @Test
    fun `a read takes the profile's resume points, watch state and every page of its lists`() = runBlocking {
        val fake = FakeHttp(::routes)
        val tracker = tracker(this, fake)

        assertTrue(tracker.refresh())

        val snapshot = tracker.snapshot!!
        assertEquals("p-main", snapshot.websiteProfileId)
        assertEquals(listOf("pb-1", "pb-2"), snapshot.playback.map { it.id })
        assertEquals(listOf("tt1", "tt-show"), snapshot.listItems["l-watch"].orEmpty().map { it.imdbId })
        assertEquals("c2", fake.sent.last { it.request.url.encodedPath == "/lists/l-watch/items" }.request.url.queryParameter("cursor"))
        assertTrue(fake.sent.filter { it.request.url.encodedPath != "/devices/me/profiles" }.all { it.request.header("x-profile-id") == "p-main" })
        // A second read within the minute is skipped; a forced one isn't.
        val sent = fake.sent.size
        assertFalse(tracker.refresh())
        assertEquals(sent, fake.sent.size)
        assertTrue(tracker.refresh(force = true))
    }

    @Test
    fun `a locked profile shows nothing, and a failed read keeps what was read`() = runBlocking {
        var failing = false
        val fake = FakeHttp { request ->
            when {
                request.url.encodedPath == "/devices/me/profiles" -> 200 to profilesJson
                failing -> 500 to "{}"
                request.header("x-profile-id") == "p-kid" -> 401 to """{"error":"Locked","code":"profile_verification_required"}"""
                else -> routes(request)
            }
        }
        val kid = tracker(this, fake, slot = 2)
        kid.refresh()
        assertTrue(kid.snapshot!!.locked)
        assertTrue(kid.libraryLists().isEmpty())

        val main = tracker(this, fake)
        main.refresh()
        failing = true
        assertFalse(main.refresh(force = true))
        assertEquals(2, main.snapshot!!.playback.size)
    }

    @Test
    fun `nothing is read or sent while not paired`() = runBlocking {
        val fake = FakeHttp(::routes)
        val tracker = FetchWebsiteTracker(null, 1, this)
        assertFalse(tracker.refresh())
        tracker.scrobble(film, ScrobbleAction.STOP, 95f)
        tracker.savePosition(film, 60_000, 6_000_000)
        assertFalse(tracker.paired)
        assertTrue(fake.sent.isEmpty())
    }

    // --- Writing ---

    @Test
    fun `a scrobble names the episode under its series, then the website is read again`() = runBlocking {
        val fake = FakeHttp(::routes)
        val tracker = tracker(this, fake)
        tracker.scrobble(episode, ScrobbleAction.STOP, 92.5f)
        settle()

        val sent = fake.sent.single { it.request.url.encodedPath == "/scrobble/stop" }
        val body = json(sent.body)
        assertEquals("episode", body["type"]!!.jsonPrimitive.content)
        assertEquals("tt0903747", body["seriesImdbId"]!!.jsonPrimitive.content)
        assertEquals("2", body["season"]!!.jsonPrimitive.content)
        assertEquals("7", body["episode"]!!.jsonPrimitive.content)
        assertEquals("92.5", body["progress"]!!.jsonPrimitive.content)
        assertEquals("p-main", sent.request.header("x-profile-id"))
        assertTrue("a stop reads the website back", fake.sent.any { it.request.url.encodedPath == "/watch/state" })

        // Titles without an IMDb id stay on this computer.
        tracker.scrobble(film.copy(contentId = "tmdb:5"), ScrobbleAction.START, 1f)
        assertEquals(1, fake.sent.count { it.request.url.encodedPath.startsWith("/scrobble/") })
    }

    @Test
    fun `watched marks go to the website and land in the held snapshot`() = runBlocking {
        val fake = FakeHttp(::routes)
        val tracker = tracker(this, fake)
        tracker.refresh()

        tracker.setWatched(episode, watched = true)
        val watch = fake.sent.single { it.request.url.encodedPath == "/watch" }
        assertEquals("POST", watch.request.method)
        assertEquals("episode", json(watch.body)["type"]!!.jsonPrimitive.content)
        assertNotNull(json(watch.body)["watchedAt"])
        assertTrue(tracker.progress.isWatched("tt0903747", 2, 7))
        assertTrue("watching clears the resume point", tracker.snapshot!!.playback.none { it.id == "pb-1" })

        tracker.setWatched(episode, watched = false)
        assertEquals("DELETE", fake.sent.last().request.method)
        assertEquals("/series/tt0903747/season/2/episode/7/watch", fake.sent.last().request.url.encodedPath)
        assertFalse(tracker.progress.isWatched("tt0903747", 2, 7))

        tracker.setWatched(film, watched = false)
        assertEquals("/watch/movie/tt1", fake.sent.last().request.url.encodedPath)

        // Specials can't be unmarked there.
        val sent = fake.sent.size
        tracker.setWatched(episode.copy(video = episode.video!!.copy(id = "tt0903747:0:1", season = 0, episode = 1)), watched = false)
        assertEquals(sent, fake.sent.size)
    }

    @Test
    fun `resume points go up by the second, at most every half minute per title`() = runBlocking {
        var clock = 1_000_000L
        val fake = FakeHttp(::routes)
        val tracker = FetchWebsiteTracker(paired(fake), 1, this, now = { clock })

        tracker.savePosition(episode, 61_500, 2_700_000); settle()
        clock += 10_000
        tracker.savePosition(episode, 71_500, 2_700_000); settle()
        // Not started, or nearly done: left to the scrobbles.
        tracker.savePosition(film, 1_000, 6_000_000)
        tracker.savePosition(film, 5_000_000, 6_000_000)
        clock += 30_000
        tracker.savePosition(episode, 101_500, 2_700_000); settle()

        val puts = fake.sent.filter { it.request.url.encodedPath == "/playback" && it.request.method == "PUT" }
        assertEquals(listOf("61", "101"), puts.map { json(it.body)["positionSeconds"]!!.jsonPrimitive.content })
        assertEquals("2700", json(puts[0].body)["durationSeconds"]!!.jsonPrimitive.content)
        assertEquals("episode", json(puts[0].body)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `leaving Continue Watching drops the title's resume points there`() = runBlocking {
        val fake = FakeHttp(::routes)
        val tracker = tracker(this, fake)
        tracker.forgetPosition("tt0903747", null, null)

        val delete = fake.sent.single { it.request.method == "DELETE" }
        assertEquals("/playback/pb-1", delete.request.url.encodedPath)
        assertTrue(tracker.snapshot!!.playback.none { it.imdbId == "tt0903747" })
    }

    // --- Continue Watching ---

    private fun snapshot(playback: List<WebsitePlayback> = emptyList(), watchState: WebsiteWatchState = WebsiteWatchState()) =
        FetchWebsiteTrackingSnapshot(1, "p-main", playback, watchState, fetchedAtMs = 1L)

    @Test
    fun `resume points become Continue Watching entries, by the second when the website has them`() {
        val projection = FetchWebsiteProgressProjection(
            snapshot(
                playback = listOf(
                    WebsitePlayback("pb-1", "episode", "tt-show", 2, 7, 39.9, 1130, 2832, "2026-10-20T20:15:00Z", "A Show"),
                    WebsitePlayback("pb-2", "movie", "tt1", progress = 42.0, pausedAt = "2026-10-19T20:00:00Z")
                ),
                watchState = WebsiteWatchState(movies = listOf(WebsiteWatchedMovie("tt2", "2026-10-01T00:00:00Z")))
            )
        )
        val (show, movie) = FetchWebsiteProgress.entries(projection)
        assertEquals("tt-show:2:7", show.videoId)
        assertEquals(1_130_000L, show.positionMs)
        assertEquals(2_832_000L, show.durationMs)
        assertNull(show.remotePercent)
        assertEquals("fetch_website", show.source)
        assertEquals(0L, movie.durationMs)
        assertEquals(42f, movie.remotePercent)
        assertEquals(setOf("tt2"), FetchWebsiteProgress.watchedIds(projection))
    }

    @Test
    fun `newer activity there wins, keeping the artwork held here`() {
        val local = ProgressEntry(
            contentId = "tt1", contentType = "movie", name = "A Film", poster = "https://addon/poster.jpg", backdrop = "https://addon/bg.jpg",
            videoId = "tt1", positionMs = 60_000, durationMs = 6_000_000, lastWatched = 10L
        )
        val remote = local.copy(poster = "https://metahub/p", backdrop = null, positionMs = 120_000, lastWatched = 20L, source = "fetch_website")
        val merged = FetchWebsiteProgress.merge(ProgressFile(entries = listOf(local)), listOf(remote), setOf("tt9"), emptyList())
        val entry = merged.entries.single()
        assertEquals(120_000L, entry.positionMs)
        assertEquals("https://addon/poster.jpg", entry.poster)
        assertEquals("https://addon/bg.jpg", entry.backdrop)
        assertEquals(listOf("tt9"), merged.watched)

        // Older there: what is here stays.
        assertEquals(60_000L, FetchWebsiteProgress.merge(ProgressFile(entries = listOf(local)), listOf(remote.copy(lastWatched = 5L)), emptySet(), emptyList()).entries.single().positionMs)
    }

    @Test
    fun `a show watched further there becomes caught up, to come back as Next Up`() {
        val projection = FetchWebsiteProgressProjection(
            snapshot(
                watchState = WebsiteWatchState(
                    episodes = listOf(
                        WebsiteWatchedEpisode("tt-show", 1, 2, "2026-10-02T00:00:00Z", "A Show"),
                        WebsiteWatchedEpisode("tt-show", 1, 3, "2026-10-03T00:00:00Z", "A Show"),
                        WebsiteWatchedEpisode("tt-other", 1, 1, "2026-10-01T00:00:00Z", "Other")
                    )
                )
            )
        )
        val nextUp = FetchWebsiteProgress.nextUp(projection, preferFurthest = true)
        assertEquals(listOf("tt-show:1:3", "tt-other:1:1"), nextUp.map { it.lastVideoId })

        val seedAt = nextUp.first().at
        val stale = ProgressEntry("tt-show", "series", "A Show", videoId = "tt-show:1:1", season = 1, episode = 1, positionMs = 5_000, durationMs = 60_000, lastWatched = seedAt - 1)
        val fresh = ProgressEntry("tt-other", "series", "Other", videoId = "tt-other:1:2", season = 1, episode = 2, positionMs = 0, durationMs = 0, lastWatched = seedAt, upNext = true)
        val merged = FetchWebsiteProgress.merge(ProgressFile(entries = listOf(stale, fresh)), emptyList(), FetchWebsiteProgress.watchedIds(projection), nextUp)

        assertEquals(listOf("tt-other"), merged.entries.map { it.contentId })
        assertEquals(listOf("tt-show"), merged.caughtUp.map { it.contentId })
        assertTrue("tt-show:1:3" in merged.watched)
        // Read again, nothing changes.
        assertEquals(merged, FetchWebsiteProgress.merge(merged, emptyList(), FetchWebsiteProgress.watchedIds(projection), nextUp))
    }

    @Test
    fun `the website is offered as a source by the TV's names, and picks only its own resume points`() {
        assertEquals(WatchProgressSourceOption.FETCH_WEBSITE, TrackingOptions(watchProgressSource = "FETCH_WEBSITE").progress)
        assertEquals(LibrarySourceOption.FETCH_WEBSITE, TrackingOptions(librarySource = "FETCH_WEBSITE").library)
        val trakt = ProgressEntry("tt1", "movie", "A", videoId = "tt1", positionMs = 0, durationMs = 0, lastWatched = 1, remotePercent = 10f, source = "trakt")
        val web = trakt.copy(contentId = "tt2", videoId = "tt2", source = "fetch_website")
        val connected = setOf(WatchProgressSourceOption.TRAKT, WatchProgressSourceOption.FETCH_WEBSITE)
        assertEquals(listOf(web), playbackForSource(listOf(trakt, web), WatchProgressSourceOption.FETCH_WEBSITE, connected))
        assertEquals(listOf(trakt), playbackForSource(listOf(trakt, web), WatchProgressSourceOption.TRAKT, connected))
    }

    // --- Library ---

    private fun lists(scope: CoroutineScope, tracker: FetchWebsiteTracker): ListsState {
        val tracking = TrackingState(scope, TrackingStore(File(dir, "tracking.json")), testCredentials, FakeHttp.always(200, "[]").client, website = { tracker })
        return ListsState(scope, tracking, localListsFile(dir), { emptyList() }, { _, _ -> }, onMessage = {}, website = tracker)
    }

    @Test
    fun `the website's lists become Library tabs, watchlist first, in the website's order`() = runBlocking {
        val tracker = tracker(this, FakeHttp(::routes))
        val lists = lists(this, tracker)
        assertEquals(listOf(ListProvider.LOCAL, ListProvider.FETCH_WEBSITE), lists.providers)
        lists.refresh(); settle()

        val tabs = lists.lists(ListProvider.FETCH_WEBSITE)
        assertEquals(listOf("fetch:list:l-watch", "fetch:list:l-custom"), tabs.map { it.key })
        assertEquals(listOf("tt1", "tt-show"), tabs[0].items.map { it.id })
        assertEquals(listOf("movie", "series"), tabs[0].items.map { it.type })
        assertEquals("A Film", tabs[0].items[0].name)
        assertEquals("1999", tabs[0].items[0].releaseInfo)
        assertFalse("the watchlist can't be renamed or deleted", tabs[0].editable)
        assertTrue(tabs[1].editable)
    }

    @Test
    fun `titles go on and off website lists, and lists are made, renamed and deleted there`() = runBlocking {
        // The custom list holds what was added to it, as the website would.
        val custom = mutableListOf<String>()
        val fake = FakeHttp { request ->
            val path = request.url.encodedPath
            when {
                path == "/lists/l-custom/items" && request.method == "POST" -> {
                    custom += json(Buffer().also { request.body!!.writeTo(it) }.readUtf8())["imdbId"]!!.jsonPrimitive.content
                    201 to "{}"
                }
                path.startsWith("/lists/l-custom/items/") && request.method == "DELETE" -> {
                    custom.remove(path.substringAfterLast('/'))
                    204 to ""
                }
                path == "/lists/l-custom/items" -> 200 to """{"items":[${custom.joinToString(",") { """{"type":"movie","imdbId":"$it"}""" }}]}"""
                else -> routes(request)
            }
        }
        val tracker = tracker(this, fake)
        val lists = lists(this, tracker)
        lists.refresh(); settle()

        lists.apply(preview, mapOf("fetch:list:l-custom" to true)); settle()
        val add = fake.sent.single { it.request.url.encodedPath == "/lists/l-custom/items" && it.request.method == "POST" }
        assertEquals("movie", json(add.body)["type"]!!.jsonPrimitive.content)
        assertEquals("tt0111161", json(add.body)["imdbId"]!!.jsonPrimitive.content)
        assertTrue(lists.isMember(lists.lists(ListProvider.FETCH_WEBSITE)[1], preview.id))

        lists.apply(preview, mapOf("fetch:list:l-custom" to false)); settle()
        assertTrue(fake.sent.any { it.request.method == "DELETE" && it.request.url.encodedPath == "/lists/l-custom/items/movie/tt0111161" })

        lists.create(ListProvider.FETCH_WEBSITE, "Weekend", null, ListPrivacy.PRIVATE); settle()
        val created = fake.sent.single { it.request.url.encodedPath == "/lists" && it.request.method == "POST" }
        assertEquals("custom", json(created.body)["kind"]!!.jsonPrimitive.content)

        lists.update(lists.lists(ListProvider.FETCH_WEBSITE)[1], "Spooky", null, ListPrivacy.PRIVATE); settle()
        val rename = fake.sent.single { it.request.method == "PATCH" }
        assertEquals("/lists/l-custom", rename.request.url.encodedPath)
        assertEquals("Spooky", json(rename.body)["name"]!!.jsonPrimitive.content)

        var message: String? = null
        val withMessages = ListsState(this, TrackingState(this, TrackingStore(File(dir, "t2.json")), testCredentials, FakeHttp.always(200, "[]").client),
            localListsFile(dir), { emptyList() }, { _, _ -> }, onMessage = { message = it }, website = tracker)
        withMessages.delete(lists.lists(ListProvider.FETCH_WEBSITE)[0]); settle()
        assertEquals("Couldn't update your lists: The watchlist can't be deleted", message)
    }
}
