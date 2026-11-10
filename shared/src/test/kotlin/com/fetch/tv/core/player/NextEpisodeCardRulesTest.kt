package com.fetch.tv.core.player

import com.fetch.tv.core.player.NextEpisodeCardRules.Credits
import com.fetch.tv.core.player.NextEpisodeCardRules.PostCreditsScene
import com.fetch.tv.core.player.NextEpisodeCardRules.Threshold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeCardRulesTest {
    private val duration = 2_400.0
    private val percent99 = Threshold.Percent(99f)

    private fun show(position: Double, credits: List<Credits> = emptyList(), scene: PostCreditsScene? = null, threshold: Threshold = percent99) =
        NextEpisodeCardRules.shouldShow(position, duration, credits, scene, threshold)

    @Test
    fun `without credits the threshold applies`() {
        assertFalse(show(2_370.0))
        assertTrue(show(2_376.0))
        assertTrue(show(2_280.0, threshold = Threshold.MinutesBeforeEnd(2f)))
        assertFalse(show(2_279.0, threshold = Threshold.MinutesBeforeEnd(2f)))
    }

    @Test
    fun `settings outside their range are clamped`() {
        // 50 % reads as 97 %, 10 minutes as 3.5.
        assertFalse(show(1_300.0, threshold = Threshold.Percent(50f)))
        assertEquals(210.0, NextEpisodeCardRules.secondsFromEnd(duration, Threshold.MinutesBeforeEnd(10f)), 0.0)
    }

    @Test
    fun `credits near the end show the card when they start`() {
        val credits = listOf(Credits(2_300.0, 2_398.0))
        assertFalse(show(2_299.0, credits))
        assertTrue(show(2_300.0, credits))
    }

    @Test
    fun `credits well before the end leave it to the threshold`() {
        val credits = listOf(Credits(2_000.0, 2_100.0))
        assertFalse(show(2_050.0, credits))
        assertTrue(show(2_376.0, credits))
    }

    @Test
    fun `an explicit post-credits scene holds the card until it ends`() {
        val credits = listOf(Credits(2_200.0, 2_300.0))
        val scene = PostCreditsScene(2_300.0, 2_390.0, guessed = false)
        assertFalse(show(2_380.0, credits, scene))
        assertTrue(show(2_390.0, credits, scene))
    }

    @Test
    fun `a guessed scene waits for the threshold, or for the end when shorter than its window`() {
        val credits = listOf(Credits(2_200.0, 2_300.0))
        assertFalse(show(2_370.0, credits, PostCreditsScene(2_300.0, duration, guessed = true)))
        assertTrue(show(2_376.0, credits, PostCreditsScene(2_300.0, duration, guessed = true)))
        // 10 s of scene against a 24 s window: no card, autoplay takes over at the end.
        assertFalse(show(2_399.0, listOf(Credits(2_300.0, 2_390.0)), PostCreditsScene(2_390.0, duration, guessed = true)))
    }

    @Test
    fun `no card without a duration or past a stale one`() {
        assertFalse(NextEpisodeCardRules.shouldShow(100.0, 0.0, emptyList(), null, percent99))
        assertFalse(show(duration + 2.0))
    }

    @Test
    fun `empty credits segments are ignored`() {
        assertFalse(show(2_300.0, listOf(Credits(2_300.0, 2_300.0))))
    }
}
