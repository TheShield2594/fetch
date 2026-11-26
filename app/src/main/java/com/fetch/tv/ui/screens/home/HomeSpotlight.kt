package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.MetaPreview
import kotlin.random.Random

/** Cards in the Spotlight row (#813). */
internal const val SPOTLIGHT_ITEM_COUNT = 6

/** Catalog rows the Spotlight picks from: the user's first ones, in their Home order. */
internal const val SPOTLIGHT_SOURCE_ROWS = 3

/** Titles looked at per source row, so a long catalog doesn't drown the others. */
internal const val SPOTLIGHT_ITEMS_PER_SOURCE_ROW = 15

/** While focus rests on the Spotlight untouched, the hero moves to its next card this often. */
internal const val SPOTLIGHT_ADVANCE_MS = 10_000L

/** A Spotlight pick and the row it came from, which its card opens through. */
internal data class SpotlightPick(val row: CatalogRow, val item: MetaPreview)

/**
 * The Spotlight's titles: from the first [SPOTLIGHT_SOURCE_ROWS] catalog rows, without titles
 * already watched or repeated across rows, preferring ones with a backdrop and a logo (the hero
 * shows both). The pick is shuffled by [day], so it changes once a day and stays put within one.
 */
internal fun pickSpotlight(
    rows: List<CatalogRow>,
    isWatched: (MetaPreview) -> Boolean,
    day: Long,
    count: Int = SPOTLIGHT_ITEM_COUNT
): List<SpotlightPick> {
    val seen = HashSet<String>()
    val candidates = rows
        .asSequence()
        .filter { it.items.isNotEmpty() }
        .take(SPOTLIGHT_SOURCE_ROWS)
        .flatMap { row -> row.items.asSequence().take(SPOTLIGHT_ITEMS_PER_SOURCE_ROW).map { SpotlightPick(row, it) } }
        .filter { pick -> !pick.item.id.startsWith("__placeholder_") }
        .filter { pick -> seen.add("${pick.item.apiType.lowercase()}:${pick.item.id}") }
        .filterNot { pick -> isWatched(pick.item) }
        .toList()
    val random = Random(day)
    val (featured, rest) = candidates.partition { pick -> pick.item.hasBackdrop() && !pick.item.logo.isNullOrBlank() }
    val (withBackdrop, plain) = rest.partition { it.item.hasBackdrop() }
    return (featured.shuffled(random) + withBackdrop.shuffled(random) + plain.shuffled(random))
        .take(count)
}

/** Real wide art; [MetaPreview.backdropUrl] falls back to the poster, which a wide card crops badly. */
private fun MetaPreview.hasBackdrop(): Boolean = !background.isNullOrBlank() || !landscapePoster.isNullOrBlank()

/** Days since the epoch in the device's time zone, so the Spotlight turns over at local midnight. */
internal fun spotlightDay(nowMs: Long = System.currentTimeMillis()): Long {
    val offsetMs = java.util.TimeZone.getDefault().getOffset(nowMs)
    return Math.floorDiv(nowMs + offsetMs, 86_400_000L)
}
