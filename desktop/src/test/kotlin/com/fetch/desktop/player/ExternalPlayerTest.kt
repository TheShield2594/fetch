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

    @Test
    fun `the link and headers stay off mpv's command line, in files only this user can read`() {
        val dir = java.nio.file.Files.createTempDirectory("fetch-private").toFile()
        val s = stream(url = "https://debrid.example/secret-token/a.mkv", headers = mapOf("Authorization" to "Bearer abc, def", "Referer" to "https://x"))
        val launch = ExternalPlayer.PrivateLaunch.create(s, dir)
        org.junit.Assume.assumeTrue("needs POSIX file permissions", launch != null)
        launch!!
        val command = ExternalPlayer.command(s, title = "Movie", mpvPath = "mpv", privateLaunch = launch)!!
        assertFalse(command.any { "secret-token" in it || "Bearer" in it || "Referer" in it })
        assertTrue("--include=${launch.config!!.absolutePath}" in command)
        assertEquals("--playlist=${launch.playlist.absolutePath}", command.last())
        assertEquals("#EXTM3U\nhttps://debrid.example/secret-token/a.mkv\n", launch.playlist.readText())
        // Each header length-prefixed, so its commas and quotes stay part of the value.
        assertEquals(
            "http-header-fields-append=%30%Authorization: Bearer abc, def\nhttp-header-fields-append=%18%Referer: https://x\n",
            launch.config!!.readText()
        )
        listOf(launch.config!!, launch.playlist).forEach { file ->
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(file.toPath())))
        }
        launch.delete()
        assertFalse(launch.playlist.exists())
        assertFalse(launch.config!!.exists())
    }

    @Test
    fun `without headers there's no config, and a line break falls back to the command line`() {
        val dir = java.nio.file.Files.createTempDirectory("fetch-private").toFile()
        val plain = ExternalPlayer.PrivateLaunch.create(stream(url = "https://cdn.example/a.mkv"), dir)
        org.junit.Assume.assumeTrue("needs POSIX file permissions", plain != null)
        assertNull(plain!!.config)
        plain.delete()
        assertNull(ExternalPlayer.PrivateLaunch.create(stream(url = "https://cdn.example/a.mkv", headers = mapOf("X" to "a\nb")), dir))
        assertNull(ExternalPlayer.PrivateLaunch.create(stream(infoHash = "abc"), dir))
    }

    @Test
    fun `YouTube pages need yt-dlp, direct links don't`() {
        assertTrue(YtDlp.needs("https://www.youtube.com/watch?v=abc"))
        assertTrue(YtDlp.needs("https://youtu.be/abc"))
        assertTrue(YtDlp.needs("https://m.youtube.com/watch?v=abc"))
        assertFalse(YtDlp.needs("https://notyoutube.com/watch?v=abc"))
        assertFalse(YtDlp.needs("https://cdn.example/a.mkv"))
        assertFalse(YtDlp.needs(null))
        assertEquals(YtDlp.MISSING_MESSAGE, YtDlp.missingFor("https://youtu.be/abc") { false })
        assertNull(YtDlp.missingFor("https://youtu.be/abc") { true })
        assertNull(YtDlp.find(path = ""))
    }
}
