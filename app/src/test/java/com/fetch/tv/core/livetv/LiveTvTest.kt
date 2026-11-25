package com.fetch.tv.core.livetv

import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogExtra
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.ui.screens.livetv.LiveTvGuideWindow
import com.fetch.tv.ui.screens.player.LivePlaybackUiPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live TV sources, the guide window and live marking (#660). */
class LiveTvTest {
    private fun catalog(type: String, id: String, vararg extra: CatalogExtra) =
        CatalogDescriptor(type = ContentType.fromString(type), rawType = type, id = id, name = id, extra = extra.toList())

    private fun addon(id: String, enabled: Boolean = true, vararg catalogs: CatalogDescriptor) = Addon(
        id = id,
        name = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://$id.example",
        catalogs = catalogs.toList(),
        types = emptyList(),
        resources = emptyList(),
        enabled = enabled
    )

    @Test
    fun `only browsable tv catalogs of enabled addons are Live TV sources`() {
        val sources = LiveTvRepository.liveCatalogs(
            listOf(
                addon(
                    "iptv",
                    true,
                    catalog("tv", "channels", CatalogExtra("skip")),
                    catalog("tv", "by-genre", CatalogExtra("genre", isRequired = true)),
                    catalog("tv", "find", CatalogExtra("search", isRequired = true)),
                    catalog("movie", "films")
                ),
                addon("off", false, catalog("tv", "channels")),
                addon("cinemeta", true, catalog("series", "top"), catalog("channel", "youtube"))
            )
        )
        assertEquals(listOf("iptv/channels"), sources.map { "${it.addon.id}/${it.catalog.id}" })
    }

    @Test
    fun `the guide window starts on the half hour and shows what overlaps it`() {
        val halfHour = LiveTvGuideWindow.SLOT_MS
        val start = LiveTvGuideWindow.startFor(10 * halfHour + 123_456L)
        assertEquals(10 * halfHour, start)
        val programs = listOf(
            LiveTvProgram("c", "Before", start - 2 * halfHour, start),
            LiveTvProgram("c", "Later", start + 2 * halfHour, start + 3 * halfHour),
            LiveTvProgram("c", "Now", start - halfHour, start + halfHour),
            LiveTvProgram("c", "After", start + LiveTvGuideWindow.WIDTH_MS, start + LiveTvGuideWindow.WIDTH_MS + halfHour)
        )
        assertEquals(listOf("Now", "Later"), LiveTvGuideWindow.visible(programs, start).map { it.title })
    }

    @Test
    fun `a channel opened from the guide is live whatever its type`() {
        assertFalse(LivePlaybackUiPolicy.isLiveContent("tv", "iptv:bbc1"))
        LiveTvChannels.markLive("iptv:bbc1")
        assertTrue(LivePlaybackUiPolicy.isLiveContent("tv", "iptv:bbc1"))
        assertTrue(LivePlaybackUiPolicy.isLivePlayback(playerReportsLive = false, contentType = "tv", contentId = "iptv:bbc1"))
        // "tv" alone is still Fetch's series synonym, so a TMDB show isn't live.
        assertFalse(LivePlaybackUiPolicy.isLiveContent("tv", "tmdb:1399"))
        assertTrue(LivePlaybackUiPolicy.isLiveContent("channel", null))
    }
}
