package com.fetch.tv.data.mediaserver.mediabrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Jellyfin and Emby Live TV channels and guide entries (#660). */
class MediaBrowserLiveTvTest {
    private val mapper = MediaBrowserMapper("https://jf.example", "conn")

    @Test
    fun `server dates read with seven fraction digits or an offset`() {
        assertEquals(1_764_086_400_000L, parseServerDate("2025-11-25T16:00:00.0000000Z"))
        assertEquals(1_764_086_400_000L, parseServerDate("2025-11-25T17:00:00+01:00"))
        assertEquals(1_764_086_400_000L, parseServerDate("2025-11-25T16:00:00"))
        assertNull(parseServerDate("soon"))
        assertNull(parseServerDate(null))
    }

    @Test
    fun `a channel carries its number, logo and what is on`() {
        val channel = mapper.liveChannel(
            BaseItem(
                id = "ch1",
                name = "BBC One",
                channelNumber = "101",
                imageTags = mapOf("Primary" to "tag"),
                currentProgram = BaseItem(
                    id = "p1",
                    name = "News",
                    startDate = "2025-11-25T16:00:00Z",
                    endDate = "2025-11-25T16:30:00Z"
                )
            )
        )
        assertEquals("101", channel.number)
        assertEquals("News", channel.currentProgram?.title)
        assertEquals("ch1", channel.currentProgram?.channelItemId)
        assertEquals(true, channel.logo?.contains("/Items/ch1/Images/Primary"))
    }

    @Test
    fun `a programme without a usable slot is dropped`() {
        assertNull(mapper.liveProgram(BaseItem(id = "p", name = "X", channelId = "c", startDate = "2025-11-25T16:00:00Z")))
        assertNull(
            mapper.liveProgram(
                BaseItem(id = "p", name = "X", channelId = "c", startDate = "2025-11-25T16:00:00Z", endDate = "2025-11-25T15:00:00Z")
            )
        )
        val ok = mapper.liveProgram(
            BaseItem(id = "p", name = "X", channelId = "c", startDate = "2025-11-25T16:00:00Z", endDate = "2025-11-25T17:00:00Z")
        )
        assertEquals(3_600_000L, ok!!.endMs - ok.startMs)
    }
}
