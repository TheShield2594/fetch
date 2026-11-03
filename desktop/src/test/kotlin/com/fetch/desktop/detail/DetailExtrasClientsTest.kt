package com.fetch.desktop.detail

import com.fetch.desktop.ratings.RatingsFakeHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailExtrasClientsTest {
    private val seasons = """[
        {"episodes":[{"season_number":1,"episode_number":1,"vote_average":8.4,"tconst":"tt2"},{"season_number":1,"episode_number":2,"vote_average":9.1}]},
        {"episodes":[{"season_number":2,"episode_number":1,"vote_average":null}]}
    ]"""

    @Test
    fun `ratings come from the IMDb service by IMDb id`() = runBlocking {
        val http = RatingsFakeHttp.routes("/api/shows/tt1/season-ratings" to seasons)
        val client = EpisodeRatingsClient(http.client, "https://imdb.example/", "https://graph.example")
        assertEquals(mapOf((1 to 1) to 8.4, (1 to 2) to 9.1), client.ratings("tt1:1:1", 1399))
        // Cached: no second request.
        client.ratings("tt1", null)
        assertEquals(1, http.sent.size)
        assertEquals("imdb.example", http.sent.first().url.host)
    }

    @Test
    fun `SeriesGraph by TMDB id fills in when the IMDb service knows nothing`() = runBlocking {
        val http = RatingsFakeHttp.routes("/api/shows/1399/season-ratings" to seasons)
        val client = EpisodeRatingsClient(http.client, "https://imdb.example", "https://graph.example")
        assertEquals(2, client.ratings("tt1", 1399).size)
        assertEquals(listOf("imdb.example", "graph.example"), http.sent.map { it.url.host })
    }

    @Test
    fun `no services, no grid`() = runBlocking {
        val client = EpisodeRatingsClient(OkHttpClient(), null, null)
        assertFalse(client.available)
        assertTrue(client.ratings("tt1", 1).isEmpty())
    }

    @Test
    fun `the grid colours follow the TV's`() {
        assertEquals(0xFF1DA1F3, episodeRatingColor(9.8))
        assertEquals(0xFF28B463, episodeRatingColor(8.4))
        assertEquals(0xFF633974, episodeRatingColor(4.0))
        assertEquals(0xFFFFFFFF, episodeRatingTextColor(9.2))
    }

    private fun comments(code: Int = 200, body: String, pages: Int = 2, total: Int = 101) = object : Interceptor {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<String?>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            urls += r.url.toString()
            headers += r.header("trakt-api-key")
            return Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("")
                .header("X-Pagination-Page-Count", "$pages").header("X-Pagination-Item-Count", "$total")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    @Test
    fun `episode comments are asked by likes with the client id, spoilers marked`() = runBlocking {
        val fake = comments(body = """[
            {"id":7,"comment":"Great [spoiler]he dies[/spoiler] episode","spoiler":false,"review":true,"likes":12,"user_stats":{"rating":9},"user":{"username":"neo","name":""},"created_at":"2024-05-01T10:00:00.000Z"},
            {"id":8,"comment":"   ","user":{"username":"x"}}
        ]""")
        val client = TraktCommentsClient(OkHttpClient.Builder().addInterceptor(fake).build(), { "abc" })
        val target = CommentsTarget.of("tt9:2:5", null, isSeries = true, season = 2, episode = 5)!!
        val page = client.page(target)
        assertEquals("https://api.trakt.tv/shows/tt9/seasons/2/episodes/5/comments/likes?page=1&limit=100", fake.urls.single())
        assertEquals("abc", fake.headers.single())
        val c = page.items.single()
        assertEquals("neo", c.authorDisplayName)
        assertEquals("Great he dies episode", c.comment)
        assertTrue(c.containsInlineSpoilers && c.hasSpoilerContent && c.review)
        assertEquals(9, c.rating)
        assertTrue(page.hasMore)
        assertEquals(101, page.itemCount)
    }

    @Test
    fun `a title Trakt doesn't have has no comments`() = runBlocking {
        val client = TraktCommentsClient(OkHttpClient.Builder().addInterceptor(comments(404, "{}")).build(), { "abc" })
        assertTrue(client.page(CommentsTarget.of("tt1", null, isSeries = false)!!).items.isEmpty())
        assertEquals(CommentsTarget.Kind.SHOW, CommentsTarget.of("x", "tt5", isSeries = true)!!.kind)
        assertEquals(null, CommentsTarget.of("tmdb:5", null, isSeries = false))
    }
}
