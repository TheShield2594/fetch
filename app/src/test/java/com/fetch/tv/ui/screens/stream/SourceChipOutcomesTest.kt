package com.fetch.tv.ui.screens.stream

import com.fetch.tv.domain.model.StreamSourceOutcome
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A chip still loading settles from the outcome its source reported to the search, so a source
 * that answered "nothing" (an addon or a media server, live or replayed from the session cache)
 * ends as "no results" instead of turning red when the search finishes.
 */
class SourceChipOutcomesTest {

    @Test
    fun `an empty source that finished while another was running ends as no results`() {
        val chips = listOf(
            SourceChipItem("Empty", SourceChipStatus.LOADING),
            SourceChipItem("Jellyfin", SourceChipStatus.LOADING),
            SourceChipItem("Slow", SourceChipStatus.LOADING)
        )
        val outcomes = mapOf("Empty" to StreamSourceOutcome.EMPTY, "Jellyfin" to StreamSourceOutcome.EMPTY)

        val settled = settlePendingSourceChips(chips, outcomes)

        assertEquals(
            listOf(SourceChipStatus.NO_RESULTS, SourceChipStatus.NO_RESULTS, SourceChipStatus.LOADING),
            settled.map { it.status }
        )
    }

    @Test
    fun `failed and timed out outcomes keep their own status`() {
        val chips = listOf(
            SourceChipItem("Broken", SourceChipStatus.LOADING),
            SourceChipItem("Dead", SourceChipStatus.LOADING)
        )
        val outcomes = mapOf("Broken" to StreamSourceOutcome.FAILED, "Dead" to StreamSourceOutcome.TIMED_OUT)

        val settled = settlePendingSourceChips(chips, outcomes)

        assertEquals(listOf(SourceChipStatus.ERROR, SourceChipStatus.TIMED_OUT), settled.map { it.status })
    }

    @Test
    fun `a streams outcome leaves the chip for its streams to settle`() {
        val chips = listOf(SourceChipItem("Addon", SourceChipStatus.LOADING))

        assertEquals(chips, settlePendingSourceChips(chips, mapOf("Addon" to StreamSourceOutcome.STREAMS)))
    }

    @Test
    fun `a chip that already settled is not changed by a later outcome`() {
        val chips = listOf(SourceChipItem("Addon", SourceChipStatus.UNPLAYABLE_ONLY))

        assertSame(chips, settlePendingSourceChips(chips, mapOf("Addon" to StreamSourceOutcome.EMPTY)))
    }
}
