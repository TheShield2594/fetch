package com.fetch.desktop.servers

import com.fetch.tv.core.streams.ranking.StreamResolution
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import com.fetch.tv.core.streams.ranking.VideoCodec
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.data.mediaserver.ServerPlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class ServerModelsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stream = ServerStream(
        accountId = "acc-1", itemId = "m1", mediaSourceId = "src-4k",
        url = "http://den:8096/Videos/m1/stream?static=true&MediaSourceId=src-4k&api_key=tok",
        fileName = "The.Matrix.mkv", sizeBytes = 50_000_000_000L, width = 3840, height = 2160,
        videoCodec = "hevc", audioCodec = "truehd", audioChannels = 8, bitrate = null,
        durationMs = null, resumePositionMs = null
    )

    @Test
    fun `toStream sets the server target and parseable quality`() {
        val s = stream.toStream("Jellyfin · Den")
        assertEquals(stream.url, s.getStreamUrl())
        assertEquals("Jellyfin · Den", s.name)
        assertEquals("Jellyfin · Den", s.addonName)
        assertEquals(ServerPlaybackTarget(ServerItemRef("acc-1", "m1"), "src-4k"), s.serverTarget)
        assertEquals("The.Matrix.mkv", s.behaviorHints?.filename)
        assertEquals(50_000_000_000L, s.behaviorHints?.videoSize)
        assertTrue(s.title!!, s.title!!.startsWith("2160p HEVC TrueHD 7.1"))

        val parsed = StreamTitleParser.parse(s)
        assertEquals(StreamResolution.UHD_2160, parsed.resolution)
        assertEquals(VideoCodec.HEVC, parsed.videoCodec)
        assertEquals(50_000_000_000L, parsed.sizeBytes)
    }

    @Test
    fun `resolution comes from width for scope films`() {
        val s = stream.copy(width = 1920, height = 800, videoCodec = "h264").toStream("x")
        assertEquals(StreamResolution.FHD_1080, StreamTitleParser.parse(s).resolution)
    }

    @Test
    fun `toString hides tokens`() {
        val account = ServerAccount("a", ServerKind.PLEX, "n", "http://x", "s", "u", "bob", "secret-token")
        assertFalse(account.toString().contains("secret-token"))
        assertFalse(stream.toString().contains("api_key"))
    }

    @Test
    fun `store round-trips with owner-only permissions`() {
        val file = File(tmp.root, "sub/servers.json")
        val store = ServersStore(file)
        assertEquals(emptyList<ServerAccount>(), store.load())
        val accounts = listOf(
            ServerAccount("a", ServerKind.JELLYFIN, "Den", "http://den", "s", "u", "bob", "tok", listOf(ServerLib("1", "Movies", "movie", selected = false))),
            ServerAccount("b", ServerKind.PLEX, "Plexy", "https://p", "m", "42", "bob", "tok2", enabled = false)
        )
        store.save(accounts)
        assertEquals(accounts, ServersStore(file).load())
        assertFalse(File(file.parentFile, "servers.json.tmp").exists())
        runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()?.let {
            assertEquals(PosixFilePermissions.fromString("rw-------"), it)
        }
        file.writeText("not json")
        assertEquals(emptyList<ServerAccount>(), store.load())
    }

    @Test
    fun `addresses are normalised`() {
        assertEquals("http://den:8096", normalizeServerAddress("den:8096/"))
        assertEquals("https://x.com/jf", normalizeServerAddress("https://x.com/jf/web/index.html"))
        assertEquals("https://x.com", normalizeServerAddress("https://x.com/emby", "/emby"))
        assertEquals(null, normalizeServerAddress("  "))
    }

    @Test
    fun `version check`() {
        assertTrue(isAtLeastVersion("10.9.11", "10.9"))
        assertTrue(isAtLeastVersion("1.40.2.8395-c67dce28e", "1.30"))
        assertFalse(isAtLeastVersion("10.8.13", "10.9"))
        assertFalse(isAtLeastVersion(null, "4.7"))
    }
}
