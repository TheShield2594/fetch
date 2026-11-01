package com.fetch.desktop.player

import com.fetch.tv.domain.model.ProxyHeaders
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPlayerTest {

    private fun stream(
        url: String? = null,
        ytId: String? = null,
        infoHash: String? = null,
        headers: Map<String, String>? = null
    ) = Stream(
        name = "Addon",
        title = "Movie.2024.2160p.WEB-DL",
        description = null,
        url = url,
        ytId = ytId,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = headers?.let {
            StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = ProxyHeaders(request = it, response = null)
            )
        },
        addonName = "Addon",
        addonLogo = null
    )

    @Test
    fun `mpv gets the title, one option per header, and the url after --`() {
        val command = ExternalPlayer.command(
            stream(url = "https://cdn.example/a.mkv", headers = mapOf("Referer" to "https://x.example", "Cookie" to "a=1, b=2")),
            title = "Movie",
            mpvPath = "/usr/bin/mpv"
        )
        assertEquals(
            listOf(
                "/usr/bin/mpv",
                "--force-window=immediate",
                "--force-media-title=Movie",
                "--http-header-fields-append=Referer: https://x.example",
                "--http-header-fields-append=Cookie: a=1, b=2",
                "--",
                "https://cdn.example/a.mkv"
            ),
            command
        )
    }

    @Test
    fun `without mpv the desktop default handler opens the link`() {
        val command = ExternalPlayer.command(stream(url = "https://cdn.example/a.mkv"), title = "Movie", mpvPath = null)
        assertEquals(listOf("xdg-open", "https://cdn.example/a.mkv"), command)
    }

    @Test
    fun `youtube ids play through their watch page`() {
        val command = ExternalPlayer.command(stream(ytId = "abc123"), title = "Trailer", mpvPath = "mpv")
        assertEquals("https://www.youtube.com/watch?v=abc123", command?.last())
    }

    @Test
    fun `torrent-only streams are not playable`() {
        val torrent = stream(infoHash = "0123456789abcdef0123456789abcdef01234567")
        assertFalse(ExternalPlayer.canPlay(torrent))
        assertNull(ExternalPlayer.command(torrent, title = "Movie", mpvPath = "mpv"))
        assertTrue(ExternalPlayer.canPlay(stream(url = "https://cdn.example/a.mkv")))
    }
}
