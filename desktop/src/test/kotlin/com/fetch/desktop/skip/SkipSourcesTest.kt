package com.fetch.desktop.skip

import com.fetch.desktop.ratings.RatingsFakeHttp
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSourcesTest {

    @Test
    fun `AniSkip results become intros and credits with their ids`() = runBlocking {
        val http = RatingsFakeHttp.routes(
            "/skip-times/21/5" to """{"found":true,"results":[
                {"interval":{"startTime":80.5,"endTime":170.0},"skipType":"op","skipId":"a1","episodeLength":1420},
                {"interval":{"startTime":1300,"endTime":1390},"skipType":"ed","skipId":"a2"},
                {"interval":{"startTime":5,"endTime":2},"skipType":"recap","skipId":"bad"}]}"""
        )
        val segments = AniSkipClient(http.client).segments("21", 5)
        assertEquals(listOf(SegmentType.INTRO, SegmentType.OUTRO), segments.map { it.type })
        assertEquals("a1", segments[0].sourceId)
        assertEquals(AniSkipClient.PROVIDER, segments[0].provider)
        val url = http.sent.single().url
        assertEquals(listOf("op", "ed", "recap", "mixed-op", "mixed-ed"), url.queryParameterValues("types[]"))
        assertTrue(AniSkipClient(RatingsFakeHttp.routes().client).segments("21", 5).isEmpty())
    }

    @Test
    fun `Anime-Skip marks run to the next mark, the last to the end`() {
        val segments = AnimeSkipClient.timestampsToSegments(
            listOf(0.0 to "Canon", 90.0 to "Intro", 180.0 to "Canon", 1300.0 to "Credits", 30.0 to "Recap")
        )
        assertEquals(
            listOf(
                Segment(SegmentType.RECAP, 30.0, 90.0, provider = "animeskip"),
                Segment(SegmentType.INTRO, 90.0, 180.0, provider = "animeskip"),
                Segment(SegmentType.OUTRO, 1300.0, Segment.OPEN_END, provider = "animeskip")
            ),
            segments
        )
        assertEquals("an open end is capped so mpv just stops", 10_000_000.0, segments.last().targetSec, 0.0)
    }

    @Test
    fun `Anime-Skip asks GraphQL with the client id and finds the episode`() = runBlocking {
        val bodies = mutableListOf<String>()
        val http = RatingsFakeHttp { request ->
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            val body = buffer.readUtf8()
            bodies += body
            assertEquals("cid", request.header("X-Client-ID"))
            when {
                "findShowsByExternalId" in body -> 200 to """{"data":{"findShowsByExternalId":[{"id":"show-1"}]}}"""
                "findEpisodesByShowId" in body -> 200 to """{"data":{"findEpisodesByShowId":[
                    {"season":"1","number":"4","timestamps":[]},
                    {"season":"1","number":"5","timestamps":[{"at":60,"type":{"name":"Intro"}},{"at":150,"type":{"name":"Canon"}}]}]}}"""
                else -> 404 to "{}"
            }
        }
        val segments = AnimeSkipClient(http.client).segments("cid", "999", 5)
        assertEquals(listOf(Segment(SegmentType.INTRO, 60.0, 150.0, provider = "animeskip")), segments)
        assertTrue(bodies.first().contains("serviceId: \\\"999\\\""))
        // The show id is cached: another episode asks only for episodes.
        bodies.clear()
        val client = AnimeSkipClient(http.client)
        client.segments("cid", "999", 5)
        assertTrue(client.segments("cid", "999", 4).isEmpty())
        assertEquals(1, bodies.count { "findShowsByExternalId" in it })
    }

    @Test
    fun `merging takes each part from the first provider that has it`() {
        val introDb = listOf(Segment(SegmentType.INTRO, 10.0, 40.0))
        val animeSkip = listOf(Segment(SegmentType.INTRO, 11.0, 41.0, provider = "animeskip"), Segment(SegmentType.RECAP, 0.0, 9.0, provider = "animeskip"))
        val aniSkip = listOf(Segment(SegmentType.OUTRO, 1300.0, 1390.0, provider = "aniskip"), Segment(SegmentType.RECAP, 0.0, 8.0, provider = "aniskip"))
        val merged = mergeByPriority(introDb, animeSkip, aniSkip)
        assertEquals(listOf("introdb", "animeskip", "aniskip"), merged.map { it.provider })
        assertEquals(listOf(SegmentType.INTRO, SegmentType.RECAP, SegmentType.OUTRO), merged.map { it.type })
    }

    @Test
    fun `movie credits never skip the post-credits scene`() {
        val overlapping = IntroDbClient.movieSegments(Segment(SegmentType.MOVIE_CREDITS, 6000.0, 6400.0), Segment(SegmentType.POST_CREDITS, 6300.0, 6420.0))
        assertEquals(6300.0, overlapping[0].endSec, 0.0)
        val gap = IntroDbClient.movieSegments(Segment(SegmentType.MOVIE_CREDITS, 6000.0, 6200.0), Segment(SegmentType.POST_CREDITS, 6300.0, 6420.0))
        assertEquals("skipping lands on the scene", 6300.0, gap[0].targetSec, 0.0)
        assertFalse(gap[1].skipsAutomatically(setOf(SegmentType.POST_CREDITS)))
    }

    @Test
    fun `a movie reads credits and post-credits from IntroDB`() = runBlocking {
        val http = RatingsFakeHttp.routes(
            "/segments" to """{"outro":{"start_ms":6000000,"end_ms":6400000},"post_credits":{"start_sec":6300,"end_sec":6420}}"""
        )
        val segments = IntroDbClient(http.client).segments("tt1", null, null)
        assertEquals(listOf(SegmentType.MOVIE_CREDITS, SegmentType.POST_CREDITS), segments.map { it.type })
        assertEquals(6300.0, segments[0].endSec, 0.0)
    }

    @Test
    fun `Simkl redirects name the type and id`() {
        assertEquals("anime" to 39687L, SimklIdResolver.parseRedirectLocation("https://simkl.com/anime/39687/some-show?x=1"))
        assertEquals("tv" to 5L, SimklIdResolver.parseRedirectLocation("/tv/5"))
        assertNull(SimklIdResolver.parseRedirectLocation("https://simkl.com/search"))
    }

    @Test
    fun `an anime episode by IMDb id merges IntroDB with AniSkip through Simkl`() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val (code, body, location) = when {
                url.encodedPath.endsWith("/segments") -> Triple(200, """{"recap":{"start_sec":0,"end_sec":30}}""", null)
                url.encodedPath.endsWith("/redirect") -> Triple(302, "", "https://simkl.com/anime/77/x")
                url.encodedPath.endsWith("/anime/episodes/77") -> Triple(200, """[{"episode":3,"tvdb":{"season":1,"episode":3}}]""", null)
                url.encodedPath.endsWith("/anime/77") -> Triple(200, """{"ids":{"mal":"21","anilist":"21","imdb":"tt0388629"},"season":1}""", null)
                url.encodedPath.endsWith("/skip-times/21/3") -> Triple(200, """{"found":true,"results":[{"interval":{"startTime":90,"endTime":180},"skipType":"op","skipId":"s"}]}""", null)
                else -> Triple(404, "{}", null)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
                .apply { if (location != null) header("Location", location) }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val sources = SkipSources(
            IntroDbClient(http, "https://introdb.test/"),
            AniSkipClient(http, "https://aniskip.test/v2/"),
            AnimeSkipClient(http, "https://animeskip.test/"),
            SimklIdResolver(http, "client", baseUrl = "https://simkl.test"),
            animeSkipClientId = { null }
        )
        val segments = sources.segments("tt0388629", "tt0388629:1:3", 1, 3, isMovie = false)
        assertEquals(listOf("introdb" to SegmentType.RECAP, "aniskip" to SegmentType.INTRO), segments.map { it.provider to it.type })
        assertTrue("without a Simkl id nothing anime is asked", SkipSources(
            IntroDbClient(http, "https://introdb.test/"), AniSkipClient(http), AnimeSkipClient(http), SimklIdResolver(http, ""), { null }
        ).segments("kitsu:1", "kitsu:1:3", null, null, isMovie = false).isEmpty())
    }

    @Test
    fun `the Skip button says what the TV says`() {
        val intro = Segment(SegmentType.INTRO, 10.0, 40.0)
        val outro = Segment(SegmentType.OUTRO, 1300.0, 1390.0)
        val credits = Segment(SegmentType.MOVIE_CREDITS, 6000.0, 6200.0)
        assertEquals("Skip Intro", SkipButtonRules.label(intro, false))
        assertEquals("Skip Recap", SkipButtonRules.label(Segment(SegmentType.RECAP, 0.0, 5.0), false))
        assertEquals("Skip Ending", SkipButtonRules.label(outro, false))
        assertEquals("Skip Credits", SkipButtonRules.label(credits, false))
        assertEquals("Skip to Post-Credits", SkipButtonRules.label(credits, true))
        assertEquals("Skip sponsor", SkipButtonRules.label(intro.copy(label = "sponsor"), false))
    }

    @Test
    fun `post-credits scenes are IntroDB's or what's left after the credits`() {
        val outro = Segment(SegmentType.OUTRO, 1300.0, 1390.0)
        assertNull("five seconds or less is just the end", SkipButtonRules.followingPostCredits(outro, listOf(outro), 1394.0))
        val heuristic = SkipButtonRules.followingPostCredits(outro, listOf(outro), 1440.0)
        assertEquals(1390.0, heuristic!!.startSec, 0.0)
        val credits = Segment(SegmentType.MOVIE_CREDITS, 6000.0, 6200.0)
        val scene = Segment(SegmentType.POST_CREDITS, 6300.0, 6400.0)
        assertEquals(6300.0, SkipButtonRules.target(credits, listOf(credits, scene), 6500.0), 0.0)
        assertNull(SkipButtonRules.followingPostCredits(Segment(SegmentType.INTRO, 1.0, 2.0), emptyList(), 100.0))
        assertNull("never a button for the scene itself", SkipButtonRules.active(listOf(scene), 6350.0))
    }

    @Test
    fun `the Skip button stays for the segment, between 10 seconds and 2 minutes`() {
        assertEquals(10_000, SkipButtonRules.autoHideMs(Segment(SegmentType.INTRO, 0.0, 4.0)))
        assertEquals(90_000, SkipButtonRules.autoHideMs(Segment(SegmentType.INTRO, 0.0, 90.0)))
        assertEquals(120_000, SkipButtonRules.autoHideMs(Segment(SegmentType.OUTRO, 0.0, 400.0)))
        assertEquals(10_000, SkipButtonRules.autoHideMs(Segment(SegmentType.OUTRO, 5.0, Segment.OPEN_END)))
        assertTrue(SkipButtonRules.visible(hasActive = true, dismissed = false, controlsVisible = false, autoHidden = false))
        assertFalse(SkipButtonRules.visible(hasActive = true, dismissed = false, controlsVisible = false, autoHidden = true))
        assertTrue("the controls bring it back", SkipButtonRules.visible(hasActive = true, dismissed = true, controlsVisible = true, autoHidden = true))
        assertFalse(SkipButtonRules.visible(hasActive = false, dismissed = false, controlsVisible = true, autoHidden = false))
    }

    @Test
    fun `auto-skipping credits lands on the post-credits scene`() {
        val credits = Segment(SegmentType.MOVIE_CREDITS, 6000.0, 6200.0, skipToSec = 6300.0)
        val planner = SkipPlanner(listOf(credits, Segment(SegmentType.POST_CREDITS, 6300.0, 6400.0)), setOf(SegmentType.MOVIE_CREDITS))
        assertEquals(6300.0, planner.at(6001.0)!!.targetSec, 0.0)
        assertNull("the scene itself is never skipped", planner.at(6301.0))
    }
}
