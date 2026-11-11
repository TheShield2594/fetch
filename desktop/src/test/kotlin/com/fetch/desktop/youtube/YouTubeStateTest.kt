package com.fetch.desktop.youtube

import com.fetch.desktop.PlayerSession
import com.fetch.desktop.YouTubeFeedKey
import com.fetch.desktop.YouTubeHost
import com.fetch.desktop.YouTubeState
import com.fetch.desktop.data.PlayerSettings
import com.fetch.tv.data.youtube.YouTubeFeed
import com.fetch.tv.data.youtube.YouTubeVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class YouTubeStateTest {

    private val dir = Files.createTempDirectory("youtube-state").toFile()

    private class FakeHost(var settings: YouTubeSettings = YouTubeSettings(), var limited: Boolean = false) : YouTubeHost {
        val messages = mutableListOf<String>()
        override val playerSettings = PlayerSettings()
        override val youTubeSettings: YouTubeSettings get() = settings
        override val restricted: Boolean get() = limited
        override fun message(text: String) { messages += text }
        override suspend fun chooseBuiltIn(): Boolean? = false
        override suspend fun openBuiltInPlayer(): PlayerSession? = null
        override val currentPlayer: PlayerSession? = null
        override fun closePlayer() = Unit
    }

    private val signedIn = YouTubeAuth(accessToken = "AT", refreshToken = "RT", accessTokenExpiresAt = Long.MAX_VALUE / 2, accountName = "Ada")

    private fun http(feedback: Boolean = true, deArrow: String? = null) = YouTubeFakeHttp { req ->
        when {
            req.path == "/tv" -> 404 to ""
            req.host == "sponsor.ajay.app" -> if (deArrow != null) 200 to deArrow else 404 to ""
            req.path == "/youtubei/v1/feedback" -> 200 to """{"feedbackResponses":[{"isProcessed":$feedback}]}"""
            req.path == "/youtubei/v1/search" -> 200 to subscriptionsJson("sssssssssss")
            req.path.startsWith("/youtubei/v1/") -> 200 to subscriptionsJson()
            else -> 404 to ""
        }
    }

    private fun state(scope: CoroutineScope, fake: YouTubeFakeHttp, host: FakeHost = FakeHost(), auth: YouTubeAuth = signedIn) =
        YouTubeState(scope, host, InMemoryYouTubeAuthStore(auth), youTubeDataFile(dir), http = fake.client)

    private suspend fun CoroutineScope.settle() = repeat(3) { coroutineContext.job.children.forEach { it.join() } }

    private fun YouTubeState.videos(key: YouTubeFeedKey) = pages[key]?.shelves.orEmpty().flatMap { it.videos }

    @Test
    fun `a feed loads through the profile's filter`() = runBlocking {
        val host = FakeHost(YouTubeSettings(showShorts = false, showLive = false))
        val yt = state(this, http(), host)
        yt.selectFeed(YouTubeFeed.SUBSCRIPTIONS)
        settle()
        val key = YouTubeFeedKey.Tab(YouTubeFeed.SUBSCRIPTIONS)
        assertEquals(listOf("aaaaaaaaaaa"), yt.videos(key).map { it.videoId })
        assertTrue(yt.pages[key]!!.loaded)
        assertEquals("NEXT_PAGE", yt.pages[key]!!.continuation)
    }

    @Test
    fun `DeArrow's titles replace YouTube's once they arrive, when it's on`() = runBlocking {
        val answer = """{"aaaaaaaaaaa":{"titles":[{"title":"Plain title","original":false,"votes":1,"locked":false}],"thumbnails":[]}}"""
        val yt = state(this, http(deArrow = answer), FakeHost(YouTubeSettings(deArrowEnabled = true)))
        yt.selectFeed(YouTubeFeed.SUBSCRIPTIONS)
        settle()
        assertEquals("Plain title", yt.videos(YouTubeFeedKey.Tab(YouTubeFeed.SUBSCRIPTIONS)).first().title)

        val off = state(this, http(deArrow = answer))
        off.selectFeed(YouTubeFeed.SUBSCRIPTIONS)
        settle()
        assertEquals("Video 0", off.videos(YouTubeFeedKey.Tab(YouTubeFeed.SUBSCRIPTIONS)).first().title)
    }

    @Test
    fun `a search is remembered and shows its results`() = runBlocking {
        val fake = http()
        val yt = state(this, fake)
        yt.onSearchQueryChange("rockets")
        yt.search()
        settle()
        assertEquals(YouTubeFeed.SEARCH, yt.selectedFeed)
        assertEquals(listOf("sssssssssss"), yt.videos(YouTubeFeedKey.Tab(YouTubeFeed.SEARCH)).map { it.videoId })
        assertEquals(listOf("rockets"), yt.recentSearches)
        assertEquals(listOf("rockets"), youTubeDataFile(dir).load().recentSearches)
        val body = Json.parseToJsonElement(fake.innertube("search").single().body).jsonObject
        assertEquals("rockets", body["query"]!!.jsonPrimitive.content)
        yt.clearRecentSearches()
        assertTrue(youTubeDataFile(dir).load().recentSearches.isEmpty())
    }

    @Test
    fun `not interested takes the video out once YouTube accepts`() = runBlocking {
        val yt = state(this, http())
        yt.selectFeed(YouTubeFeed.HOME)
        settle()
        val key = YouTubeFeedKey.Tab(YouTubeFeed.HOME)
        val video = yt.videos(key).first()
        yt.markNotInterested(video)
        settle()
        assertFalse(yt.videos(key).any { it.videoId == video.videoId })

        val refused = state(this, http(feedback = false))
        refused.selectFeed(YouTubeFeed.HOME)
        settle()
        refused.markNotInterested(refused.videos(key).first())
        settle()
        assertEquals(3, refused.videos(key).size)
    }

    @Test
    fun `the Home row follows the TV's rules and is fetched at most every ten minutes`() = runBlocking {
        val fake = http()
        val host = FakeHost(YouTubeSettings(homeRowEnabled = true), limited = true)
        val yt = state(this, fake, host)
        yt.onHomeVisible()
        settle()
        assertFalse("restricted profiles get no row", yt.homeRowShown)
        assertTrue(fake.sent.none { it.path.startsWith("/youtubei") })
        assertFalse("nor YouTube in the sidebar", yt.available)

        host.limited = false
        yt.onHomeVisible()
        settle()
        assertTrue(yt.homeRowShown)
        assertEquals(3, yt.homeRow.size)
        yt.onHomeVisible()
        settle()
        assertEquals("cached", 1, fake.innertube("browse").size)

        host.settings = host.settings.copy(homeRowEnabled = false)
        assertFalse(yt.homeRowShown)
    }

    @Test
    fun `a sign-in YouTube stopped accepting shows as signed out`() = runBlocking {
        val fake = YouTubeFakeHttp { req ->
            when (req.path) {
                "/tv" -> 404 to ""
                "/o/oauth2/token" -> 400 to """{"error":"invalid_grant"}"""
                else -> 200 to "{}"
            }
        }
        val yt = state(this, fake, auth = signedIn.copy(accessTokenExpiresAt = 0))
        assertTrue(yt.available)
        yt.selectFeed(YouTubeFeed.HOME)
        settle()
        assertTrue(yt.signedOut)
        assertFalse(yt.signedIn)
        assertFalse(yt.available)
    }

    @Test
    fun `a restricted profile can't play YouTube`() = runBlocking {
        val host = FakeHost(limited = true)
        val yt = state(this, http(), host)
        yt.play(YouTubeVideo("aaaaaaaaaaa", "A", thumbnailUrl = "x"))
        settle()
        assertEquals(listOf("YouTube isn't available on this profile"), host.messages)
    }
}
