package com.fetch.tv.core.player

import com.fetch.tv.core.streams.ranking.DeviceDecodeCapabilities
import com.fetch.tv.data.local.StreamAutoPlayMode
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamNextSourceAfterFailureTest {

    private val sd = stream("AddonA", "https://a/sd.mkv", "480p")
    private val uhd = stream("AddonB", "https://b/uhd.mkv", "2160p")
    private val hd = stream("AddonB", "https://b/hd.mkv", "1080p")
    private val all = listOf(sd, uhd, hd)

    private fun next(
        mode: StreamAutoPlayMode,
        failed: Set<Stream>,
        streams: List<Stream> = all,
        selectedAddons: Set<String> = emptySet()
    ): Stream? = StreamAutoPlaySelector.nextAfterFailure(
        streams = streams,
        mode = mode,
        installedAddonNames = setOf("AddonA", "AddonB"),
        selectedAddons = selectedAddons,
        failed = { it in failed },
        device = DeviceDecodeCapabilities.Permissive
    )

    @Test
    fun `best match moves to the next best stream`() {
        assertEquals(hd, next(StreamAutoPlayMode.BEST_MATCH, failed = setOf(uhd)))
        assertEquals(sd, next(StreamAutoPlayMode.BEST_MATCH, failed = setOf(uhd, hd)))
    }

    @Test
    fun `other modes follow the addons' order`() {
        assertEquals(uhd, next(StreamAutoPlayMode.FIRST_STREAM, failed = setOf(sd)))
        assertEquals(hd, next(StreamAutoPlayMode.MANUAL, failed = setOf(sd, uhd)))
    }

    @Test
    fun `nothing left once every stream has failed`() {
        assertNull(next(StreamAutoPlayMode.BEST_MATCH, failed = all.toSet()))
    }

    @Test
    fun `skips streams the player can't open`() {
        val web = stream("AddonA", url = null, name = "2160p", externalUrl = "https://example.com/watch")
        assertEquals(hd, next(StreamAutoPlayMode.BEST_MATCH, failed = setOf(uhd), streams = listOf(web) + all))
    }

    @Test
    fun `keeps to the addons allowed for autoplay`() {
        assertEquals(
            hd,
            next(StreamAutoPlayMode.FIRST_STREAM, failed = setOf(uhd), selectedAddons = setOf("AddonB"))
        )
        assertNull(next(StreamAutoPlayMode.FIRST_STREAM, failed = setOf(uhd, hd), selectedAddons = setOf("AddonB")))
    }

    private fun stream(addonName: String, url: String?, name: String, externalUrl: String? = null): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null
        ),
        addonName = addonName,
        addonLogo = null
    )
}
