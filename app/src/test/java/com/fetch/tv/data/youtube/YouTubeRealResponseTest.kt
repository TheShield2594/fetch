package com.fetch.tv.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The parser against real YouTube TV responses, saved trimmed by scripts/youtube_fixtures.py
 * under src/test/resources/youtube. YouTube reshapes these often (shelf titles moved into an
 * avatar lockup, Shorts into reel endpoints, search into lockups), and hand-written JSON
 * keeps the old shape.
 *
 * With YOUTUBE_FIXTURES_DIR set, as the scheduled YouTube responses workflow does, the shape
 * checks run against freshly fetched responses instead, and the checks pinned to the saved
 * responses' content are skipped.
 */
class YouTubeRealResponseTest {

    private val liveDir: File? = System.getenv("YOUTUBE_FIXTURES_DIR")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun load(name: String): JsonElement {
        val text = liveDir?.let { File(it, "$name.json").readText() }
            ?: javaClass.getResourceAsStream("/youtube/$name.json")!!.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text)
    }

    private fun page(name: String) = YouTubeResponseParser.parsePage(load(name))

    private fun assumeSaved() = assumeTrue("Pinned to the saved responses", liveDir == null)

    private fun assertWellFormed(videos: List<YouTubeVideo>) {
        assertTrue("no videos", videos.isNotEmpty())
        videos.forEach { video ->
            assertEquals("video id ${video.videoId}", 11, video.videoId.length)
            assertTrue("blank title for ${video.videoId}", video.title.isNotBlank())
            assertTrue("thumbnail ${video.thumbnailUrl}", video.thumbnailUrl.startsWith("https://"))
        }
    }

    // --- Shape: holds for any current response ---

    @Test
    fun `search lists videos and pages on`() {
        val search = page("search")
        assertWellFormed(search.shelves.flatMap { it.videos })
        // The TV search pages its results row, not the page.
        assertNotNull("search continuation", search.continuation ?: search.shelves.firstNotNullOfOrNull { it.continuation })
        assertWellFormed(page("search_continuation").shelves.flatMap { it.videos })
    }

    @Test
    fun `channel page has titled shelves and its tab links`() {
        val channel = page("channel")
        val titled = channel.shelves.filter { it.title != null && it.videos.isNotEmpty() }
        assertTrue("titled shelves: ${channel.shelves.map { it.title }}", titled.size >= 2)
        assertWellFormed(titled.flatMap { it.videos })
        val tabs = channel.shelves.flatMap { it.links }.mapNotNull { it.tab }
        assertTrue("tabs: $tabs", YouTubeChannelTab.VIDEOS in tabs && YouTubeChannelTab.SHORTS in tabs)
    }

    @Test
    fun `channel shorts are flagged`() {
        val shorts = page("channel").shelves.filter { it.isShorts }
        assertTrue("no Shorts shelf", shorts.isNotEmpty())
        assertTrue(shorts.flatMap { it.videos }.all { it.isShort })

        val tab = page("channel_tab_shorts").shelves.flatMap { it.videos }
        assertWellFormed(tab)
        assertTrue(tab.all { it.isShort })
    }

    @Test
    fun `channel videos tab lists full videos`() {
        val videos = page("channel_tab_videos").shelves.flatMap { it.videos }
        assertWellFormed(videos)
        assertTrue(videos.none { it.isShort })
    }

    @Test
    fun `channel playlists tab links its playlists`() {
        val links = page("channel_tab_playlists").shelves.flatMap { it.links }
        assertTrue("no playlists", links.any { it.kind == YouTubeLinkKind.PLAYLIST && it.title != null })
    }

    @Test
    fun `playlist lists its videos`() {
        assertWellFormed(page("playlist").shelves.flatMap { it.videos })
    }

    @Test
    fun `next has an autoplay pick, related videos and the channel to subscribe to`() {
        val root = load("next")
        val videoId = currentVideoId(root)
        val next = YouTubeResponseParser.parseWatchNext(root, videoId)
        assertWellFormed(next.related)
        assertNotNull("autoplay pick", next.next)
        assertFalse(next.related.any { it.videoId == videoId })
        val actions = assertNotNull(next.actions)
        assertTrue("channel ${actions.channelId}", actions.channelId?.startsWith("UC") == true)
        assertNotNull("subscribed", actions.subscribed)
    }

    // --- Content: pinned to the saved responses ---

    @Test
    fun `saved channel keeps its shelf titles from the avatar lockup`() {
        assumeSaved()
        val titles = page("channel").shelves.mapNotNull { it.title }
        assertTrue(titles.toString(), "Shorts" in titles)
        assertTrue(titles.toString(), "Live now" in titles)
    }

    @Test
    fun `saved search keeps its rows titled, clusters included`() {
        assumeSaved()
        val shelves = page("search").shelves
        assertTrue(shelves.map { it.title }.toString(), shelves.all { it.title != null })
        val cluster = shelves.first { it.title == "NASA" }
        assertEquals("UCLA_DiR1FfKNvjuUpBHmylQ", cluster.viewAll?.browseId)
        assertTrue(shelves.first { it.title == "Shorts" }.isShorts)
    }

    @Test
    fun `saved live tab flags streams that are live now, not upcoming ones`() {
        assumeSaved()
        val videos = page("channel_tab_live").shelves.flatMap { it.videos }
        assertEquals(2, videos.count { it.isLive })
        assertEquals(6, videos.size)
    }

    @Test
    fun `saved channel tabs are read from their icons`() {
        assumeSaved()
        val tabs = page("channel").shelves.flatMap { it.links }.associate { it.title to it.tab }
        assertEquals(YouTubeChannelTab.VIDEOS, tabs["Videos"])
        assertEquals(YouTubeChannelTab.SHORTS, tabs["Shorts"])
        assertEquals(YouTubeChannelTab.LIVE, tabs["Live"])
        assertEquals(YouTubeChannelTab.PLAYLISTS, tabs["Playlists"])
    }

    private fun currentVideoId(root: JsonElement): String =
        Regex("\"currentVideoEndpoint\"\\s*:\\s*\\{\\s*\"watchEndpoint\"\\s*:\\s*\\{\\s*\"videoId\"\\s*:\\s*\"([^\"]+)\"")
            .find(root.toString())?.groupValues?.get(1)
            ?: error("no current video in next")

    private fun <T : Any> assertNotNull(value: T?): T {
        assertNotNull(null, value)
        return value!!
    }
}
