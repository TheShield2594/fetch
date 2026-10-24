package com.fetch.tv.core.streams

import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonResource
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.Video
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAvailabilityTest {
    @Test
    fun `no sources or metadata-only addons cannot play`() {
        assertFalse(PlaybackAvailability().canStream("movie", "tt123"))
        val metaAddon = addon().copy(resources = listOf(AddonResource("meta", listOf("movie"), null)))
        assertFalse(PlaybackAvailability(addons = listOf(metaAddon)).canStream("movie", "tt123"))
    }

    @Test
    fun `addon must be enabled and match the type and video prefix`() {
        val available = PlaybackAvailability(addons = listOf(addon()))
        assertTrue(available.canStream("movie", "tt123"))
        assertFalse(available.canStream("series", "tt123:1:1"))
        assertFalse(available.canStream("movie", "tmdb:123"))
        assertFalse(available.copy(addons = listOf(addon().copy(enabled = false))).canStream("movie", "tt123"))
    }

    @Test
    fun `resource prefixes override addon prefixes and missing prefixes fall back`() {
        val configured = addon().copy(idPrefixes = listOf("tmdb:"))
        assertTrue(configured.supportsStreamResource("movie", "tt123"))
        assertFalse(configured.supportsStreamResource("movie", "tmdb:123"))
        for (prefixes in listOf<List<String>?>(null, emptyList())) {
            val fallback = configured.copy(resources = listOf(AddonResource("stream", emptyList(), prefixes)))
            assertTrue(fallback.supportsStreamResource("series", "tmdb:123:1:1"))
            assertFalse(fallback.supportsStreamResource("movie", "tt123"))
        }
    }

    @Test
    fun `unrestricted stream resources support custom content types`() {
        val unrestricted = addon().copy(resources = listOf(AddonResource("stream", emptyList(), null)))
        assertTrue(PlaybackAvailability(addons = listOf(unrestricted)).canStream("channel", "channel:1"))
    }

    @Test
    fun `embedded streams allow only the matching video without installed sources`() {
        val video = video()
        val available = PlaybackAvailability()
        assertTrue(available.canStream("other", video.id, video = video))
        assertFalse(available.canStream("other", "different-video", video = video))
        assertFalse(available.canStream("other", video.id, video = video.copy(streams = emptyList())))
    }

    @Test
    fun `continue watching can use embedded streams from cached parent metadata`() {
        val video = video()
        val meta = mockk<Meta>()
        every { meta.videos } returns listOf(video)
        val available = PlaybackAvailability(cachedMeta = { type, id ->
            meta.takeIf { type == "other" && id == "parent" }
        })
        assertTrue(available.canStream("other", video.id, "parent"))
        assertFalse(available.canStream("other", "different-video", "parent"))
        assertFalse(available.canStream("other", video.id, "uncached-parent"))
    }

    @Test
    fun `continue watching keeps embedded streams playable when parent metadata is not cached`() {
        val metaAddon = addon().copy(resources = listOf(AddonResource("meta", listOf("other"), null)))
        val available = PlaybackAvailability(addons = listOf(metaAddon))
        assertTrue(available.canStream("other", "embedded-video", "uncached-parent"))
        assertFalse(available.copy(addons = listOf(metaAddon.copy(enabled = false)))
            .canStream("other", "embedded-video", "uncached-parent"))
        assertFalse(available.canStream("movie", "embedded-video", "uncached-parent"))
    }

    @Test
    fun `a metadata addon alone does not make plain imdb titles playable`() {
        val metaAddon = addon().copy(resources = listOf(AddonResource("meta", listOf("movie", "series"), null)))
        val available = PlaybackAvailability(addons = listOf(metaAddon))
        assertFalse(available.canStream("movie", "tt123", "tt123"))
        assertFalse(available.canStream("series", "tt123:1:2", "tt123"))
    }

    private fun addon() = Addon(
        id = "addon", name = "Addon", version = "1", description = null, logo = null,
        baseUrl = "https://example.com", catalogs = emptyList(), types = emptyList(),
        resources = listOf(AddonResource("stream", listOf("movie"), listOf("tt")))
    )

    private fun video() = Video(
        id = "embedded-video", title = "Video", released = null, thumbnail = null,
        streams = listOf(mockk<Stream>()), season = null, episode = null, overview = null
    )
}
