package com.fetch.tv.ui.screens.home

import androidx.compose.ui.unit.dp
import com.fetch.tv.data.youtube.YouTubeFeedFilter
import com.fetch.tv.data.youtube.YouTubePage
import com.fetch.tv.data.youtube.YouTubeShelf
import com.fetch.tv.data.youtube.YouTubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeYouTubeRowTest {
    private fun video(
        id: String,
        isShort: Boolean = false,
        isLive: Boolean = false,
        watchedPercent: Int? = null
    ) = YouTubeVideo(
        videoId = id,
        title = "Video $id",
        channelName = "Channel",
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        duration = "10:00",
        watchedPercent = watchedPercent,
        isShort = isShort,
        isLive = isLive
    )

    private fun page(vararg shelves: YouTubeShelf) = YouTubePage(shelves.toList(), continuation = null)

    @Test
    fun `row shows only when every condition holds`() {
        assertTrue(youTubeHomeRowAllowed(youTubeInBuild = true, rowEnabled = true, signedIn = true, restrictedProfile = false))
        assertFalse(youTubeHomeRowAllowed(youTubeInBuild = false, rowEnabled = true, signedIn = true, restrictedProfile = false))
        assertFalse(youTubeHomeRowAllowed(youTubeInBuild = true, rowEnabled = false, signedIn = true, restrictedProfile = false))
        assertFalse(youTubeHomeRowAllowed(youTubeInBuild = true, rowEnabled = true, signedIn = false, restrictedProfile = false))
        assertFalse(youTubeHomeRowAllowed(youTubeInBuild = true, rowEnabled = true, signedIn = true, restrictedProfile = true))
    }

    @Test
    fun `videos are capped at the row limit across shelves`() {
        val first = YouTubeShelf(title = "Today", videos = (1..15).map { video("a$it") })
        val second = YouTubeShelf(title = "Yesterday", videos = (1..15).map { video("b$it") })

        val videos = youTubeHomeRowVideos(page(first, second), YouTubeFeedFilter())

        assertEquals(YOUTUBE_HOME_ROW_MAX_VIDEOS, videos.size)
        assertEquals("a1", videos.first().videoId)
        assertEquals("b5", videos.last().videoId)
    }

    @Test
    fun `cap counts videos left after the feed filter`() {
        val shelf = YouTubeShelf(
            title = null,
            videos = (1..30).map { video("v$it", isLive = it % 2 == 0) }
        )

        val videos = youTubeHomeRowVideos(page(shelf), YouTubeFeedFilter(showLive = false))

        assertEquals(15, videos.size)
        assertTrue(videos.none { it.isLive })
    }

    @Test
    fun `feed filter drops shorts shelves and watched videos`() {
        val shorts = YouTubeShelf(title = "Shorts", videos = listOf(video("s1"), video("s2")), isShorts = true)
        val regular = YouTubeShelf(
            title = null,
            videos = listOf(video("v1"), video("v2", watchedPercent = 95), video("v3", isShort = true))
        )

        val videos = youTubeHomeRowVideos(
            page(shorts, regular),
            YouTubeFeedFilter(showShorts = false, hideWatched = true)
        )

        assertEquals(listOf("v1"), videos.map { it.videoId })
    }

    @Test
    fun `a video in two shelves shows once`() {
        val shelf = YouTubeShelf(title = null, videos = listOf(video("v1"), video("v2")))
        val again = YouTubeShelf(title = "More", videos = listOf(video("v2"), video("v3")))

        val videos = youTubeHomeRowVideos(page(shelf, again), YouTubeFeedFilter())

        assertEquals(listOf("v1", "v2", "v3"), videos.map { it.videoId })
    }

    @Test
    fun `an empty feed gives no videos`() {
        assertTrue(youTubeHomeRowVideos(page(), YouTubeFeedFilter()).isEmpty())
    }

    @Test
    fun `cache is fresh only within its time`() {
        assertTrue(youTubeHomeRowCacheFresh(fetchedAtMs = 1_000, nowMs = 1_000))
        assertTrue(youTubeHomeRowCacheFresh(fetchedAtMs = 1_000, nowMs = 1_000 + YOUTUBE_HOME_ROW_CACHE_TTL_MS - 1))
        assertFalse(youTubeHomeRowCacheFresh(fetchedAtMs = 1_000, nowMs = 1_000 + YOUTUBE_HOME_ROW_CACHE_TTL_MS))
        // A clock that went backwards (a reboot) can't vouch for the cache.
        assertFalse(youTubeHomeRowCacheFresh(fetchedAtMs = 5_000, nowMs = 1_000))
    }

    @Test
    fun `video card carries the video and uses its thumbnail for the hero`() {
        val source = video("abc")

        val item = buildYouTubeVideoItem(source)

        val payload = item.payload as ModernPayload.YouTubeVideo
        assertEquals(source, payload.video)
        assertEquals(item.key, payload.focusKey)
        assertEquals("Video abc", item.heroPreview.title)
        assertEquals(source.thumbnailUrl, item.heroPreview.backdrop)
        assertEquals("Channel", item.heroPreview.contentTypeText)
        assertEquals("10:00", item.heroPreview.runtimeText)
    }

    @Test
    fun `video card stays landscape with portrait posters`() {
        val metrics = buildYouTubeVideoItem(video("abc")).catalogCardMetrics(
            useLandscapePosters = false,
            portraitCardWidth = 100.dp,
            portraitCardHeight = 150.dp,
            landscapeCardWidth = 240.dp,
            landscapeCardHeight = 135.dp
        )

        assertEquals(240.dp, metrics.width)
        assertEquals(135.dp, metrics.height)
    }
}
