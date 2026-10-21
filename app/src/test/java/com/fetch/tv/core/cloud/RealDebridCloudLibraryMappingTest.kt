package com.fetch.tv.core.cloud

import com.fetch.tv.data.remote.dto.RealDebridDownloadDto
import com.fetch.tv.data.remote.dto.RealDebridTorrentFileDto
import com.fetch.tv.data.remote.dto.RealDebridTorrentInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDebridCloudLibraryMappingTest {
    @Test
    fun `selected files pair with links in file order`() {
        val torrent = RealDebridTorrentInfoDto(id = "T", filename = "Show S01", status = "downloaded", bytes = 30)
        val details = torrent.copy(
            files = listOf(
                RealDebridTorrentFileDto(id = 3, path = "/Show S01/E02.mkv", bytes = 20, selected = 1),
                RealDebridTorrentFileDto(id = 1, path = "/Show S01/E01.mkv", bytes = 10, selected = 1),
                RealDebridTorrentFileDto(id = 2, path = "/Show S01/info.nfo", bytes = 1, selected = 0)
            ),
            links = listOf("link-e01", "link-e02")
        )

        val item = realDebridTorrentCloudItem(torrent, details, "realdebrid", "Real-Debrid")!!

        assertEquals(CloudLibraryItemType.Torrent, item.type)
        assertEquals(listOf("E01.mkv" to "link-e01", "E02.mkv" to "link-e02"), item.files.map { it.name to it.id })
        assertTrue(item.files.all { it.playable })
    }

    @Test
    fun `links without matching files still play under the torrent name`() {
        val torrent = RealDebridTorrentInfoDto(
            id = "T",
            filename = "Movie",
            status = "downloaded",
            bytes = 99,
            links = listOf("only-link")
        )

        val item = realDebridTorrentCloudItem(torrent, details = null, providerId = "realdebrid", providerName = "Real-Debrid")!!

        assertEquals(listOf("Movie"), item.files.map { it.name })
        assertEquals(99L, item.files.single().sizeBytes)
        assertTrue(item.files.single().playable)
    }

    @Test
    fun `unfinished torrent has no files to play`() {
        val torrent = RealDebridTorrentInfoDto(id = "T", filename = "Pending", status = "downloading", progress = 40)

        val item = realDebridTorrentCloudItem(torrent, null, "realdebrid", "Real-Debrid")!!

        assertTrue(item.files.isEmpty())
        assertEquals(0.4f, item.progressFraction!!, 0.001f)
    }

    @Test
    fun `download plays its direct link`() {
        val item = realDebridDownloadCloudItem(
            RealDebridDownloadDto(id = "D", filename = "clip.mp4", filesize = 5, download = "https://cdn.example/clip.mp4"),
            "realdebrid",
            "Real-Debrid"
        )!!

        assertEquals(CloudLibraryItemType.WebDownload, item.type)
        assertEquals("https://cdn.example/clip.mp4", item.files.single().playbackUrl)
        assertTrue(item.files.single().playable)
    }

    @Test
    fun `non video download is listed but not playable, and one without a link is skipped`() {
        val archive = realDebridDownloadCloudItem(
            RealDebridDownloadDto(id = "D", filename = "pack.zip", download = "https://cdn.example/pack.zip"),
            "realdebrid",
            "Real-Debrid"
        )!!
        assertFalse(archive.files.single().playable)
        assertNull(realDebridDownloadCloudItem(RealDebridDownloadDto(id = "X"), "realdebrid", "Real-Debrid"))
    }
}
