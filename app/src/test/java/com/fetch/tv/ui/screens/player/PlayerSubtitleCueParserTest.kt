package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSubtitleCueParserTest {

    @Test
    fun `srt cues lose ASS override tags`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,500
            {\an8}Top line

        """.trimIndent()
        val cues = PlayerSubtitleCueParser.parseFromText(srt, "https://example.com/sub.srt")
        assertEquals(1, cues.size)
        assertEquals("Top line", cues[0].text)
        assertEquals(1_000L, cues[0].startTimeMs)
        assertEquals(2_500L, cues[0].endTimeMs)
    }

    @Test
    fun `ass dialogue lines become cues`() {
        val ass = """
            [Script Info]
            Title: Test

            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.20,0:00:07.00,Default,,0,0,0,,{\i1}Second{\i0}, with comma
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,First\Nline two
        """.trimIndent()
        val cues = PlayerSubtitleCueParser.parseFromText(ass, "https://example.com/sub")
        assertEquals(2, cues.size)
        assertEquals(1_500L, cues[0].startTimeMs)
        assertEquals(3_000L, cues[0].endTimeMs)
        assertEquals("First\nline two", cues[0].text)
        assertEquals(5_200L, cues[1].startTimeMs)
        assertEquals("Second, with comma", cues[1].text)
    }

    @Test
    fun `ass drawing commands are not cue text`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\pos(10,10)\p1}m 0 0 l 100 100{\p0}Hello
            Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\p1}m 0 0 l 50 50
        """.trimIndent()
        val cues = PlayerSubtitleCueParser.parseFromText(ass, "https://example.com/sub.ass")
        assertEquals(1, cues.size)
        assertEquals("Hello", cues[0].text)
    }
}
