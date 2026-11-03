package com.fetch.desktop.skip

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipMarkersTest {

    @Test
    fun `types map to IntroDB's as on TV`() {
        assertEquals("intro", SkipMarkerRules.submissionType(SegmentType.INTRO, isMovie = false))
        assertEquals(null, SkipMarkerRules.submissionType(SegmentType.INTRO, isMovie = true))
        assertEquals("outro", SkipMarkerRules.submissionType(SegmentType.MOVIE_CREDITS, isMovie = true))
        assertEquals("post-credits", SkipMarkerRules.submissionType(SegmentType.POST_CREDITS, isMovie = true))
        assertEquals(null, SkipMarkerRules.submissionType(SegmentType.POST_CREDITS, isMovie = false))
    }

    @Test
    fun `the first press records the start and the next one submits`() {
        assertEquals(SkipMarkerRules.Press.Start(10.0), SkipMarkerRules.press(null, 10.0))
        assertEquals(SkipMarkerRules.Press.Submit(10.0, 95.0), SkipMarkerRules.press(10.0, 95.0))
        // A press at or before the start (after seeking back) starts again.
        assertEquals(SkipMarkerRules.Press.Start(5.0), SkipMarkerRules.press(10.0, 5.0))
    }

    @Test
    fun `only AniSkip markers with an id can be reported`() {
        assertTrue(SkipMarkerRules.canReport(Segment(SegmentType.INTRO, 0.0, 90.0, provider = "aniskip", sourceId = "abc")))
        assertFalse(SkipMarkerRules.canReport(Segment(SegmentType.INTRO, 0.0, 90.0, provider = "aniskip")))
        assertFalse(SkipMarkerRules.canReport(Segment(SegmentType.INTRO, 0.0, 90.0, provider = "introdb", sourceId = "abc")))
    }

    private class Recorder(private val code: Int = 200) : Interceptor {
        var url: String? = null
        var body: String? = null
        var apiKey: String? = null
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            url = request.url.toString()
            apiKey = request.header("X-API-Key")
            body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body("{}".toResponseBody(null)).build()
        }
    }

    @Test
    fun `an episode marker is posted to IntroDB's submit endpoint with the key`() = runBlocking {
        val recorder = Recorder()
        val client = IntroDbClient(OkHttpClient.Builder().addInterceptor(recorder).build(), "https://introdb.example/")
        assertTrue(client.submit(" idb_key ", MarkerTarget("tt123", 1, 2), SegmentType.INTRO, 10.0, 95.5))
        assertEquals("https://introdb.example/submit", recorder.url)
        assertEquals("idb_key", recorder.apiKey)
        assertEquals("""{"segment_type":"intro","imdb_id":"tt123","season":1,"episode":2,"start_sec":10.0,"end_sec":95.5}""", recorder.body)
    }

    @Test
    fun `a movie marker says so, and a bad one isn't sent`() = runBlocking {
        val recorder = Recorder()
        val client = IntroDbClient(OkHttpClient.Builder().addInterceptor(recorder).build(), "https://introdb.example")
        assertTrue(client.submit("k", MarkerTarget("tt9", null, null), SegmentType.MOVIE_CREDITS, 5000.0, 5200.0))
        assertEquals("""{"segment_type":"outro","imdb_id":"tt9","is_movie":true,"start_sec":5000.0,"end_sec":5200.0}""", recorder.body)
        recorder.url = null
        assertFalse(client.submit("k", MarkerTarget("tt9", 1, 1), SegmentType.INTRO, 50.0, 40.0))
        assertFalse(client.submit("", MarkerTarget("tt9", 1, 1), SegmentType.INTRO, 10.0, 40.0))
        assertEquals(null, recorder.url)
    }

    @Test
    fun `a refused submit reports failure`() = runBlocking {
        val client = IntroDbClient(OkHttpClient.Builder().addInterceptor(Recorder(401)).build(), "https://introdb.example")
        assertFalse(client.submit("k", MarkerTarget("tt9", 1, 1), SegmentType.INTRO, 10.0, 40.0))
    }

    @Test
    fun `a wrong AniSkip marker is downvoted`() = runBlocking {
        val recorder = Recorder()
        val client = AniSkipClient(OkHttpClient.Builder().addInterceptor(recorder).build())
        assertTrue(client.reportWrong("skip-1"))
        assertEquals("https://api.aniskip.com/v2/skip-times/skip-1/votes", recorder.url)
        assertEquals("""{"voteType":"downvote"}""", recorder.body)
    }
}
