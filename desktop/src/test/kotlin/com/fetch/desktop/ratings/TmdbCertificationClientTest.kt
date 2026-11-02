package com.fetch.desktop.ratings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Properties

class TmdbCertificationClientTest {

    private val movieFind = """{"movie_results":[{"id":603,"title":"The Matrix"}],"tv_results":[]}"""
    private val tvFind = """{"movie_results":[],"tv_results":[{"id":1396}]}"""

    @Test
    fun `movie picks US release date over other countries`() = runBlocking {
        val http = RatingsFakeHttp.routes(
            "/find/tt0133093" to movieFind,
            "/movie/603/release_dates" to """{"id":603,"results":[
                {"iso_3166_1":"DE","release_dates":[{"certification":"16","type":3}]},
                {"iso_3166_1":"US","release_dates":[{"certification":"","type":1},{"certification":"R","type":3}]},
                {"iso_3166_1":"GB","release_dates":[{"certification":"15","type":3}]}]}""",
        )
        val client = TmdbCertificationClient(http.client, { "v3key" })

        assertEquals(Certification("R", "US"), client.certification("tt0133093", "movie"))
        val find = http.sent.first().url
        assertEquals("/3/find/tt0133093", find.encodedPath)
        assertEquals("imdb_id", find.queryParameter("external_source"))
        assertEquals("v3key", find.queryParameter("api_key"))
        assertNull(http.sent.first().header("Authorization"))
    }

    @Test
    fun `strictest rating within the chosen country wins, like Android`() = runBlocking {
        val http = RatingsFakeHttp.routes(
            "/find/tt1" to movieFind,
            "/movie/603/release_dates" to """{"results":[{"iso_3166_1":"US","release_dates":[
                {"certification":"PG-13","type":3},{"certification":"NR","type":4},{"certification":"R","type":5}]}]}""",
        )
        assertEquals(Certification("R", "US"), TmdbCertificationClient(http.client, { "k" }).certification("tt1", "movie"))
    }

    @Test
    fun `falls back through preferred countries then others, skipping unrated labels`() = runBlocking {
        val http = RatingsFakeHttp.routes(
            "/find/tt1" to movieFind,
            "/movie/603/release_dates" to """{"results":[
                {"iso_3166_1":"BR","release_dates":[{"certification":"14"}]},
                {"iso_3166_1":"US","release_dates":[{"certification":"NR"}]},
                {"iso_3166_1":"GB","release_dates":[{"certification":"12A"}]}]}""",
        )
        assertEquals(Certification("12A", "GB"), TmdbCertificationClient(http.client, { "k" }).certification("tt1", "movie"))

        assertEquals(Certification("14", "BR"), TmdbCertificationClient.pick(mapOf("BR" to listOf("14"), "US" to listOf(""))))
        assertNull(TmdbCertificationClient.pick(mapOf("US" to listOf("NR", ""))))
    }

    @Test
    fun `series uses tv results and content ratings`() = runBlocking {
        val http = RatingsFakeHttp.routes(
            "/find/tt0903747" to tvFind,
            "/tv/1396/content_ratings" to """{"results":[
                {"iso_3166_1":"DE","rating":"16"},{"iso_3166_1":"US","rating":"TV-MA"}]}""",
        )
        val client = TmdbCertificationClient(http.client, { "k" })
        assertEquals(Certification("TV-MA", "US"), client.certification("tt0903747:1:2", "series"))
        assertTrue(http.sent.any { it.url.encodedPath == "/3/tv/1396/content_ratings" })
    }

    @Test
    fun `movie lookup ignores tv results`() = runBlocking {
        val http = RatingsFakeHttp.routes("/find/tt1" to tvFind)
        assertNull(TmdbCertificationClient(http.client, { "k" }).certification("tt1", "movie"))
        assertEquals(1, http.sent.size)
    }

    @Test
    fun `no key returns null without any request`() = runBlocking {
        val http = RatingsFakeHttp { error("should not be called") }
        assertNull(TmdbCertificationClient(http.client, { null }).certification("tt1", "movie"))
        assertNull(TmdbCertificationClient(http.client, { "  " }).certification("tt1", "movie"))
        assertTrue(http.sent.isEmpty())
    }

    @Test
    fun `404 returns null`() = runBlocking {
        val http = RatingsFakeHttp { 404 to """{"success":false}""" }
        assertNull(TmdbCertificationClient(http.client, { "k" }).certification("tt1", "movie"))
    }

    @Test(expected = IOException::class)
    fun `server errors throw so they are not cached as unrated`() {
        runBlocking { TmdbCertificationClient(RatingsFakeHttp { 429 to "{}" }.client, { "k" }).certification("tt1", "movie") }
    }

    @Test
    fun `v4 read access token is sent as bearer`() = runBlocking {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2ln"
        val http = RatingsFakeHttp.routes("/find/tt1" to """{"movie_results":[]}""")
        TmdbCertificationClient(http.client, { token }).certification("tt1", "movie")
        val request = http.sent.single()
        assertEquals("Bearer $token", request.header("Authorization"))
        assertNull(request.url.queryParameter("api_key"))
    }

    @Test
    fun `credentials read properties then env, ignoring placeholders`() {
        val props = Properties()
        assertNull(TmdbCredentials.from(props) { null })
        props.setProperty("tmdbApiKey", "\${tmdbApiKey}")
        assertEquals("env", TmdbCredentials.from(props) { if (it == "TMDB_API_KEY") "env" else null })
        assertNull(TmdbCredentials.from(props) { " " })
        props.setProperty("tmdbApiKey", " prop ")
        assertEquals("prop", TmdbCredentials.from(props) { "env" })
    }
}
