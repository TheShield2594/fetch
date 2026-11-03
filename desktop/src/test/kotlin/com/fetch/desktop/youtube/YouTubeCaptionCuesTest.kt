package com.fetch.desktop.youtube

import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.player.Argb
import com.fetch.desktop.player.SubtitleStyle
import com.fetch.desktop.player.autosync.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCaptionCuesTest {
    private val track = YouTubeCaptionTrack("en", "English", false, false, listOf("https://www.youtube.com/api/timedtext?v=x&lang=en"))

    @Test
    fun `reads json3 as the TV does`() {
        val json3 = """{"events":[
            {"tStartMs":0,"dDurationMs":5000},
            {"tStartMs":1000,"dDurationMs":2000,"segs":[{"utf8":"Hello "},{"utf8":"there"}]},
            {"tStartMs":4000,"dDurationMs":0,"segs":[{"utf8":"zero"}]},
            {"tStartMs":3500,"dDurationMs":1500,"segs":[{"utf8":"\n"}]},
            {"tStartMs":5000,"dDurationMs":1000,"segs":[{"utf8":"Bye"}]}]}"""
        assertEquals(listOf(Cue(1_000, 3_000, "Hello there"), Cue(5_000, 6_000, "Bye")), parseJson3(json3))
        assertTrue(parseJson3("not json").isEmpty())
    }

    @Test
    fun `asks for json3 first and falls back to WebVTT`() {
        val asked = mutableListOf<String>()
        val cues = YouTubeCaptions.cues(track) { url ->
            asked += url
            if ("fmt=vtt" in url) "WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n" else null
        }
        assertEquals(listOf(Cue(1_000, 2_000, "Hi")), cues)
        assertTrue("fmt=json3" in asked[0])
        assertTrue(YouTubeCaptions.cues(track) { null }.isEmpty())
    }

    @Test
    fun `the cue at a position, with rolling captions overlapping`() {
        val cues = listOf(Cue(0, 2_000, "a"), Cue(1_500, 4_000, "b"), Cue(5_000, 6_000, "c"))
        assertEquals("a", cueAt(cues, 1_000)?.text)
        assertEquals("b", cueAt(cues, 1_800)?.text)
        assertNull(cueAt(cues, 4_500))
        assertEquals("c", cueAt(cues, 5_000)?.text)
        assertNull(cueAt(emptyList(), 0))
    }

    @Test
    fun `captions take the player's subtitle style`() {
        val look = SubtitleStyle.captionLook(PlayerSettings(subtitleScale = 1.5, subtitleBold = true, subtitleColor = "#FFD700", subtitlePosition = 90), liftPercent = 10)
        assertEquals(1.5f, look.scale, 0.001f)
        assertTrue(look.bold)
        assertEquals(Argb.parse("#FFD700"), look.textArgb)
        assertEquals(Argb.BLACK, look.outlineArgb)
        assertNull(look.backgroundArgb)
        assertEquals(20, look.bottomPercent)
        val boxed = SubtitleStyle.captionLook(PlayerSettings(subtitleBackground = true))
        assertNull(boxed.outlineArgb)
        assertEquals(Argb.parse(SubtitleStyle.BACKGROUND), boxed.backgroundArgb)
        assertEquals(0, boxed.bottomPercent)
    }
}
