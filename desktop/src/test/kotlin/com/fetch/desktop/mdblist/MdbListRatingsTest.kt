package com.fetch.desktop.mdblist

import com.fetch.tv.domain.model.MDBListRatings
import com.fetch.tv.domain.model.RottenTomatoesStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun media(imdb: String, vararg ratings: String, keywords: String = "[]") =
    """{"title":"T","ids":{"imdb":"$imdb","tmdb":1},"ratings":[${ratings.joinToString(",")}],"keywords":$keywords}"""

class MdbListRatingsMappingTest {
    private fun ratingsOf(json: String) = mdbListRatingsOf(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `maps each source on its own scale, as the TV does`() {
        val r = ratingsOf(
            media(
                "tt1",
                """{"source":"imdb","value":8.1}""",
                """{"source":"trakt","value":84}""",
                """{"source":"tmdb","value":79}""",
                """{"source":"letterboxd","value":4.1,"score":82}""",
                """{"source":"tomatoes","value":93}""",
                """{"source":"popcorn","value":88}""",
                """{"source":"metacritic","value":71}""",
                """{"source":"myanimelist","value":8.5}""",
                """{"source":"rogerebert","value":3.5}"""
            )
        )
        assertEquals(8.1, r.imdb!!, 0.0)
        assertEquals(84.0, r.trakt!!, 0.0)
        assertEquals(79.0, r.tmdb!!, 0.0)
        assertEquals("Letterboxd's score out of 100 becomes out of 5", 4.1, r.letterboxd!!, 1e-9)
        assertEquals(93.0, r.tomatoes!!, 0.0)
        assertEquals("popcorn is the audience score", 88.0, r.audience!!, 0.0)
        assertEquals(71.0, r.metacritic!!, 0.0)
        assertEquals(8.5, r.mal!!, 0.0)
        assertFalse(r.tomatoesCertified)
    }

    @Test
    fun `drops missing, negative and out-of-range values and keeps the first of a source`() {
        val r = ratingsOf(
            media(
                "tt1",
                """{"source":"imdb","value":null}""",
                """{"source":"trakt","value":-1}""",
                """{"source":"metacritic","value":140}""",
                """{"source":"tomatoes","value":40}""",
                """{"source":"tomatoes","value":90}"""
            )
        )
        assertNull(r.imdb)
        assertNull(r.trakt)
        assertNull(r.metacritic)
        assertEquals(40.0, r.tomatoes!!, 0.0)
        assertEquals(RottenTomatoesStatus.ROTTEN, r.tomatoesStatus)
    }

    @Test
    fun `certified fresh and verified hot come from keywords`() {
        val r = ratingsOf(
            media(
                "tt1",
                """{"source":"tomatoes","value":95}""",
                """{"source":"audience","value":91}""",
                keywords = """[{"name":"rt.certified-fresh"},"certified-hot"]"""
            )
        )
        assertEquals(RottenTomatoesStatus.CERTIFIED_FRESH, r.tomatoesStatus)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, r.audienceStatus)
    }

    @Test
    fun `settings hide providers and order the badges`() {
        val s = MdbListSettings(showTrakt = false, ratingOrder = listOf("tomatoes", "imdb"))
        assertEquals(listOf("tomatoes", "imdb", "tmdb", "audience", "letterboxd", "metacritic", "mal"), s.enabledOrder())
        val filtered = s.filter(MDBListRatings(trakt = 80.0, imdb = 7.0))
        assertNull(filtered.trakt)
        assertEquals(7.0, filtered.imdb!!, 0.0)
        assertEquals(listOf("imdb", "tomatoes"), s.moved("imdb", -1).ratingOrder.take(2))
        assertFalse(s.withShown("imdb", false).shows("imdb"))
        assertEquals("92%", formatMdbListRating("tomatoes", 92.0))
        assertEquals("8.0", formatMdbListRating("imdb", 8.0))
        assertEquals("71", formatMdbListRating("metacritic", 71.0))
    }
}

class MdbListRatingsClientTest {
    @Test
    fun `one title is a GET with the key and the keyword extra`() = runBlocking {
        val fake = MdbListFakeHttp.routes("/imdb/show/tt9" to media("tt9", """{"source":"imdb","value":7.5}"""))
        val ratings = MdbListRatingsClient(fake.http()).ratings("series", listOf("tt9"), MdbListAuth.ApiKey("k"))
        assertEquals(7.5, ratings["tt9"]!!.imdb!!, 0.0)
        val sent = fake.sent.single()
        assertEquals("GET", sent.method)
        assertEquals("k", sent.query("apikey"))
        assertEquals("keyword", sent.query("append_to_response"))
        assertEquals("Fetch/test", sent.request.header("User-Agent"))
    }

