package com.fetch.desktop.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    private val catalogs = listOf("a", "b", "c")

    @Test
    fun `without a saved order collections follow the catalogs`() {
        assertEquals(listOf("a", "b", "c", "collection_x"), HomeLayout.middle(catalogs, listOf("x"), emptyList()))
    }

    @Test
    fun `a saved order places collections among the rows`() {
        val order = listOf("a", "collection_x", "b", "c")
        assertEquals(order, HomeLayout.middle(catalogs, listOf("x"), order))
    }

    @Test
    fun `rows missing from the saved order stay after their neighbours`() {
        // "d" is new since the order was saved; it stays after "c".
        assertEquals(listOf("collection_x", "a", "b", "c", "d"), HomeLayout.middle(catalogs + "d", listOf("x"), listOf("collection_x", "a", "b", "c")))
    }

    @Test
    fun `pinned catalogs still lead`() {
        val order = listOf("collection_x", "a", "b", "c")
        assertEquals(listOf("c", "collection_x", "a", "b"), HomeLayout.middle(listOf("c", "a", "b"), listOf("x"), order, pinnedCatalogs = listOf("c")))
    }

    @Test
    fun `following addon order keeps catalogs put and moves only collections`() {
        // Saved with the catalogs reordered; the catalogs keep addon order, the collection stays after "b".
        val order = listOf("c", "b", "collection_x", "a")
        assertEquals(listOf("a", "b", "collection_x", "c"), HomeLayout.middle(catalogs, listOf("x"), order, followAddonsOrder = true))
        assertEquals(listOf("collection_x", "a", "b", "c"), HomeLayout.middle(catalogs, listOf("x"), listOf("collection_x", "a"), followAddonsOrder = true))
    }

    @Test
    fun `rows put pinned collections first, then recommendations, and hide hidden collections`() {
        val rows = HomeLayout.rows(
            catalogs = catalogs,
            collections = listOf(HomeLayout.Collection("p", pinToTop = true), HomeLayout.Collection("x", false), HomeLayout.Collection("h", false)),
            becauseYouWatched = listOf("tt1"),
            serverFilters = listOf("f"),
            servers = listOf("s"),
            order = emptyList(),
            pinnedCatalogs = emptyList(),
            hidden = setOf("collection_h"),
            followAddonsOrder = false,
            kids = false
        )
        assertEquals(
            listOf("collection_p", "byw|tt1", "a", "b", "c", "collection_x", "server-filter|f", "server|s"),
            rows.map { it.key }
        )
    }

    @Test
    fun `a kids Home has no recommendations and six rows at most`() {
        val rows = HomeLayout.rows(
            catalogs = (1..9).map { "c$it" },
            collections = emptyList(),
            becauseYouWatched = listOf("tt1"),
            serverFilters = emptyList(),
            servers = emptyList(),
            order = emptyList(),
            pinnedCatalogs = emptyList(),
            hidden = emptySet(),
            followAddonsOrder = false,
            kids = true
        )
        assertEquals((1..6).map { "c$it" }, rows.map { it.key })
    }

    @Test
    fun `move shifts one row and stops at the ends`() {
        val current = listOf("a", "collection_x", "b")
        assertEquals(listOf("collection_x", "a", "b"), HomeLayout.move(current, "collection_x", -1))
        assertEquals(current, HomeLayout.move(current, "a", -1))
        assertEquals(listOf("a", "b", "collection_x"), HomeLayout.move(current, "collection_x", 1))
    }
}
