package com.fetch.tv.core.player

import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.core.streams.ranking.StreamResolution
import com.fetch.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAutoPlayEarlyPickTest {

    private val addons = listOf("Torrentio", "Comet", "MediaFusion")

    @Test
    fun `no addon limit means every addon could still win`() {
        assertNull(
            StreamAutoPlaySelector.sourcesNeededForPick(
                selectedAddons = emptySet(),
                streamAddonNames = addons,
                serverSourceNames = emptyList()
            )
        )
    }

    @Test
    fun `limited addons plus media servers are all that must answer`() {
        assertEquals(
            setOf("Torrentio", "Jellyfin"),
            StreamAutoPlaySelector.sourcesNeededForPick(
                selectedAddons = setOf("Torrentio", "Uninstalled"),
                streamAddonNames = addons,
                serverSourceNames = listOf("Jellyfin")
            )
        )
    }

    @Test
    fun `a limit naming no stream addon falls back to waiting`() {
        assertNull(
            StreamAutoPlaySelector.sourcesNeededForPick(
                selectedAddons = setOf("Cinemeta"),
                streamAddonNames = addons,
                serverSourceNames = emptyList()
            )
        )
    }

    private val rules1080 = StreamRankingRules(preferredResolution = StreamResolution.FHD_1080)

    @Test
    fun `a cached stream at the preferred quality starts early`() {
        assertTrue(StreamAutoPlaySelector.isGoodEnoughToStartEarly(stream("Movie 1080p [RD+]"), rules1080))
        assertTrue(StreamAutoPlaySelector.isGoodEnoughToStartEarly(stream("Movie 2160p [RD+]"), rules1080))
    }

    @Test
    fun `below the preferred quality or of unknown quality waits`() {
        assertFalse(StreamAutoPlaySelector.isGoodEnoughToStartEarly(stream("Movie 720p [RD+]"), rules1080))
        assertFalse(StreamAutoPlaySelector.isGoodEnoughToStartEarly(stream("Movie [RD+]"), rules1080))
    }

    @Test
    fun `an uncached stream waits only while cached streams are preferred`() {
        val uncached = stream("Movie 1080p")
        assertFalse(StreamAutoPlaySelector.isGoodEnoughToStartEarly(uncached, rules1080))
        assertTrue(
            StreamAutoPlaySelector.isGoodEnoughToStartEarly(uncached, rules1080.copy(preferCachedDebrid = false))
        )
    }

    @Test
    fun `the maximum quality caps the target`() {
        val rules = StreamRankingRules(
            preferredResolution = StreamResolution.UHD_2160,
            maxResolution = StreamResolution.FHD_1080
        )
        assertTrue(StreamAutoPlaySelector.isGoodEnoughToStartEarly(stream("Movie 1080p [RD+]"), rules))
    }

    @Test
    fun `a peer-to-peer torrent never starts early`() {
        val torrent = stream("Movie 2160p", url = null, infoHash = "abcdef0123456789")
        assertFalse(
            StreamAutoPlaySelector.isGoodEnoughToStartEarly(torrent, rules1080.copy(preferCachedDebrid = false))
        )
    }

    private fun stream(
        name: String,
        url: String? = "https://example.com/video.mkv",
        infoHash: String? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Torrentio",
        addonLogo = null
    )
}
