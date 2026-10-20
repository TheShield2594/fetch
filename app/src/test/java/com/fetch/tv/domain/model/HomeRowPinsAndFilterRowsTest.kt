package com.fetch.tv.domain.model

import com.fetch.tv.data.mediaserver.ServerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowPinsAndFilterRowsTest {
    @Test
    fun `pinned keys move to the front in pin order`() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), pinnedFirst(order, listOf("c", "a")))
    }

    @Test
    fun `unavailable pins are skipped without disturbing the order`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), pinnedFirst(order, listOf("gone", "b")))
        assertEquals(order, pinnedFirst(order, listOf("gone")))
    }

    @Test
    fun `addon filter row joins its addon as a catalog with the source type`() {
        val addon = addon(
            id = "cinemeta",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Popular",
                    extra = listOf(CatalogExtra("genre", options = listOf("Sci-Fi")), CatalogExtra("skip"))
                )
            )
        )
        val row = FilterRow("r1", "Sci-fi", FilterRowSource.AddonCatalog("cinemeta", "movie", "top"))

        val catalogs = listOf(addon).withFilterRows(listOf(row)).single().catalogs

        val added = catalogs.single { it.id == row.catalogId }
        assertEquals("Sci-fi", added.name)
        assertEquals("movie", added.apiType)
        assertEquals(listOf("skip"), added.extra.map { it.name })
        assertTrue(added.showInHome)
        assertTrue(isFilterRowKey(catalogRowLegacyKey("cinemeta", "movie", added.id)))
    }

    @Test
    fun `server filter row joins its server and rows without a source are dropped`() {
        val server = addon(
            id = ServerCatalog.addonId("conn"),
            catalogs = listOf(CatalogDescriptor(type = ContentType.SERIES, id = "lib", name = "Shows"))
        )
        val rows = listOf(
            FilterRow("r1", "Unwatched shows", FilterRowSource.ServerLibrary("conn", "lib")),
            FilterRow("r2", "Gone", FilterRowSource.ServerLibrary("conn", "missing"))
        )

        val ids = listOf(server).withFilterRows(rows).single().catalogs.map { it.id }

        assertEquals(listOf("lib", filterRowCatalogId("r1")), ids)
        assertTrue(ServerCatalog.isServerKey(catalogRowLegacyKey("cinemeta", "movie", filterRowCatalogId("r1"))))
    }

    private fun addon(id: String, catalogs: List<CatalogDescriptor>) = Addon(
        id = id,
        name = id,
        version = "1",
        description = null,
        logo = null,
        baseUrl = "https://$id.example",
        catalogs = catalogs,
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )
}
