package com.fetch.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingRecheckTest {
    @Test
    fun `caught up series is due again once its deadline passes`() {
        // Checked before the premiere: no next episode, deadline set to the air date.
        // The air date has now passed while Fetch stayed open.
        val due = olderSeedsDueForRecheck(
            processedOlderSeedContentIds = setOf("tt1"),
            negativeResolutionKeys = setOf("tt1|1|8"),
            hasBeenValidated = { true },
            isValidationFresh = { false }
        )

        assertEquals(setOf("tt1"), due)
    }

    @Test
    fun `caught up series stays skipped before its deadline`() {
        val due = olderSeedsDueForRecheck(
            processedOlderSeedContentIds = setOf("tt1"),
            negativeResolutionKeys = setOf("tt1|1|8"),
            hasBeenValidated = { true },
            isValidationFresh = { true }
        )

        assertTrue(due.isEmpty())
    }

    @Test
    fun `series with a next up or no deadline are not rechecked`() {
        val due = olderSeedsDueForRecheck(
            processedOlderSeedContentIds = setOf("has-next-up", "meta-failed"),
            negativeResolutionKeys = setOf("meta-failed|2|3"),
            hasBeenValidated = { it != "meta-failed" },
            isValidationFresh = { false }
        )

        assertTrue(due.isEmpty())
    }

    @Test
    fun `series not processed this session are left to the normal path`() {
        val due = olderSeedsDueForRecheck(
            processedOlderSeedContentIds = emptySet(),
            negativeResolutionKeys = setOf("tt1|1|8|unaired=false"),
            hasBeenValidated = { true },
            isValidationFresh = { false }
        )

        assertTrue(due.isEmpty())
    }

    @Test
    fun `recheck is scheduled for the earliest future deadline`() {
        val deadlines = mapOf("tt1" to 5_000L, "tt2" to 3_000L, "tt3" to 1_000L)

        val recheckAt = nextOlderSeedRecheckAtMs(
            processedOlderSeedContentIds = setOf("tt1", "tt2", "tt3"),
            negativeResolutionKeys = setOf("tt1|1|8", "tt2|2|10", "tt3|1|1"),
            deadlineOf = deadlines::get,
            nowMs = 2_000L
        )

        // tt3's deadline has already passed: this cycle's recheck handles it.
        assertEquals(3_000L, recheckAt)
    }

    @Test
    fun `no recheck is scheduled without a pending caught up series`() {
        assertEquals(
            null,
            nextOlderSeedRecheckAtMs(
                processedOlderSeedContentIds = setOf("has-next-up", "no-deadline"),
                negativeResolutionKeys = setOf("no-deadline|1|2"),
                deadlineOf = { if (it == "has-next-up") 9_000L else null },
                nowMs = 2_000L
            )
        )
    }

    @Test
    fun `cached episode list expires after the ttl`() {
        val cachedAt = 1_000L

        assertTrue(isCwMetaCacheFresh(cachedAt, cachedAt + CW_META_CACHE_TTL_MS - 1))
        assertFalse(isCwMetaCacheFresh(cachedAt, cachedAt + CW_META_CACHE_TTL_MS))
        assertFalse(isCwMetaCacheFresh(null, cachedAt))
    }
}
