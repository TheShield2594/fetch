package com.fetch.tv.core.debrid

import com.fetch.tv.data.local.DebridSettingsDataStore
import com.fetch.tv.data.local.StreamBadgeSettingsDataStore
import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.DebridSettings
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamClientResolve
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridStreamPresentationTest {
    private val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    private val presentation = DebridStreamPresentation(
        dataStore = mockk<DebridSettingsDataStore>(relaxed = true),
        streamBadgeSettingsDataStore = mockk<StreamBadgeSettingsDataStore>(relaxed = true),
        formatter = formatter
    )
    private val settings = DebridSettings(
        enabled = true,
        torboxApiKey = "key",
        streamNameTemplate = "{stream.resolution} {stream.quality} {stream.encode}",
        streamDescriptionTemplate = "{stream.filename}"
    )

    @Test
    fun `labels every debrid stream and leaves filtering to stream ranking`() {
        val passthrough = Stream(
            name = "Regular",
            title = "Movie",
            description = null,
            url = "https://example.com/movie.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Addon",
            addonLogo = null
        )
        val debridStreams = listOf(
            debridStream("Movie.2023.1080p.WEB-DL.DDP5.1.x265-GRP.mkv"),
            debridStream("Movie.2023.2160p.BluRay.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv"),
            debridStream("Movie.2023.720p.WEBRip.AAC.x264-GRP.mkv"),
            debridStream("Movie.2023.1440p.HDTV.HDR.AV1.mkv")
        )

        val result = presentation.apply(
            groups = listOf(AddonStreams(addonName = "Addon", addonLogo = null, streams = debridStreams + passthrough)),
            settings = settings,
            includeBadgeMatches = false
        ).single().streams

        assertEquals(debridStreams.map { formatter.format(it, settings) } + passthrough, result)
        assertEquals(
            listOf("1080p WEB-DL HEVC", "2160p BluRay REMUX HEVC", "720p WEBRip AVC", "1440p HDTV AV1"),
            result.dropLast(1).map { it.name }
        )
    }

    private fun debridStream(filename: String): Stream = Stream(
        name = "TB cached",
        title = "Movie",
        description = filename,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = filename.hashCode().toString(),
            fileIdx = 0,
            magnetUri = null,
            sources = null,
            torrentName = filename,
            filename = filename,
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Movie",
            season = null,
            episode = null,
            service = "torbox",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )
}
