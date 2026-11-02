package com.fetch.desktop.skip

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentsTest {

    private fun client(body: String, code: Int = 200, seen: MutableList<String> = mutableListOf()) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            seen += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()

    // A real IntroDB answer for Breaking Bad S1E2.
    private val sample = """{"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":2,
        "intro":{"start_sec":314.5,"end_sec":331,"start_ms":314500,"end_ms":331000,"confidence":1},
        "recap":null,"outro":{"start_sec":2843,"end_sec":2901},"post_credits":null,"no_intro":false}"""

    @Test
    fun `reads intro and credits for an episode`() = runBlocking {
        val seen = mutableListOf<String>()
        val segments = IntroDbClient(client(sample, seen = seen)).segments("tt0903747", 1, 2)
        assertEquals(listOf(Segment(SegmentType.INTRO, 314.5, 331.0), Segment(SegmentType.OUTRO, 2843.0, 2901.0)), segments)
        assertTrue(seen.single().endsWith("/segments?imdb_id=tt0903747&season=1&episode=2"))
    }

    @Test
    fun `a movie is asked for as a movie, and errors mean no segments`() = runBlocking {
        val seen = mutableListOf<String>()
        assertTrue(IntroDbClient(client("{}", 404, seen)).segments("tt1", null, null).isEmpty())
        assertTrue(seen.single().endsWith("imdb_id=tt1&is_movie=true"))
    }

    @Test
    fun `skips each enabled segment once, from inside it`() {
        val planner = SkipPlanner(
            listOf(Segment(SegmentType.INTRO, 300.0, 330.0), Segment(SegmentType.OUTRO, 2800.0, 2900.0)),
            enabled = setOf(SegmentType.INTRO)
        )
        assertNull(planner.at(299.0))
        assertEquals(SegmentType.INTRO, planner.at(301.0)?.type)
        assertNull("only once", planner.at(305.0))
        assertNull("credits aren't enabled", planner.at(2810.0))
        assertTrue(planner.isEmpty)
    }
}
