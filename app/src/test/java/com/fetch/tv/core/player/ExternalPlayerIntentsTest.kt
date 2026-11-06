package com.fetch.tv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalPlayerIntentsTest {

    @Test
    fun `headers are sent as alternating names and values`() {
        val headers = linkedMapOf(
            "Authorization" to "Bearer x",
            "Referer" to "https://example.com/"
        )

        assertArrayEquals(
            arrayOf("Authorization", "Bearer x", "Referer", "https://example.com/"),
            ExternalPlayerIntents.headersExtra(headers)
        )
    }

    @Test
    fun `no headers extra when there are none`() {
        assertNull(ExternalPlayerIntents.headersExtra(null))
        assertNull(ExternalPlayerIntents.headersExtra(emptyMap()))
    }

    @Test
    fun `subtitle filenames stay distinct for the same language and addon`() {
        val subs = listOf(
            SubtitleInput("content://a/000_en_1.vtt", "English - Addon", "en"),
            SubtitleInput("content://a/001_en_2.srt", "English - Addon", "en"),
            SubtitleInput("content://a/002_en_3", "English - Addon", "en")
        )

        assertEquals(
            listOf(
                "1_en_English - Addon.vtt",
                "2_en_English - Addon.srt",
                "3_en_English - Addon.srt"
            ),
            ExternalPlayerIntents.subtitleFilenames(subs)
        )
    }
}
