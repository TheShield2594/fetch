package com.fetch.desktop.playback

import com.fetch.tv.data.mediaserver.ServerPlaybackTarget
import com.fetch.tv.domain.model.ProxyHeaders
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class StreamLinkCacheTest {
    private var now = 1_000_000L
    private val dir = Files.createTempDirectory("fetch-links").toFile()
    private fun cache() = StreamLinkCache(
        com.fetch.desktop.data.JsonFile(java.io.File(dir, "stream-cache.json"), StreamCacheFile.serializer()) { StreamCacheFile() }
    ) { now }

    private val stream = Stream(
        name = "Torrentio\n1080p", title = "Show.S01E02.mkv", description = null, url = "https://debrid.example/file.mkv",
        ytId = null, infoHash = "hash", fileIdx = null, externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = "torrentio|1080p", countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = mapOf("Referer" to "x"), response = null), filename = "Show.S01E02.mkv"
        ),
        addonName = "Torrentio", addonLogo = null
    )

    @Test
    fun `the last link is kept for its hours, and survives a restart`() {
        cache().saveLink("tt1:1:2", stream)
        val again = cache()
        val link = again.validLink("tt1:1:2", maxAgeHours = 1)
        assertNotNull(link)
        val replayed = link!!.toStream()
        assertEquals(stream.url, replayed.url)
        assertEquals("torrentio|1080p", replayed.behaviorHints?.bingeGroup)
        assertEquals(mapOf("Referer" to "x"), replayed.behaviorHints?.proxyHeaders?.request)
        now += 3_600_001L
        assertNull(again.validLink("tt1:1:2", maxAgeHours = 1))
        assertNotNull(again.validLink("tt1:1:2", maxAgeHours = 2))
        again.forgetLink("tt1:1:2")
        assertNull(cache().validLink("tt1:1:2", maxAgeHours = 2))
    }

    @Test
    fun `server files and torrents aren't cached`() {
        val c = cache()
        c.saveLink("a", stream.copy(url = null))
        c.saveLink("b", stream.copy(serverTarget = ServerPlaybackTarget(com.fetch.tv.data.mediaserver.ServerItemRef("s", "i"), null)))
        assertNull(c.validLink("a", 24))
        assertNull(c.validLink("b", 24))
    }

    @Test
    fun `the binge group is replaced, or forgotten when a stream has none`() {
        val c = cache()
        c.saveBingeGroup("tt1", stream)
        assertEquals(BingeHint("torrentio|1080p", "Show.S01E02.mkv", "hash"), cache().bingeGroup("tt1"))
        c.saveBingeGroup("tt1", stream.copy(behaviorHints = null))
        assertNull(cache().bingeGroup("tt1"))
    }
}
