package com.fetch.desktop

import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.tracking.FakeHttp
import com.fetch.desktop.tracking.SimklClient
import com.fetch.desktop.tracking.TrackedItem
import com.fetch.desktop.tracking.TrackingAccount
import com.fetch.desktop.tracking.TrackingAccounts
import com.fetch.desktop.tracking.TrackingStore
import com.fetch.desktop.tracking.TraktClient
import com.fetch.desktop.tracking.testCredentials
import com.fetch.desktop.tracking.testTokens
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ListsStateTest {

    private val dir: File = Files.createTempDirectory("lists").toFile()
    private val preview = MetaPreview(
        id = "tt0111161", type = ContentType.MOVIE, name = "The Shawshank Redemption", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = "1994", imdbRating = null, genres = emptyList()
    )

    private fun traktRoutes(request: okhttp3.Request): Pair<Int, String> {
        val path = request.url.encodedPath
        return when {
            path == "/sync/watchlist/movies" || path == "/sync/watchlist/shows" -> 200 to "[]"
            path == "/users/me/lists" && request.method == "GET" ->
                200 to """[{"name":"Favourites","privacy":"private","item_count":1,"ids":{"trakt":55,"slug":"favourites"}}]"""
            path == "/users/me/lists" -> 201 to """{"name":"New","privacy":"public","ids":{"trakt":77}}"""
            path == "/users/me/lists/55/items/movie,show" ->
                200 to """[{"listed_at":"2024-01-01T00:00:00.000Z","type":"movie","movie":{"title":"Heat","year":1995,"ids":{"imdb":"tt0113277"}}}]"""
            else -> 201 to "{}"
        }
    }

    private suspend fun lists(scope: CoroutineScope, fake: FakeHttp, library: MutableList<LibraryEntry> = mutableListOf()): ListsState {
        val store = TrackingStore(File(dir, "tracking.json")).apply {
            save(TrackingAccounts(trakt = TrackingAccount(testTokens, "me")))
        }
        val tracking = TrackingState(
            scope, store, testCredentials,
            trakt = TraktClient(fake.client, testCredentials, appVersion = "test"),
            simkl = SimklClient(fake.client, testCredentials, appVersion = "test")
        )
        return ListsState(scope, tracking, localListsFile(dir), { library }, { p, on ->
            if (on) library += LibraryEntry.from(p, addedAt = 1) else library.removeAll { it.id == p.id }
        }, onMessage = {})
    }

    private suspend fun CoroutineScope.settle() = coroutineContext.job.children.forEach { it.join() }

    @Test
    fun `Trakt's lists become Library tabs after the watchlist`() = runBlocking {
        val state = lists(this, FakeHttp(::traktRoutes))
        state.refresh()
        settle()
        val trakt = state.lists(ListProvider.TRAKT)
        assertEquals(listOf("Watchlist", "Favourites"), trakt.map { it.title })
        assertEquals("tt0113277", trakt[1].items.single().id)
        assertTrue(trakt[1].editable)
        assertEquals(listOf(ListProvider.LOCAL, ListProvider.TRAKT), state.providers)
    }

    @Test
    fun `adding to a Trakt list posts the title and updates the tab`() = runBlocking {
        val fake = FakeHttp(::traktRoutes)
        val state = lists(this, fake)
        state.refresh(); settle()
        fake.sent.clear()
        state.apply(preview, mapOf("trakt:list:55" to true, "local:library" to true))
        settle()
        val sent = fake.sent.single()
        assertEquals("/users/me/lists/55/items", sent.request.url.encodedPath)
        assertEquals(Json.parseToJsonElement("""{"movies":[{"ids":{"imdb":"tt0111161"}}]}"""), Json.parseToJsonElement(sent.body))
        assertTrue(state.isMember(state.lists(ListProvider.TRAKT)[1], preview.id))
        assertTrue("the local library box adds here only", state.isMember(state.lists(ListProvider.LOCAL)[0], preview.id))
    }

    @Test
    fun `creating, reordering and deleting Trakt lists`() = runBlocking {
        val fake = FakeHttp(::traktRoutes)
        val state = lists(this, fake)
        state.refresh(); settle()
        fake.sent.clear()
        state.create(ListProvider.TRAKT, " New ", "Desc", ListPrivacy.PUBLIC); settle()
        assertEquals(Json.parseToJsonElement("""{"name":"New","description":"Desc","privacy":"public"}"""), Json.parseToJsonElement(fake.sent.last().body))
        val created = state.lists(ListProvider.TRAKT).last()
        assertEquals("New", created.title)
        state.move(created, -1); settle()
        assertEquals("/users/me/lists/reorder", fake.sent.last().request.url.encodedPath)
        assertEquals(Json.parseToJsonElement("""{"rank":[77,55]}"""), Json.parseToJsonElement(fake.sent.last().body))
        state.delete(state.lists(ListProvider.TRAKT).first { it.title == "Favourites" }); settle()
        assertEquals("DELETE", fake.sent.last().request.method)
        assertEquals(listOf("Watchlist", "New"), state.lists(ListProvider.TRAKT).map { it.title })
    }

    @Test
    fun `local lists are saved, edited and reloaded`() = runBlocking {
        val state = lists(this, FakeHttp.always(200, "[]"))
        state.create(ListProvider.LOCAL, "Weekend", null, ListPrivacy.PRIVATE)
        val list = state.lists(ListProvider.LOCAL).last()
        state.apply(preview, mapOf(list.key to true))
        state.update(state.lists(ListProvider.LOCAL).last(), "Weekend films", "Saturdays", ListPrivacy.PRIVATE)
        val reloaded = lists(this, FakeHttp.always(200, "[]")).lists(ListProvider.LOCAL)
        assertEquals(listOf("Library", "Weekend films"), reloaded.map { it.title })
        assertEquals("Saturdays", reloaded.last().description)
        assertEquals(listOf("tt0111161"), reloaded.last().items.map { it.id })
        state.apply(preview, mapOf(list.key to false))
        assertFalse(state.isMember(state.lists(ListProvider.LOCAL).last(), preview.id))
    }

    @Test
    fun `changes send only what changed, and Simkl as one choice`() {
        val simkl = ListsState.SIMKL_STATUSES.map { (s, t, _) -> LibraryList("simkl:status:$s", ListProvider.SIMKL, t, emptyList(), selectionGroup = ListsState.SIMKL_GROUP) }
        val all = listOf(LibraryList("local:library", ListProvider.LOCAL, "Library", emptyList()), LibraryList("local:a", ListProvider.LOCAL, "A", emptyList())) + simkl
        assertEquals(
            mapOf("local:a" to true, "simkl:status:hold" to true),
            ListsState.changes(all, before = setOf("local:library", "simkl:status:watching"), after = setOf("local:library", "local:a", "simkl:status:hold"))
        )
        assertEquals(mapOf("simkl:status:watching" to false), ListsState.changes(all, setOf("simkl:status:watching"), emptySet()))
        assertTrue(ListsState.changes(all, setOf("local:a"), setOf("local:a")).isEmpty())
    }

    @Test
    fun `Simkl status lists and moves`() = runBlocking {
        val fake = FakeHttp { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/sync/all-items/movies") -> 200 to """{"movies":[{"status":"plantowatch","added_to_watchlist_at":"2024-01-01T00:00:00Z","movie":{"title":"Heat","year":1995,"ids":{"imdb":"tt0113277"}}}]}"""
                path.endsWith("/sync/all-items/shows") -> 200 to """{"shows":[{"status":"watching","show":{"title":"Lost","ids":{"imdb":"tt0411008"}}}]}"""
                else -> 200 to "{}"
            }
        }
        val simkl = SimklClient(fake.client, testCredentials, appVersion = "test")
        val byStatus = simkl.statusLists(testTokens)
        assertEquals("tt0113277", byStatus.getValue("plantowatch").single().imdbId)
        assertEquals("tt0411008", byStatus.getValue("watching").single().imdbId)
        fake.sent.clear()
        simkl.setStatus(testTokens, TrackedItem("tt0411008", TrackedItem.TYPE_SERIES, title = "Lost"), "hold")
        val sent = fake.sent.single()
        assertTrue(sent.request.url.encodedPath.endsWith("/sync/add-to-list"))
        assertEquals(Json.parseToJsonElement("""{"shows":[{"to":"hold","title":"Lost","ids":{"imdb":"tt0411008"}}]}"""), Json.parseToJsonElement(sent.body))
    }
}
