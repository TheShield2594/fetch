package com.fetch.desktop.tmdb

import com.fetch.desktop.data.TmdbSettings
import com.fetch.desktop.ratings.RatingsFakeHttp
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbClientTest {

    private val movieDetails = """
        {"id":120,"title":"The Fellowship of the Ring","original_title":"The Fellowship of the Ring","overview":"A hobbit.",
         "genres":[{"id":12,"name":"Adventure"}],"release_date":"2001-12-18","runtime":179,"status":"Released",
         "vote_average":8.4,"vote_count":20000,"original_language":"en","production_countries":[{"iso_3166_1":"NZ"}],
         "poster_path":"/p.jpg","backdrop_path":"/b.jpg","imdb_id":"tt0120737",
         "production_companies":[{"id":12,"name":"New Line Cinema","logo_path":"/nl.png"}],
         "belongs_to_collection":{"id":119,"name":"The Lord of the Rings Collection"},
         "credits":{"cast":[{"id":109,"name":"Elijah Wood","character":"Frodo","order":0,"profile_path":"/e.jpg"}],
                    "crew":[{"id":108,"name":"Peter Jackson","job":"Director","department":"Directing"},
                            {"id":1,"name":"Fran Walsh","job":"Screenplay","department":"Writing"}]},
         "images":{"logos":[{"file_path":"/de.png","iso_639_1":"de"},{"file_path":"/en.png","iso_639_1":"en"}],"backdrops":[{"file_path":"/clean.jpg","iso_639_1":null}]},
         "videos":{"results":[{"key":"teaser1","site":"YouTube","type":"Teaser"},{"key":"trailer1","site":"YouTube","type":"Trailer"},{"key":"v","site":"Vimeo","type":"Trailer"}]}}
    """

    private val tvDetails = """
        {"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20","last_air_date":"2013-09-29","status":"Ended",
         "episode_run_time":[47],"created_by":[{"id":66633,"name":"Vince Gilligan"}],
         "networks":[{"id":174,"name":"AMC","logo_path":"/amc.png"}],"seasons":[{"season_number":0},{"season_number":1}],
         "external_ids":{"imdb_id":"tt0903747"},
         "aggregate_credits":{"cast":[{"id":17419,"name":"Bryan Cranston","order":0,"roles":[{"character":"Walter White"}]}],"crew":[]}}
    """

    private fun client(http: RatingsFakeHttp) = TmdbClient(http.client, apiKey = { "key" }, baseUrl = "https://tmdb.test/3/".toHttpUrl())

    @Test
    fun `a movie's details map like the TV's enrichment`() = runBlocking {
        val title = client(RatingsFakeHttp.routes("/movie/120" to movieDetails)).title(120, isSeries = false, language = "en")!!
        assertEquals("The Fellowship of the Ring", title.title)
        assertEquals("2001", title.releaseInfo)
        assertEquals(179, title.runtimeMinutes)
        assertEquals(listOf("Peter Jackson"), title.directors.map { it.name })
        assertEquals(108, title.directors.single().tmdbId)
        assertEquals("Frodo", title.cast.single().character)
        assertEquals("https://image.tmdb.org/t/p/w500/en.png", title.logo)
        assertEquals("https://image.tmdb.org/t/p/w1280/clean.jpg", title.backdrop)
        assertEquals(listOf("trailer1", "teaser1"), title.trailers)
        assertEquals(119, title.collectionId)
        assertEquals("tt0120737", title.imdbId)
        assertEquals(12, title.companies.single().tmdbId)
    }

    @Test
    fun `a show uses aggregate credits, creators and a year range`() = runBlocking {
        val title = client(RatingsFakeHttp.routes("/tv/1396" to tvDetails)).title(1396, isSeries = true, language = "en")!!
        assertEquals("2008–2013", title.releaseInfo)
        assertEquals(47, title.runtimeMinutes)
        assertEquals("Walter White", title.cast.single().character)
        assertEquals("Creator", title.writers.single().character)
        assertEquals(listOf(0, 1), title.seasons)
        assertEquals("tt0903747", title.imdbId)
        assertEquals(174, title.networks.single().tmdbId)
    }

    @Test
    fun `find maps IMDb ids and the key goes as api_key`() = runBlocking {
        val http = RatingsFakeHttp.routes("/find/tt0903747" to """{"movie_results":[],"tv_results":[{"id":1396}]}""")
        val c = client(http)
        assertEquals(1396, c.tmdbId("tt0903747:1:2", isSeries = true))
        assertEquals(1396, c.tmdbId("tmdb:1396", isSeries = true))
        assertEquals("key", http.sent.single().url.queryParameter("api_key"))
        assertEquals("tt0903747", c.imdbId(1396, isSeries = true))
        assertEquals("cached both ways", 1, http.sent.size)
    }

    @Test
    fun `a bearer token goes in the header`() = runBlocking {
        val http = RatingsFakeHttp.routes("/find/tt1" to """{"movie_results":[{"id":5}]}""")
        TmdbClient(http.client, apiKey = { "eyJhbGciOi.payload.sig" }, baseUrl = "https://tmdb.test/3/".toHttpUrl()).tmdbId("tt1", false)
        val sent = http.sent.single()
        assertEquals("Bearer eyJhbGciOi.payload.sig", sent.header("Authorization"))
        assertNull(sent.url.queryParameter("api_key"))
    }

    @Test
    fun `recommendations favour well-rated titles and come back with IMDb ids`() = runBlocking {
        val http = RatingsFakeHttp.routes(
            "/movie/120/recommendations" to """{"results":[
                {"id":1,"title":"Obscure","vote_count":2,"vote_average":4.0,"original_language":"xx"},
                {"id":121,"title":"The Two Towers","vote_count":19000,"vote_average":8.3,"original_language":"en","backdrop_path":"/tt.jpg","release_date":"2002-12-18"},
                {"id":122,"title":"The Return of the King","vote_count":21000,"vote_average":8.5,"original_language":"en"}]}""",
            "/movie/121/external_ids" to """{"imdb_id":"tt0167261"}""",
            "/movie/122/external_ids" to """{"imdb_id":null}"""
        )
        val recs = client(http).recommendations(120, isSeries = false, language = "en")
        assertEquals(listOf("The Return of the King", "The Two Towers"), recs.map { it.name })
        assertEquals(listOf("tmdb:122", "tt0167261"), recs.map { it.id })
        assertEquals(PosterShape.LANDSCAPE, recs.first().posterShape)
        assertEquals("2002", recs.last().releaseInfo)
    }

    @Test
    fun `a person's credits lead with their department and skip talk shows`() = runBlocking {
        val http = RatingsFakeHttp.routes(
            "/person/108" to """{"id":108,"name":"Peter Jackson","known_for_department":"Directing","birthday":"1961-10-31",
              "combined_credits":{"cast":[{"id":9,"media_type":"tv","name":"Late Show","genre_ids":[10767],"popularity":99.0},
                                          {"id":7,"media_type":"movie","title":"Cameo","popularity":5.0}],
                                  "crew":[{"id":120,"media_type":"movie","title":"Fellowship","department":"Directing","popularity":50.0},
                                          {"id":8,"media_type":"movie","title":"Produced only","department":"Production","popularity":80.0}]}}"""
        )
        val person = client(http).person(108, "en")!!
        assertEquals(listOf("Fellowship", "Cameo"), person.credits.map { it.name })
        assertEquals("Directing", person.knownFor)
    }

    @Test
    fun `discover pages a network's shows`() = runBlocking {
        val http = RatingsFakeHttp.routes("/discover/tv" to """{"page":2,"total_pages":7,"results":[{"id":1396,"name":"Breaking Bad"}]}""")
        val page = client(http).discover(TmdbEntityKind.NETWORK, 174, isSeries = false, page = 2, language = "en")
        assertEquals(7, page.totalPages)
        assertEquals(ContentType.SERIES, page.items.single().type)
        assertEquals("174", http.sent.single().url.queryParameter("with_networks"))
    }

    @Test
    fun `no key means no requests`() = runBlocking {
        val http = RatingsFakeHttp.routes()
        val c = TmdbClient(http.client, apiKey = { null }, baseUrl = "https://tmdb.test/3/".toHttpUrl())
        assertNull(c.title(1, false, "en"))
        assertTrue(http.sent.isEmpty())
    }

    @Test
    fun `languages normalize as on TV`() {
        assertEquals("pt-BR", TmdbClient.normalizeLanguage("pt-br"))
        assertEquals("es-MX", TmdbClient.normalizeLanguage("es-419"))
        assertEquals("fr", TmdbClient.normalizeLanguage("FR"))
        assertEquals("en", TmdbClient.normalizeLanguage(null))
        assertEquals(42, TmdbClient.parseTmdbId("tmdb:42:1:3"))
        assertNull(TmdbClient.parseTmdbId("tt42"))
    }

    // --- Merging into an addon's meta ---

    private val addonMeta = Meta(
        id = "tt0903747", type = ContentType.SERIES, name = "Breaking Bad", poster = "addon-poster", posterShape = PosterShape.POSTER,
        background = "addon-bg", logo = null, description = "Addon text", releaseInfo = "2008-2013", imdbRating = 9.5f,
        genres = listOf("Drama"), runtime = null, director = emptyList(), cast = listOf("Someone"),
        videos = listOf(Video(id = "tt0903747:1:1", title = "Episode 1", released = null, thumbnail = null, season = 1, episode = 1, overview = null)),
        country = null, awards = null, language = null, links = emptyList()
    )

    private fun tmdbTitle() = runBlocking { client(RatingsFakeHttp.routes("/tv/1396" to tvDetails)).title(1396, true, "en")!! }

    private val episodes = mapOf((1 to 1) to TmdbEpisode(1, 1, "Pilot", "Walt.", "still", "2008-01-20", 58, 8.2f))

    @Test
    fun `nothing changes while TMDB is off`() {
        assertEquals(addonMeta, TmdbEnrich.apply(addonMeta, tmdbTitle(), episodes, TmdbSettings(enabled = false)))
    }

    @Test
    fun `each part follows its switch`() {
        val all = TmdbEnrich.apply(addonMeta, tmdbTitle(), episodes, TmdbSettings(enabled = true))
        assertEquals("Pilot", all.videos.single().title)
        assertEquals("still", all.videos.single().thumbnail)
        assertEquals(8.2, all.videos.single().rating!!, 0.01)
        assertEquals(listOf("Vince Gilligan", "Bryan Cranston"), all.castMembers.map { it.name })
        assertEquals("AMC", all.networks.single().name)
        assertEquals("addon text kept when TMDB has none", "Addon text", all.description)
        assertEquals("the addon's release line wins", "2008-2013", all.releaseInfo)

        val noEpisodesOrCredits = TmdbEnrich.apply(addonMeta, tmdbTitle(), episodes, TmdbSettings(enabled = true, useEpisodes = false, useCredits = false, useNetworks = false))
        assertEquals("Episode 1", noEpisodesOrCredits.videos.single().title)
        assertTrue(noEpisodesOrCredits.castMembers.isEmpty())
        assertTrue(noEpisodesOrCredits.networks.isEmpty())
    }

    @Test
    fun `a TMDB-only show gets episodes with tmdb ids`() {
        val meta = TmdbEnrich.metaFor(tmdbTitle(), episodes)
        assertEquals("tmdb:1396", meta.id)
        assertEquals("tmdb:1396:1:1", meta.videos.single().id)
        assertEquals(ContentType.SERIES, meta.type)
    }
}
