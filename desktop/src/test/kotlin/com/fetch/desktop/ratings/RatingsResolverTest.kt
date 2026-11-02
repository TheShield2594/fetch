package com.fetch.desktop.ratings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class RatingsResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    /** tt1 → movie 1 rated PG; tt2 → movie 2 with no rating; anything else 404. */
    private fun tmdb() = RatingsFakeHttp { request ->
        when (request.url.encodedPath) {
            "/3/find/tt1" -> 200 to """{"movie_results":[{"id":1}]}"""
            "/3/find/tt2" -> 200 to """{"movie_results":[{"id":2}]}"""
            "/3/movie/1/release_dates" -> 200 to """{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG"}]}]}"""
            "/3/movie/2/release_dates" -> 200 to """{"results":[{"iso_3166_1":"US","release_dates":[{"certification":""}]}]}"""
            else -> 404 to "{}"
        }
    }

    private fun resolver(http: RatingsFakeHttp, cache: CertificationCache, key: String? = "k") =
        RatingsResolver(scope, TmdbCertificationClient(http.client, { key }), cache, batchDelayMs = 1)

    @Test
    fun `dedups requests and caches results`() = runBlocking {
        val http = tmdb()
        val file = tmp.root.resolve("ratings.json")
        val resolver = resolver(http, CertificationCache(file))

        resolver.request(listOf("tt1" to "movie", "tt1" to "movie", "tt2" to "movie"))
        resolver.request(listOf("tt1" to "movie"))
        resolver.awaitIdle()

        assertEquals(Certification("PG", "US"), resolver.cached("tt1"))
        assertEquals(Certification("PG", "US"), resolver.ratings["tt1"])
        assertTrue(resolver.isKnownUnrated("tt2"))
        assertFalse(resolver.isKnownUnrated("tt1"))
        assertFalse(resolver.isKnownUnrated("tt3"))
        assertEquals(4, http.sent.size) // find + release_dates, once per title

        resolver.request(listOf("tt1" to "movie", "tt2" to "movie"))
        resolver.awaitIdle()
        assertEquals(4, http.sent.size)

        // A fresh resolver (next launch) answers from the saved cache with no HTTP calls.
        val nextHttp = tmdb()
        val next = resolver(nextHttp, CertificationCache(file))
        next.request(listOf("tt1" to "movie", "tt2" to "movie"))
        next.awaitIdle()
        assertEquals(Certification("PG", "US"), next.cached("tt1"))
        assertTrue(next.isKnownUnrated("tt2"))
        assertTrue(nextHttp.sent.isEmpty())
    }

    @Test
    fun `runs at most the configured number of lookups at once`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val http = RatingsFakeHttp { request ->
            val now = active.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            Thread.sleep(20)
            active.decrementAndGet()
            if (request.url.encodedPath.startsWith("/3/find/")) 200 to """{"movie_results":[]}""" else 404 to "{}"
        }
        val resolver = resolver(http, CertificationCache(tmp.root.resolve("r.json")))
        resolver.request((1..10).map { "tt$it" to "movie" })
        resolver.awaitIdle()
        assertEquals(10, http.sent.size)
        assertTrue("peak ${peak.get()}", peak.get() <= 4)
        assertTrue(resolver.isKnownUnrated("tt10"))
    }

    @Test
    fun `no key makes no requests and caches nothing`() = runBlocking {
        val http = tmdb()
        val cache = CertificationCache(tmp.root.resolve("r.json"))
        val resolver = resolver(http, cache, key = null)
        resolver.request(listOf("tt1" to "movie"))
        resolver.awaitIdle()
        assertTrue(http.sent.isEmpty())
        assertNull(resolver.ratings["tt1"])
        assertFalse(resolver.isKnownUnrated("tt1"))
        assertNull(cache.get("movie", "tt1"))
    }

    @Test
    fun `failed lookups are not cached and wait before retrying`() = runBlocking {
        val http = RatingsFakeHttp { 500 to "{}" }
        val cache = CertificationCache(tmp.root.resolve("r.json"))
        val resolver = resolver(http, cache)
        resolver.request(listOf("tt1" to "movie"))
        resolver.awaitIdle()
        assertFalse(resolver.isKnownUnrated("tt1"))
        assertNull(cache.get("movie", "tt1"))
        resolver.request(listOf("tt1" to "movie"))
        resolver.awaitIdle()
        assertEquals(1, http.sent.size)
    }
}
