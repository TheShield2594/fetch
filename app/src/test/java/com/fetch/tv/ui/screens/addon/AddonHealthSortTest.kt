package com.fetch.tv.ui.screens.addon

import com.fetch.tv.core.streams.AddonHealth
import com.fetch.tv.core.streams.AddonHealthSample
import com.fetch.tv.core.streams.AddonRequestOutcome
import com.fetch.tv.domain.model.Addon
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonHealthSortTest {

    private fun addon(name: String) = Addon(
        id = name,
        name = name,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://$name.example",
        catalogs = emptyList(),
        types = emptyList(),
        resources = emptyList()
    )

    private fun row(name: String, vararg samples: AddonHealthSample): AddonHealthRow =
        AddonHealthRow(
            addon(name),
            if (samples.isEmpty()) null else samples.fold(AddonHealth(name)) { acc, s -> acc.plus(s, error = null) }
        )

    private val fast = row("fast", AddonHealthSample(AddonRequestOutcome.SUCCESS, 200L, atMs = 30L))
    private val slow = row(
        "Slow",
        AddonHealthSample(AddonRequestOutcome.SUCCESS, 9_000L, atMs = 20L),
        AddonHealthSample(AddonRequestOutcome.ERROR, 100L, atMs = 40L)
    )
    private val dead = row("dead", AddonHealthSample(AddonRequestOutcome.TIMEOUT, 25_000L, atMs = 50L))
    private val unused = row("unused")
    private val rows = listOf(unused, fast, slow, dead)

    private fun List<AddonHealthRow>.names() = map { it.addon.name }

    @Test
    fun `name sorts case-insensitively`() {
        assertEquals(listOf("dead", "fast", "Slow", "unused"), sortAddonHealthRows(rows, AddonHealthSort.NAME).names())
    }

    @Test
    fun `failure rate puts the most failing first and unused last`() {
        assertEquals(
            listOf("dead", "Slow", "fast", "unused"),
            sortAddonHealthRows(rows, AddonHealthSort.FAILURE_RATE).names()
        )
    }

    @Test
    fun `latency puts the slowest first and never-answered after answered`() {
        assertEquals(
            listOf("Slow", "fast", "dead", "unused"),
            sortAddonHealthRows(rows, AddonHealthSort.LATENCY).names()
        )
    }

    @Test
    fun `last success puts the longest-silent first`() {
        assertEquals(
            listOf("dead", "Slow", "fast", "unused"),
            sortAddonHealthRows(rows, AddonHealthSort.LAST_SUCCESS).names()
        )
    }
}