    @Test
    fun `several titles are one POST, matched back by IMDb id`() = runBlocking {
        val fake = MdbListFakeHttp.routes(
            "POST /imdb/movie/" to "[${media("tt2", """{"source":"imdb","value":6.0}""")},{\"imdb_id\":\"tt1\",\"ratings\":[{\"source\":\"tmdb\",\"value\":70}]}]"
        )
        val ratings = MdbListRatingsClient(fake.http()).ratings("movie", listOf("tt1", "tt2", "tt3"), MdbListAuth.Bearer("tok"))
        assertEquals(setOf("tt1", "tt2"), ratings.keys)
        assertEquals(70.0, ratings["tt1"]!!.tmdb!!, 0.0)
        val sent = fake.sent.single()
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals(listOf("tt1", "tt2", "tt3"), body["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Bearer tok", sent.request.header("Authorization"))
        assertNull("a token is not sent as a key", sent.query("apikey"))
    }

    @Test
    fun `a rejected key fails verification`() = runBlocking {
        val fake = MdbListFakeHttp { _, _ -> FakeReply(401, """{"error":"invalid_api_key"}""") }
        assertFalse(MdbListRatingsClient(fake.http()).verify("bad"))
        val ok = MdbListFakeHttp.routes("/user" to """{"user_id":5}""")
        assertTrue(MdbListRatingsClient(ok.http()).verify(" good "))
        assertEquals("good", ok.sent.single().query("apikey"))
    }
}

class MdbListHttpTest {
    @Test
    fun `a 429 blocks further requests until the limit resets`() = runBlocking {
        var now = 1_000_000L
        val fake = MdbListFakeHttp { _, _ -> FakeReply(429, "{}", mapOf("Retry-After" to "30")) }
        val http = fake.http { now }
        try {
            http.get("/user", MdbListAuth.ApiKey("k")); fail()
        } catch (e: MdbListException) {
            assertEquals(429, e.status)
            assertEquals(now + 30_000, e.retryAtEpochMs)
        }
        try {
            http.get("/user", MdbListAuth.ApiKey("k")); fail()
        } catch (e: MdbListException) {
            assertEquals(429, e.status)
        }
        assertEquals("the second request never left", 1, fake.sent.size)
        now += 31_000
        runCatching { http.get("/user", MdbListAuth.ApiKey("k")) }
        assertEquals(2, fake.sent.size)
    }

    @Test
    fun `no requests remaining blocks the next one until the reset time`() = runBlocking {
        val now = 1_000_000L
        val fake = MdbListFakeHttp { _, _ -> FakeReply(200, "{}", mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "2000")) }
        val http = fake.http { now }
        http.get("/user", null)
        assertEquals(2_000_000L, http.rateLimitedUntil)
        assertTrue(runCatching { http.get("/user", null) }.exceptionOrNull() is MdbListException)
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun `other errors carry the status and MDBList's error code`() = runBlocking {
        val fake = MdbListFakeHttp { _, _ -> FakeReply(400, """{"error":"bad_request"}""") }
        val error = runCatching { fake.http().get("/lists/user", null) }.exceptionOrNull() as MdbListException
        assertEquals(400, error.status)
        assertEquals("bad_request", error.code)
    }
}

class MdbListRatingsCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `keeps ratings across launches for twelve hours`() {
        var now = 0L
        val file = tmp.root.resolve("mdblist-ratings.json")
        val cache = MdbListRatingsCache(file) { now }
        val key = MdbListRatingsCache.key("series", "tt1")
        assertEquals("imdb:show:tt1", key)
        cache.put(key, MDBListRatings(imdb = 7.0, tomatoes = 80.0, tomatoesCertified = true))
        cache.put("imdb:movie:tt2", MDBListRatings())
        cache.save()

        val reloaded = MdbListRatingsCache(file) { now }
        assertEquals(MDBListRatings(imdb = 7.0, tomatoes = 80.0, tomatoesCertified = true), reloaded.get(key))
        assertNull("titles without ratings aren't stored", reloaded.get("imdb:movie:tt2"))
        now = MdbListRatingsCache.DEFAULT_TTL_MS
        assertNull(reloaded.get(key))
    }

    @Test
    fun `an unreadable file reads as empty`() {
        val file = tmp.root.resolve("bad.json").apply { writeText("not json") }
        assertNull(MdbListRatingsCache(file).get("imdb:movie:tt1"))
    }
}

class MdbListRatingsStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun state(fake: MdbListFakeHttp, settings: MdbListSettings, cache: MdbListRatingsCache = MdbListRatingsCache(tmp.root.resolve("r.json"))) =
        MdbListRatingsState(scope, MdbListRatingsClient(fake.http()), cache, { settings }, batchWindowMs = 30)

    @Test
    fun `requests close together go out as one batch, and are cached`() = runBlocking {
        val fake = MdbListFakeHttp.routes(
            "POST /imdb/movie/" to "[${media("tt1", """{"source":"imdb","value":7.0}""")},${media("tt2", """{"source":"imdb","value":5.0}""")}]",
            "GET /imdb/show/tt3" to media("tt3", """{"source":"trakt","value":90}""")
        )
        val cache = MdbListRatingsCache(tmp.root.resolve("r.json"))
        val s = state(fake, MdbListSettings(enabled = true, apiKey = "k", showTrakt = false), cache)
        s.request(listOf("tt1" to "movie", "tt2" to "movie"))
        s.request(listOf("tt3" to "series", "tt1" to "movie", "tt4" to "movie"))
        s.awaitIdle()

        assertEquals(listOf("POST /imdb/movie/", "GET /imdb/show/tt3"), fake.sent.map { "${it.method} ${it.path}" }.sorted().reversed())
        assertEquals(3, Json.parseToJsonElement(fake.sent.first { it.method == "POST" }.body).jsonObject["ids"]!!.jsonArray.size)
        assertEquals(7.0, s.forTitle("tt1", "movie")!!.imdb!!, 0.0)
        assertNull("trakt is hidden, so tt3 has nothing to show", s.forTitle("tt3:1:2", "series"))
        assertNull("MDBList has nothing for tt4", s.forTitle("tt4", "movie"))
        assertEquals(7.0, cache.get("imdb:movie:tt1")!!.imdb!!, 0.0)

        // Known now, so nothing more is asked.
        s.request(listOf("tt1" to "movie", "tt4" to "movie"))
        s.awaitIdle()
        assertEquals(2, fake.sent.size)

        // A new launch answers from the saved cache.
        val fresh = MdbListFakeHttp { _, _ -> FakeReply(500, "{}") }
        val again = state(fresh, MdbListSettings(enabled = true, apiKey = "k"), MdbListRatingsCache(tmp.root.resolve("r.json")))
        again.request(listOf("tt1" to "movie"))
        again.awaitIdle()
        assertTrue(fresh.sent.isEmpty())
        assertEquals(7.0, again.forTitle("tt1", "movie")!!.imdb!!, 0.0)
    }

    @Test
    fun `a failed lookup isn't retried straight away`() = runBlocking {
        val fake = MdbListFakeHttp { _, _ -> FakeReply(503, "{}") }
        val s = state(fake, MdbListSettings(enabled = true, apiKey = "k"))
        s.request(listOf("tt1" to "movie"))
        s.awaitIdle()
        s.request(listOf("tt1" to "movie"))
        s.awaitIdle()
        assertEquals(1, fake.sent.size)
        assertNull(s.forTitle("tt1", "movie"))
    }

    @Test
    fun `off, or with no key or account, nothing is asked`() = runBlocking {
        val fake = MdbListFakeHttp { _, _ -> FakeReply(200, "{}") }
        assertFalse(state(fake, MdbListSettings(enabled = false, apiKey = "k")).active)
        assertFalse(state(fake, MdbListSettings(enabled = true)).active)
        val hero = state(fake, MdbListSettings(enabled = true, apiKey = "k", showOnHero = false))
        assertNull(hero.forHero("tt1", "movie"))
        hero.prefetch(listOf("tt1" to "movie"))
        hero.awaitIdle()
        assertTrue(fake.sent.isEmpty())
        assertEquals("tt42", MdbListRatingsState.imdbIdOf("imdb:tt42:1:3"))
        assertNull(MdbListRatingsState.imdbIdOf("tmdb:5"))
    }
}
