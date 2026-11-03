package com.fetch.desktop.player.autosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutoSyncTest {

    @Test
    fun `reads SubRip, WebVTT and ASS`() {
        val srt = "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\nHello\r\n\r\n2\r\n00:01:02,250 --> 00:01:04,000\r\nWorld\r\n"
        assertEquals(listOf(Cue(1_500, 3_000, "Hello"), Cue(62_250, 64_000, "World")), CueParser.parse(srt))
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.500\nHi\n\n01:00:00.000 --> 01:00:01.000 align:start\nLater\n"
        assertEquals(listOf(Cue(1_000, 2_500, "Hi"), Cue(3_600_000, 3_601_000, "Later")), CueParser.parse(vtt))
        val ass = """
            [Script Info]
            Title: x
            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.10,0:00:07.00,Default,,0,0,0,,{\i1}Hello, there\Nfriend
            Dialogue: 0,0:00:08.00,0:00:09.00,Default,,0,0,0,,{\p1}
        """.trimIndent()
        assertEquals(listOf(Cue(5_100, 7_000, "Hello, there\nfriend")), CueParser.parse(ass))
        assertTrue(CueParser.parse("not a subtitle").isEmpty())
    }

    /** A dialogue-like track: lines of 1–4 s with gaps of 0.5–6 s. */
    private fun dialogue(seed: Int, count: Int, startMs: Long = 0): List<Cue> {
        val random = Random(seed)
        var t = startMs
        return List(count) {
            t += 500 + random.nextLong(5_500)
            val length = 1_000 + random.nextLong(3_000)
            Cue(t, t + length, "line $it").also { t += length }
        }
    }

    @Test
    fun `finds a constant offset over the stretch that played`() {
        val video = dialogue(7, 400)
        val subtitle = video.map { it.copy(startMs = it.startMs - 2_340, endMs = it.endMs - 2_340) }
        // Only the first few minutes of the video's own track have been seen.
        val played = video.take(40)
        assertTrue(AutoSyncAligner.enoughReference(played))
        val result = AutoSyncAligner.align(played, subtitle)!!
        assertTrue(result.confident)
        assertEquals(2_340.0, result.offsetMs.toDouble(), 20.0)
        assertEquals(result.offsetMs, AutoSyncAligner.delayToApply(result, toleranceMs = 0))
        assertEquals("+2.34 s", AutoSyncAligner.label(2_340))
    }

    @Test
    fun `small corrections are left alone within the tolerance`() {
        val video = dialogue(3, 200)
        val subtitle = video.map { it.copy(startMs = it.startMs + 150, endMs = it.endMs + 150) }
        val result = AutoSyncAligner.align(video.take(40), subtitle)!!
        assertEquals(-150.0, result.offsetMs.toDouble(), 20.0)
        assertEquals(0L, AutoSyncAligner.delayToApply(result, toleranceMs = 200))
        assertEquals(result.offsetMs, AutoSyncAligner.delayToApply(result, toleranceMs = 100))
    }

    @Test
    fun `an unrelated subtitle isn't trusted`() {
        val result = AutoSyncAligner.align(dialogue(1, 40), dialogue(99, 400))
        assertFalse(result?.confident ?: false)
        assertNull(AutoSyncAligner.delayToApply(result, 0))
    }

    @Test
    fun `the thorough search needs two estimates that agree`() {
        val a = AutoSyncResult(1_000, 0.8, 0.3, true)
        assertTrue(AutoSyncAligner.agree(a, a.copy(offsetMs = 1_100)))
        assertFalse(AutoSyncAligner.agree(a, a.copy(offsetMs = 1_400)))
        assertFalse(AutoSyncAligner.agree(null, a))
    }

    @Test
    fun `the live reference keeps one cue per start`() {
        val live = LiveReference()
        live.observe(1.0, 2.0, "a")
        live.observe(1.0, 2.0, "a")
        live.observe(3.0, 2.0, "backwards")
        live.observe(4.0, 5.5, "")
        live.observe(6.0, 7.25, "b")
        assertEquals(listOf(Cue(1_000, 2_000, "a"), Cue(6_000, 7_250, "b")), live.cues())
    }

    @Test
    fun `overlap is measured exactly`() {
        val ref = listOf(Cue(0, 1_000), Cue(2_000, 3_000))
        val target = listOf(Cue(500, 1_500), Cue(2_000, 2_500))
        assertEquals(1_000L, AutoSyncAligner.overlap(ref, target, 0))
        assertEquals(1_000L, AutoSyncAligner.overlap(ref, target, -500))
        assertEquals(500L, AutoSyncAligner.overlap(ref, target, 500))
    }
}
