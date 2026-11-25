package com.fetch.tv.data.mediaserver

import com.fetch.tv.core.player.Chapter
import com.fetch.tv.data.mediaserver.mediabrowser.BaseItem
import com.fetch.tv.data.mediaserver.mediabrowser.toChapters
import com.fetch.tv.data.mediaserver.plex.PlexResponse
import com.fetch.tv.data.mediaserver.plex.toChapters
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerChaptersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `jellyfin and emby chapters convert from ticks`() {
        val item = json.decodeFromString(
            BaseItem.serializer(),
            """{"Id":"1","Chapters":[{"StartPositionTicks":0,"Name":"Intro"},{"StartPositionTicks":900000000,"Name":"Act 1","ImageTag":"x"},{"Name":"No start"}]}"""
        )
        assertEquals(listOf(Chapter(0L, null, "Intro"), Chapter(90_000L, null, "Act 1")), item.chapters.toChapters())
    }

    @Test
    fun `plex chapters keep offsets and names in index order`() {
        val response = json.decodeFromString(
            PlexResponse.serializer(),
            """{"MediaContainer":{"Metadata":[{"ratingKey":"5","Chapter":[
                {"index":2,"tag":"Credits","startTimeOffset":1300000,"endTimeOffset":1400000},
                {"index":1,"tag":"Opening","startTimeOffset":0,"endTimeOffset":90000}
            ]}]}}"""
        )
        assertEquals(
            listOf(Chapter(0L, 90_000L, "Opening"), Chapter(1_300_000L, 1_400_000L, "Credits")),
            response.container.metadata.single().chapters.toChapters()
        )
    }
}
