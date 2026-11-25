package com.fetch.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpDismissalsTest {

    private val dismissal = NextUpDismissal(
        contentId = "tt1",
        seedSeason = 1,
        seedEpisode = 4,
        season = 1,
        episode = 5,
        hadAired = true,
        dismissedAtMs = 42L,
        title = "A | show"
    )

    @Test
    fun `round trips through storage`() {
        assertEquals(dismissal, NextUpDismissal.decode(dismissal.encode()))
        assertEquals("tt1", NextUpDismissal.contentIdOf(dismissal.encode()))
    }

    @Test
    fun `legacy keys hide by content id`() {
        val stored = NextUpDismissals.fromStored(setOf("tt1", "tt2|1|3"))
        assertTrue(stored.hides("tt1", 9, 9, 9, 10, true))
        assertTrue(stored.hides("tt2", 1, 1))
        assertFalse(stored.hides("tt3", 1, 1))
        assertEquals("tt2", NextUpDismissal.contentIdOf("tt2|1|3"))
    }

    @Test
    fun `same position stays hidden`() {
        val stored = dismissals(dismissal)
        assertTrue(stored.hides("tt1", 1, 4, 1, 5, true))
        assertTrue(stored.hidesSeed("tt1", 1, 4))
    }

    @Test
    fun `newer history brings the show back`() {
        val stored = dismissals(dismissal)
        assertFalse(stored.hides("tt1", 1, 6, 1, 7, true))
        assertFalse(stored.hidesSeed("tt1", 1, 6))
    }

    @Test
    fun `a later offered episode brings the show back`() {
        assertFalse(dismissals(dismissal).hides("tt1", 1, 4, 2, 1, true))
    }

    @Test
    fun `a dismissed unaired episode comes back once it airs`() {
        val stored = dismissals(dismissal.copy(hadAired = false))
        assertTrue(stored.hides("tt1", 1, 4, 1, 5, false))
        assertFalse(stored.hides("tt1", 1, 4, 1, 5, true))
        // The seed alone can't tell whether it aired, so it isn't filtered early.
        assertFalse(stored.hidesSeed("tt1", 1, 4))
    }

    @Test
    fun `a new dismissal replaces a legacy key for the same show`() {
        val stored = NextUpDismissals.fromStored(setOf("tt1", dismissal.encode()))
        assertEquals(dismissal, stored.byContentId["tt1"])
    }

    private fun dismissals(vararg entries: NextUpDismissal) =
        NextUpDismissals(entries.associateBy { it.contentId })
}
