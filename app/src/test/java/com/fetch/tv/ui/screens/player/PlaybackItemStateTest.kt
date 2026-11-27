package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackItemStateTest {

    private fun item() = PlaybackItemState(PlaybackItemKey("tt1:1:1", 1, 1), generation = 1L, parent = null)

    @Test
    fun retryOfTheSameStream_keepsItsBudget() {
        val item = item()
        item.attemptFor("https://a").timeoutRecoveryAttempts = 2
        item.attemptFor("https://a").hasRetriedAfter416 = true
        assertEquals(2, item.attemptFor("https://a").timeoutRecoveryAttempts)
        assertTrue(item.attemptFor("https://a").hasRetriedAfter416)
    }

    @Test
    fun anotherStream_getsAFreshBudget() {
        val item = item()
        val first = item.attemptFor("https://a")
        first.timeoutRecoveryAttempts = 2
        val second = item.attemptFor("https://b")
        assertNotSame(first, second)
        assertEquals(0, second.timeoutRecoveryAttempts)
    }

    @Test
    fun resetAttempt_refillsTheSameStream() {
        val item = item()
        item.attemptFor("https://a").hasRetriedAfterUnexpectedNpe = true
        item.resetAttempt()
        assertFalse(item.attemptFor("https://a").hasRetriedAfterUnexpectedNpe)
        assertSame(item.attempt, item.attemptFor("https://a"))
    }

    @Test
    fun completionFlags_startClear_andCancelEndsTheItemsWork() {
        val item = item()
        assertFalse(item.hasMarkedCompleted)
        assertFalse(item.completionIsFinal)
        item.cancel()
        assertTrue(item.job.isCancelled)
    }
}
