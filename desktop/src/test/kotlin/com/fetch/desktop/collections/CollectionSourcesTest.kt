package com.fetch.desktop.collections

import com.fetch.desktop.LocalList
import com.fetch.desktop.data.AddonClient
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.tmdb.TmdbClient
import com.fetch.desktop.tmdb.TmdbCollectionSources
import com.fetch.desktop.tracking.TraktClient
import com.fetch.desktop.tracking.TrackingCredentials
import com.fetch.desktop.tracking.TraktListPage
import com.fetch.desktop.tracking.TraktPublicItem
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogExtra
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.FetchListCollectionSource
import com.fetch.tv.domain.model.LocalListCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionFilters
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import com.fetch.tv.domain.model.TmdbCollectionSort
import com.fetch.tv.domain.model.TmdbCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionSourceType
import com.fetch.tv.domain.model.TraktCollectionSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class CollectionSourcesTest {

    private fun tmdb(http: CollectionsFakeHttp, key: String? = "key") =
        TmdbCollectionSources(TmdbClient(http.client, apiKey = { key }, baseUrl = "https://tmdb.test/3/".toHttpUrl()), today = { LocalDate.of(2026, 11, 3) })

    private fun ok(body: String) = FakeAnswer(200, body)

    // --- TMDB, as the TV's TmdbCollectionSourceResolver ---

    @Test
    fun `a network discovers aired shows with the TV's parameters`() = runBlocking {
        val http = CollectionsFakeHttp.routes("/discover/tv" to ok("""{"page":1,"total_pages":3,"results":[{"id":1399,"name":"Game of Thrones","first_air_date":"2011-04-17","poster_path":"/p.jpg","vote_average":8.4}]}"""))
        val page = tmdb(http).resolve(TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "HBO", 49, TmdbCollectionMediaType.MOVIE), 1, "en")
        val url = http.sent.single().url
        assertEquals("49", url.queryParameter("with_networks"))
        assertEquals("0|3|4", url.queryParameter("with_status"))
        assertEquals("2026-11-03", url.queryParameter("first_air_date.lte"))
        assertEquals("popularity.desc", url.queryParameter("sort_by"))
        assertEquals("key", url.queryParameter("api_key"))
        val item = page.items.single()
        assertEquals("tmdb:1399", item.id)
        assertEquals(ContentType.SERIES, item.type)
        assertEquals("2011", item.releaseInfo)
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", item.poster)
        assertTrue(page.hasMore)
    }

    @Test
    fun `discover sends the filters, a company its id, and watch providers a region`() = runBlocking {
        val http = CollectionsFakeHttp.routes("/discover/movie" to ok("""{"page":2,"total_pages":2,"results":[]}"""))
        val filters = TmdbCollectionFilters(
            withGenres = "28,12", withoutGenres = "16", releaseDateGte = "2000-01-01", voteAverageGte = 7.0,
            voteCountGte = 200, withOriginalLanguage = "ko", withKeywords = "9715", year = 2019, withWatchProviders = "8"
        )
        val page = tmdb(http).resolve(TmdbCollectionSource(TmdbCollectionSourceType.DISCOVER, "D", null, TmdbCollectionMediaType.MOVIE, "first_air_date.desc", filters), 2, "pt-br")
        val url = http.sent.single().url
        assertEquals("2", url.queryParameter("page"))
        assertEquals("pt-BR", url.queryParameter("language"))
        assertEquals("primary_release_date.desc", url.queryParameter("sort_by"))
        assertEquals("28,12", url.queryParameter("with_genres"))
        assertEquals("16", url.queryParameter("without_genres"))
        assertEquals("2000-01-01", url.queryParameter("primary_release_date.gte"))
        assertEquals("7.0", url.queryParameter("vote_average.gte"))
        assertEquals("200", url.queryParameter("vote_count.gte"))
        assertEquals("ko", url.queryParameter("with_original_language"))
        assertEquals("9715", url.queryParameter("with_keywords"))
        assertEquals("2019", url.queryParameter("year"))
        assertEquals("US", url.queryParameter("watch_region"))
        assertEquals("flatrate|free|ads|rent|buy", url.queryParameter("with_watch_monetization_types"))
        assertNull(url.queryParameter("with_status"))
        assertFalse(page.hasMore)

        val company = TmdbCollectionSources.discoverQuery(
            TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Pixar", 3, TmdbCollectionMediaType.TV), TmdbCollectionMediaType.TV, 1, "en", LocalDate.of(2026, 1, 1)
        ).toMap()
        assertEquals("3", company["with_companies"])
        assertNull(company["first_air_date.lte"])
    }

    @Test
    fun `a list pages, keeps its order or sorts in memory`() = runBlocking {
        val body = """{"name":"Picks","page":1,"total_pages":2,"items":[
            {"id":1,"media_type":"movie","title":"Old","release_date":"1990-01-01","vote_average":9.0},
            {"id":2,"media_type":"tv","name":"New","first_air_date":"2020-01-01","vote_average":7.0},
            {"id":1,"media_type":"movie","title":"Old","release_date":"1990-01-01"}]}"""
        val http = CollectionsFakeHttp.routes("/list/77" to ok(body))
        val source = TmdbCollectionSource(TmdbCollectionSourceType.LIST, "Picks", 77, sortBy = TmdbCollectionSort.ORIGINAL.value)
        val page = tmdb(http).resolve(source, 1, "en")
        assertEquals(listOf("tmdb:1", "tmdb:2"), page.items.map { it.id })
        assertEquals(ContentType.SERIES, page.items[1].type)
        assertTrue(page.hasMore)
        val newest = tmdb(http).resolve(source.copy(sortBy = TmdbCollectionSort.RELEASE_DATE_DESC.value), 1, "en")
        assertEquals(listOf("New", "Old"), newest.items.map { it.name })
    }

    @Test
    fun `a director's credits are the films they directed, a collection comes whole`() = runBlocking {
        val http = CollectionsFakeHttp.routes(
            "/person/525/combined_credits" to ok("""{"cast":[{"id":5,"media_type":"movie","title":"Cameo"}],
                "crew":[{"id":10,"media_type":"movie","title":"Inception","job":"Director","release_date":"2010-07-16"},
                        {"id":11,"media_type":"movie","title":"Produced","job":"Producer"},
                        {"id":12,"media_type":"tv","name":"A show","job":"Director"}]}"""),
            "/collection/119" to ok("""{"name":"LOTR","parts":[{"id":120,"title":"Fellowship"},{"id":121,"title":"Two Towers"}]}""")
        )
        val directed = tmdb(http).resolve(TmdbCollectionSource(TmdbCollectionSourceType.DIRECTOR, "Nolan", 525), 1, "en")
        assertEquals(listOf("Inception"), directed.items.map { it.name })
        assertFalse(directed.hasMore)
        val cast = tmdb(http).resolve(TmdbCollectionSource(TmdbCollectionSourceType.PERSON, "Nolan", 525), 1, "en")
        assertEquals(listOf("Cameo"), cast.items.map { it.name })
        val lotr = tmdb(http).resolve(TmdbCollectionSource(TmdbCollectionSourceType.COLLECTION, "LOTR", 119, sortBy = "original"), 1, "en")
        assertEquals(listOf("tmdb:120", "tmdb:121"), lotr.items.map { it.id })
    }

    @Test
    fun `tmdb without a key says where to add one`() = runBlocking {
        try {
            tmdb(CollectionsFakeHttp.routes(), key = null).resolve(TmdbCollectionSource(TmdbCollectionSourceType.DISCOVER, "D"), 1, "en")
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Settings › TMDB"))
        }
    }

    @Test
    fun `tmdb ids come from numbers and addresses`() {
        assertEquals(420, TmdbCollectionSources.parseTmdbId("420"))
        assertEquals(10, TmdbCollectionSources.parseTmdbId("https://www.themoviedb.org/collection/10-star-wars"))
        assertEquals(213, TmdbCollectionSources.parseTmdbId("https://www.themoviedb.org/network/213"))
        assertEquals(5, TmdbCollectionSources.parseTmdbId("x?id=5"))
        assertNull(TmdbCollectionSources.parseTmdbId("nothing"))
    }

    // --- Addon catalogs, Trakt and local lists ---

    private val catalog = CatalogDescriptor(
        type = ContentType.MOVIE, id = "top", name = "popular",
        extra = listOf(CatalogExtra("genre", options = listOf("Action", "Drama")), CatalogExtra("skip"))
    )
    private val addon = Addon(
        id = "com.example", name = "Example", version = "1", description = null, logo = null,
        baseUrl = "https://addon.test", catalogs = listOf(catalog), types = listOf(ContentType.MOVIE), resources = emptyList()
    )

    private fun resolver(
        http: CollectionsFakeHttp = CollectionsFakeHttp.routes(),
        addons: List<Addon> = listOf(addon),
        trakt: TraktListReader = TraktListReader { _, _, _, _, _, _ -> null },
        local: List<LocalList> = emptyList()
    ) = CollectionSourceResolver(
        addons = { addons },
        addonClient = AddonClient.create(http.client),
        tmdb = tmdb(http),
        tmdbLanguage = { "en" },
        trakt = trakt,
        localLists = { local }
    )

    private val metas = """{"metas":[{"id":"tt1","type":"movie","name":"One"},{"id":"tt2","type":"movie","name":"Two"}]}"""

    @Test
    fun `an addon catalog loads with its genre and pages by skip`() = runBlocking {
        val http = CollectionsFakeHttp { FakeAnswer(200, metas) }
        val source = AddonCatalogCollectionSource("com.example", "movie", "top", genre = "Action")
        val first = resolver(http).load(source, 1)
        assertEquals(listOf("tt1", "tt2"), first.items.map { it.id })
        assertTrue(first.hasMore)
        assertTrue(http.sent[0].url.encodedPath.contains("genre=Action"))
        resolver(http).load(source, 2)
        assertTrue(http.sent[1].url.encodedPath.contains("skip=100"))
        // "None" means no genre, as on TV.
        resolver(http).load(source.copy(genre = "None"), 1)
        assertFalse(http.sent[2].url.encodedPath.contains("genre"))
    }

    @Test
    fun `a missing addon is read from its address, or says it's missing`() = runBlocking {
        val manifest = """{"id":"com.example","version":"1","name":"Example","resources":["catalog"],"types":["movie"],
            "catalogs":[{"type":"movie","id":"top","name":"Popular"}]}"""
        val http = CollectionsFakeHttp { r -> if (r.url.encodedPath.endsWith("manifest.json")) FakeAnswer(200, manifest) else FakeAnswer(200, metas) }
        val page = resolver(http, addons = emptyList()).load(AddonCatalogCollectionSource("com.example", "movie", "top", addonUrl = "https://addon.test"), 1)
        assertEquals(2, page.items.size)
        // That catalog has no skip extra, so it's one page.
        assertFalse(page.hasMore)
        try {
            resolver(http, addons = emptyList()).load(AddonCatalogCollectionSource("gone", "movie", "top"), 1)
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertEquals(CollectionSourceResolver.ADDON_MISSING, e.message)
        }
    }

    @Test
    fun `a trakt list maps its items and pages by the page count`() = runBlocking {
        var asked: List<Any>? = null
        val reader = TraktListReader { id, type, page, limit, sortBy, sortHow ->
            asked = listOf(id, type, page, limit, sortBy, sortHow)
            TraktListPage(listOf(TraktPublicItem("tt9", true, "Show", 2020, "About", 8.1, listOf("drama"), 50, "https://p", "https://f", null)), pageCount = 3)
        }
        val page = resolver(trakt = reader).load(TraktCollectionSource("Best", 42L, TmdbCollectionMediaType.TV, "ADDED", "desc"), 1)
        assertEquals(listOf(42L, "show", 1, 50, "added", "desc"), asked)
        val item = page.items.single()
        assertEquals(ContentType.SERIES, item.type)
        assertEquals("tt9", item.imdbId)
        assertEquals("50 min", item.runtime)
        assertTrue(page.hasMore)
    }

    @Test
    fun `trakt's public list items are read with the client id and paging header`() = runBlocking {
        val body = """[{"rank":1,"type":"movie","movie":{"title":"Heat","year":1995,"ids":{"trakt":1,"imdb":"tt0113277","tmdb":949},
            "images":{"poster":["walter-r2.trakt.tv/images/p.jpg"]}}},
            {"rank":2,"type":"movie","movie":{"title":"No imdb","ids":{"trakt":2,"tmdb":50}}}]"""
        val http = CollectionsFakeHttp { FakeAnswer(200, body, mapOf("X-Pagination-Page-Count" to "4")) }
        val trakt = TraktClient(http.client, TrackingCredentials("id", "secret", ""), appVersion = "test")
        val page = trakt.publicListItems(null, "42", "movie", 2, 50, "rank", "asc")
        val sent = http.sent.single()
        assertEquals("/lists/42/items/movie", sent.url.encodedPath)
        assertEquals("2", sent.url.queryParameter("page"))
        assertEquals("rank", sent.url.queryParameter("sort_by"))
        assertEquals("id", sent.header("trakt-api-key"))
        assertNull(sent.header("Authorization"))
        assertEquals(4, page.pageCount)
        assertEquals(listOf("tt0113277", "tmdb:50"), page.items.map { it.id })
        assertEquals("https://walter-r2.trakt.tv/images/p.jpg", page.items[0].poster)
    }

    @Test
    fun `a local list shows its titles newest first, by type`() = runBlocking {
        val list = LocalList(
            "l1", "Weekend", items = listOf(
                LibraryEntry("tt1", "movie", "Older", addedAt = 1),
                LibraryEntry("tt2", "series", "A show", addedAt = 3),
                LibraryEntry("tt3", "movie", "Newer", addedAt = 2)
            )
        )
        val all = resolver(local = listOf(list)).load(LocalListCollectionSource("l1", "Weekend"), 1)
        assertEquals(listOf("tt2", "tt3", "tt1"), all.items.map { it.id })
        assertFalse(all.hasMore)
        val movies = resolver(local = listOf(list)).load(LocalListCollectionSource("l1", "Weekend", TmdbCollectionMediaType.MOVIE), 1)
        assertEquals(listOf("tt3", "tt1"), movies.items.map { it.id })
        try {
            resolver().load(FetchListCollectionSource("w", "Web"), 1)
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("website"))
        }
    }

    @Test
    fun `tab labels follow the TV`() {
        assertEquals("Popular · Action" to "Movies", CollectionSourceResolver.label(AddonCatalogCollectionSource("com.example", "movie", "top", "Action"), listOf(addon)))
        assertEquals("Movies" to "Movies", CollectionSourceResolver.label(AddonCatalogCollectionSource("x", "movie", "nope"), emptyList()))
        assertEquals("HBO" to "Network", CollectionSourceResolver.label(TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "HBO", 49), emptyList()))
    }

    // --- The folder page ---

    @Test
    fun `a folder loads every source and its All tab takes turns between them`() = runBlocking {
        val dir = Files.createTempDirectory("folder").toFile()
        try {
            val other = catalog.copy(id = "new", name = "new")
            val two = addon.copy(catalogs = listOf(catalog, other))
            val http = CollectionsFakeHttp { r ->
                if (r.url.encodedPath.contains("/new")) FakeAnswer(200, """{"metas":[{"id":"tt8","type":"movie","name":"Eight"},{"id":"tt1","type":"movie","name":"One"}]}""")
                else FakeAnswer(200, metas)
            }
            val collection = Collection(
                "c", "Mine", folders = listOf(
                    CollectionFolder("f", "Both", sources = listOf(
                        AddonCatalogCollectionSource("com.example", "movie", "top"),
                        AddonCatalogCollectionSource("com.example", "movie", "new")
                    ))
                )
            )
            CollectionsStore.inProfile(dir).save(listOf(collection))
            val state = CollectionsState(
                this, CollectionsStore.inProfile(dir),
                resolver(http, addons = listOf(two)),
                addons = { listOf(two) }
            )
            state.openFolder("c", "f")
            val view = state.folder!!
            assertEquals(listOf("All", "Popular", "New"), view.tabs.map { it.label })
            repeat(50) { if (state.folder!!.tabs.any { it.loading }) delay(20) }
            val all = state.folder!!.tabs[0]
            assertEquals(listOf("tt1", "tt8", "tt2"), all.items.map { it.id })
            assertTrue(all.hasMore)

            // With "All" off, or one source, there's no All tab.
            state.save(collection.copy(showAllTab = false))
            state.openFolder("c", "f")
            assertEquals(listOf("Popular", "New"), state.folder!!.tabs.map { it.label })
            repeat(50) { if (state.folder!!.tabs.any { it.loading }) delay(20) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the editor's rules`() {
        val pickable = CollectionEditing.pickableCatalogs(listOf(addon)).single()
        assertNull(pickable.genreFor(null))
        val required = PickableCatalog(addon, catalog.copy(extra = listOf(CatalogExtra("genre", isRequired = true, options = listOf("Drama")))))
        assertEquals("Drama", required.genreFor(null))
        var folder = CollectionEditing.newFolder("f")
        folder = CollectionEditing.toggleCatalog(folder, pickable)
        assertEquals(AddonCatalogCollectionSource("com.example", "movie", "top", null, "https://addon.test"), folder.sources.single())
        folder = CollectionEditing.setGenre(folder, 0, "Drama", listOf(pickable))
        assertEquals("Drama", (folder.sources.single() as AddonCatalogCollectionSource).genre)
        assertTrue(CollectionEditing.toggleCatalog(folder, pickable).sources.isEmpty())

        val withEmoji = folder.copy(coverEmoji = "🎬")
        val company = TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Pixar", 3)
        val covered = CollectionEditing.addSources(withEmoji, listOf(company, company), "https://logo")
        assertEquals(2, covered.sources.size)
        assertEquals("https://logo", covered.coverImageUrl)
        assertNull(covered.coverEmoji)
        // A discover source brings no cover.
        assertNull(CollectionEditing.addSources(folder, listOf(TmdbCollectionSource(TmdbCollectionSourceType.DISCOVER, "D")), "https://x").coverImageUrl)

        assertEquals(listOf(TmdbCollectionMediaType.TV), CollectionEditing.tmdbMediaTypes(TmdbCollectionSourceType.NETWORK, MediaChoice.BOTH))
        assertEquals(2, CollectionEditing.tmdbMediaTypes(TmdbCollectionSourceType.COMPANY, MediaChoice.BOTH).size)
        assertEquals("Pixar Series", CollectionEditing.titleForMedia("Pixar", TmdbCollectionMediaType.TV, true))
        assertEquals("Untitled", CollectionEditing.cleanFolder(CollectionFolder("x", "  ", coverImageUrl = " ")).title)
        assertNull(CollectionEditing.cleanFolder(CollectionFolder("x", "", coverImageUrl = " ")).coverImageUrl)

        assertEquals("123", CollectionEditing.parseTraktList("123"))
        assertEquals("my-list", CollectionEditing.parseTraktList("https://trakt.tv/users/sam/lists/my-list?sort=rank"))
        assertEquals("456", CollectionEditing.parseTraktList("https://trakt.tv/lists/456"))
        assertNull(CollectionEditing.parseTraktList("not a list!"))
    }
}
