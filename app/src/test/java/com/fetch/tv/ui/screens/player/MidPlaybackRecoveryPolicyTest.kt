package com.fetch.tv.ui.screens.player

import com.fetch.tv.domain.model.Stream
import com.fetch.tv.ui.screens.player.MidPlaybackRecoveryPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MidPlaybackRecoveryPolicyTest {

    @Test
    fun `expired or refused links and source errors are recovered`() {
        listOf(401, 403, 404, 410).forEach { status ->
            assertTrue("HTTP $status", MidPlaybackRecoveryPolicy.applies(httpStatus = status, isSourceError = false))
        }
        assertTrue(MidPlaybackRecoveryPolicy.applies(httpStatus = null, isSourceError = true))
        assertTrue(MidPlaybackRecoveryPolicy.applies(httpStatus = 503, isSourceError = true))
    }

    @Test
    fun `decoder and other non-source failures keep their error`() {
        assertFalse(MidPlaybackRecoveryPolicy.applies(httpStatus = null, isSourceError = false))
        assertFalse(MidPlaybackRecoveryPolicy.applies(httpStatus = 500, isSourceError = false))
    }

    @Test
    fun `first the same source is refreshed`() {
        assertEquals(
            Step.REFRESH_SAME_SOURCE,
            MidPlaybackRecoveryPolicy.nextStep(refreshTried = false, nextSourcesTried = 0, nextSourceEnabled = true)
        )
        // Refreshing the same source doesn't depend on the next-source setting.
        assertEquals(
            Step.REFRESH_SAME_SOURCE,
            MidPlaybackRecoveryPolicy.nextStep(refreshTried = false, nextSourcesTried = 0, nextSourceEnabled = false)
        )
    }

    @Test
    fun `then the next ranked sources up to the limit`() {
        for (tried in 0 until MidPlaybackRecoveryPolicy.MAX_NEXT_SOURCES) {
            assertEquals(
                Step.NEXT_SOURCE,
                MidPlaybackRecoveryPolicy.nextStep(refreshTried = true, nextSourcesTried = tried, nextSourceEnabled = true)
            )
        }
    }

    @Test
    fun `then the error with its actions`() {
        assertEquals(
            Step.SHOW_ERROR,
            MidPlaybackRecoveryPolicy.nextStep(
                refreshTried = true,
                nextSourcesTried = MidPlaybackRecoveryPolicy.MAX_NEXT_SOURCES,
                nextSourceEnabled = true
            )
        )
        // With "try the next stream" off, the error follows the failed refresh.
        assertEquals(
            Step.SHOW_ERROR,
            MidPlaybackRecoveryPolicy.nextStep(refreshTried = true, nextSourcesTried = 0, nextSourceEnabled = false)
        )
    }

    @Test
    fun `finds the same file from the same addon with a new link`() {
        val other = stream(addon = "Torrentio", name = "RD 1080p", url = "https://a/other", infoHash = "bbb", fileIdx = 1)
        val same = stream(addon = "Torrentio", name = "RD 2160p", url = "https://a/fresh", infoHash = "AAA", fileIdx = 2)
        val otherAddon = stream(addon = "Comet", name = "RD 2160p", url = "https://c/x", infoHash = "aaa", fileIdx = 2)

        val found = MidPlaybackRecoveryPolicy.findSameSource(
            streams = listOf(otherAddon, other, same),
            addonName = "Torrentio",
            name = "RD 2160p",
            description = null,
            infoHash = "aaa",
            fileIdx = 2,
            filename = null
        )
        assertSame(same, found)
    }

    @Test
    fun `falls back to name and description, then filename`() {
        val byName = stream(addon = "Addon", name = "Movie 1080p", description = "x265", url = "https://a/1")
        assertSame(
            byName,
            MidPlaybackRecoveryPolicy.findSameSource(
                listOf(stream(addon = "Addon", name = "Movie 1080p", description = "x264", url = "https://a/2"), byName),
                addonName = "Addon",
                name = "Movie 1080p",
                description = "x265",
                infoHash = null,
                fileIdx = null,
                filename = null
            )
        )

        val byFile = stream(addon = "Addon", name = "Renamed", url = "https://a/3", filename = "Movie.2160p.mkv")
        assertSame(
            byFile,
            MidPlaybackRecoveryPolicy.findSameSource(
                listOf(byFile),
                addonName = "Addon",
                name = "Old name",
                description = null,
                infoHash = null,
                fileIdx = null,
                filename = "Movie.2160p.mkv"
            )
        )
    }

    @Test
    fun `no match when the addon no longer lists the source`() {
        assertNull(
            MidPlaybackRecoveryPolicy.findSameSource(
                listOf(stream(addon = "Comet", name = "Movie", url = "https://c/1")),
                addonName = "Torrentio",
                name = "Movie",
                description = null,
                infoHash = null,
                fileIdx = null,
                filename = null
            )
        )
    }

    private fun stream(
        addon: String,
        name: String?,
        url: String,
        description: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        filename: String? = null
    ) = Stream(
        name = name,
        title = null,
        description = description,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = fileIdx,
        externalUrl = null,
        behaviorHints = filename?.let {
            com.fetch.tv.domain.model.StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = it
            )
        },
        addonName = addon,
        addonLogo = null
    )
}
