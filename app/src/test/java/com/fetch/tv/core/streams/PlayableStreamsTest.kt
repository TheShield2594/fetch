package com.fetch.tv.core.streams

import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayableStreamsTest {

    @Test
    fun `torrents and client-resolved debrid streams are hidden and counted`() {
        val direct = stream(name = "direct", url = "https://example.com/video.mkv")
        val group = AddonStreams(
            addonName = "Torrentio",
            addonLogo = null,
            streams = listOf(
                stream(name = "hash", infoHash = HASH),
                direct,
                stream(name = "magnet", url = "magnet:?xt=urn:btih:$HASH"),
                stream(name = "resolve", clientResolve = debridResolve())
            )
        )

        val presented = group.withoutUnplayableStreams()

        assertEquals(listOf(direct), presented.streams)
        assertEquals(3, presented.hiddenUnplayableCount)
    }

    @Test
    fun `a debrid stream with an http link is kept even when it carries an info hash`() {
        val cached = stream(name = "rd", url = "https://real-debrid.example/dl/video.mkv", infoHash = HASH)
        val group = AddonStreams(addonName = "Torrentio", addonLogo = null, streams = listOf(cached))

        val presented = group.withoutUnplayableStreams()

        assertSame(group, presented)
        assertEquals(0, presented.hiddenUnplayableCount)
    }

    @Test
    fun `hidden counts add up when an addon's results are presented again`() {
        val first = AddonStreams(
            addonName = "Torrentio",
            addonLogo = null,
            streams = listOf(stream(name = "a", infoHash = HASH))
        ).withoutUnplayableStreams()
        val second = first.copy(
            streams = first.streams + stream(name = "b", infoHash = OTHER_HASH)
        ).withoutUnplayableStreams()

        assertTrue(second.streams.isEmpty())
        assertEquals(2, second.hiddenUnplayableCount)
    }

    @Test
    fun `counts are summed per addon and addons with nothing hidden are left out`() {
        val groups = listOf(
            AddonStreams("Torrentio", null, emptyList(), hiddenUnplayableCount = 40),
            AddonStreams("Direct", null, listOf(stream(name = "d", url = "https://example.com/a.mp4"))),
            AddonStreams("Torrentio", null, emptyList(), hiddenUnplayableCount = 2),
            AddonStreams("Mixed", null, listOf(stream(name = "m", url = "https://example.com/b.mp4")), hiddenUnplayableCount = 5)
        )

        assertEquals(mapOf("Torrentio" to 42, "Mixed" to 5), groups.hiddenUnplayableCounts())
    }

    @Test
    fun `only addons whose every stream was hidden count as unplayable only`() {
        val groups = listOf(
            AddonStreams("Torrentio", null, emptyList(), hiddenUnplayableCount = 12),
            AddonStreams("Mixed", null, listOf(stream(name = "m", url = "https://example.com/b.mp4")), hiddenUnplayableCount = 5),
            AddonStreams("Empty", null, emptyList())
        )

        assertEquals(setOf("Torrentio"), groups.unplayableOnlyAddonNames())
    }

    private fun stream(
        name: String,
        url: String? = null,
        infoHash: String? = null,
        clientResolve: StreamClientResolve? = null
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
        addonName = "Addon",
        addonLogo = null,
        clientResolve = clientResolve
    )

    private fun debridResolve(): StreamClientResolve = StreamClientResolve(
        type = "debrid",
        infoHash = HASH,
        fileIdx = null,
        torrentName = "Torrent",
        filename = "video.mkv"
    )

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
        const val OTHER_HASH = "89abcdef0123456789abcdef0123456789abcdef"
    }
}
