package com.fetch.tv.ui.screens.stream

import com.fetch.tv.core.streams.AddonHealth
import com.fetch.tv.core.streams.AddonHealthSample
import com.fetch.tv.core.streams.AddonRequestKind
import com.fetch.tv.core.streams.AddonRequestOutcome
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the search ends, a chip still loading takes the outcome its addon already recorded, so an
 * addon that answered "nothing" ends as "no results" even if its live event was missed.
 */
class SourceChipOutcomesTest {

    private val searchStartMs = 1_000L

    @Test
    fun `an empty addon that finished while another was running ends as no results`() {
        val chips = listOf(
            SourceChipItem("Empty", SourceChipStatus.LOADING),
            SourceChipItem("Slow", SourceChipStatus.SUCCESS)
        )
        val health = mapOf(
            "Empty" to health("Empty", AddonRequestOutcome.EMPTY, atMs = 1_200L),
            "Slow" to health("Slow", AddonRequestOutcome.SUCCESS, atMs = 9_000L)
        )

        val settled = settlePendingSourceChips(chips, health, searchStartMs)

        assertEquals(SourceChipStatus.NO_RESULTS, settled.single { it.name == "Empty" }.status)
        assertEquals(SourceChipStatus.SUCCESS, settled.single { it.name == "Slow" }.status)
    }

    @Test
    fun `failed and timed out outcomes keep their own status`() {
        val chips = listOf(
            SourceChipItem("Broken", SourceChipStatus.LOADING),
            SourceChipItem("Dead", SourceChipStatus.LOADING)
        )
        val health = mapOf(
            "Broken" to health("Broken", AddonRequestOutcome.ERROR, atMs = 1_500L),
            "Dead" to health("Dead", AddonRequestOutcome.TIMEOUT, atMs = 1_500L)
        )

        val settled = settlePendingSourceChips(chips, health, searchStartMs)

        assertEquals(listOf(SourceChipStatus.ERROR, SourceChipStatus.TIMED_OUT), settled.map { it.status })
    }

    @Test
    fun `an outcome from an earlier search or another request kind does not settle the chip`() {
        val chips = listOf(SourceChipItem("Addon", SourceChipStatus.LOADING))
        val health = mapOf(
            "Addon" to AddonHealth(
                addonName = "Addon",
                samples = listOf(
                    AddonHealthSample(AddonRequestOutcome.EMPTY, latencyMs = 100L, atMs = 500L),
                    AddonHealthSample(AddonRequestOutcome.EMPTY, 100L, atMs = 1_500L, kind = AddonRequestKind.CATALOG)
                )
            )
        )

        val settled = settlePendingSourceChips(chips, health, searchStartMs)

        assertEquals(SourceChipStatus.LOADING, settled.single().status)
    }

    @Test
    fun `a success outcome leaves the chip for its streams to settle`() {
        val chips = listOf(SourceChipItem("Addon", SourceChipStatus.LOADING))
        val health = mapOf("Addon" to health("Addon", AddonRequestOutcome.SUCCESS, atMs = 1_500L))

        assertEquals(chips, settlePendingSourceChips(chips, health, searchStartMs))
    }

    private fun health(name: String, outcome: AddonRequestOutcome, atMs: Long) = AddonHealth(
        addonName = name,
        samples = listOf(AddonHealthSample(outcome, latencyMs = 100L, atMs = atMs))
    )
}
