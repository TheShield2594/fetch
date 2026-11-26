package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSpotlightTest {

    private fun item(id: String, backdrop: Boolean = true, logo: Boolean = true) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = "poster/$id",
        posterShape = PosterShape.POSTER,
        background = if (backdrop) "backdrop/$id" else null,
        logo = if (logo) "logo/$id" else null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun row(id: String, items: List<MetaPreview>) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon",
        catalogId = id,
        catalogName = id,
        type = ContentType.MOVIE,
        items = items
    )

    @Test
    fun `the same day gives the same pick and another day can differ`() {
        val rows = listOf(row("a", (1..15).map { item("a$it") }), row("b", (1..15).map { item("b$it") }))
        val today = pickSpotlight(rows, isWatched = { false }, day = 20_000L).map { it.item.id }
        assertEquals(today, pickSpotlight(rows, isWatched = { false }, day = 20_000L).map { it.item.id })
        val week = (20_001L..20_007L).map { day -> pickSpotlight(rows, { false }, day).map { it.item.id } }
        assertTrue(week.any { it != today })
        assertEquals(SPOTLIGHT_ITEM_COUNT, today.size)
    }

    @Test
    fun `watched titles and repeats across rows are left out`() {
        val shared = item("shared")
        val rows = listOf(row("a", listOf(shared, item("seen"), item("a1"))), row("b", listOf(shared, item("b1"))))
        val ids = pickSpotlight(rows, isWatched = { it.id == "seen" }, day = 1L).map { it.item.id }
        assertFalse("seen" in ids)
        assertEquals(1, ids.count { it == "shared" })
        assertEquals(setOf("shared", "a1", "b1"), ids.toSet())
    }

    @Test
    fun `titles with a backdrop and a logo come first`() {
        val plain = (1..10).map { item("plain$it", backdrop = false, logo = false) }
        val featured = (1..3).map { item("featured$it") }
        val picked = pickSpotlight(listOf(row("a", plain + featured)), { false }, day = 7L).map { it.item.id }
        assertEquals(featured.map { it.id }.toSet(), picked.take(3).toSet())
    }

    @Test
    fun `only the first source rows are used`() {
        val rows = (1..5).map { r -> row("r$r", listOf(item("r$r-1"))) }
        val ids = pickSpotlight(rows, { false }, day = 3L).map { it.item.id }.toSet()
        assertEquals((1..SPOTLIGHT_SOURCE_ROWS).map { "r$it-1" }.toSet(), ids)
    }
}
