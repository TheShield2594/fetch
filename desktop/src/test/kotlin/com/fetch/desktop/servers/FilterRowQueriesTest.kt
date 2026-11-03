package com.fetch.desktop.servers

import com.fetch.desktop.FilterRow
import com.fetch.desktop.FilterRowFilters
import com.fetch.desktop.FilterRowSource
import com.fetch.desktop.FilterRowsFile
import com.fetch.desktop.RowSort
import com.fetch.desktop.RowWatched
import com.fetch.desktop.filterRowsFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FilterRowQueriesTest {

    private val filters = FilterRowFilters(
        genre = "Sci-Fi & Fantasy", yearFrom = 1999, yearTo = 2011, watched = RowWatched.UNWATCHED,
        minRating = 7.0, sort = RowSort.RATING, descending = true
    )

    @Test
    fun `Jellyfin gets the TV's filter query`() = runBlocking {
        val fake = FakeServerHttp { 200 to """{"Items":[],"TotalRecordCount":0}""" }
        val account = ServerAccount(
            id = "acc-1", kind = ServerKind.JELLYFIN, name = "Den", address = "http://den:8096", serverId = "s",
            userId = "u1", userName = "bob", token = "t", libraries = listOf(ServerLib("lib-m", "Movies", "movie"))
        )
        MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev", "box").filteredPage(account, account.libraries[0], filters, 40, 20)
        val sent = fake.sent.single()
        assertEquals("Sci-Fi & Fantasy", sent.query("genres"))
        assertEquals("1999-01-01T00:00:00Z", sent.query("minPremiereDate"))
        assertEquals("2011-12-31T23:59:59Z", sent.query("maxPremiereDate"))
        assertEquals("false", sent.query("isPlayed"))
        assertEquals("7.0", sent.query("minCommunityRating"))
        assertEquals("CommunityRating,SortName", sent.query("sortBy"))
        assertEquals("Descending", sent.query("sortOrder"))
        assertEquals("lib-m", sent.query("parentId"))
        assertEquals("40", sent.query("startIndex"))
    }

    @Test
    fun `Plex filters by the genre's key, and an unknown genre has nothing`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/library/sections/1/genre" -> 200 to """{"MediaContainer":{"Directory":[{"key":"512","title":"Sci-Fi & Fantasy"},{"key":"7","title":"Drama"}]}}"""
                else -> 200 to """{"MediaContainer":{"size":0,"totalSize":0,"Metadata":[]}}"""
            }
        }
        val account = ServerAccount(
            id = "acc-p", kind = ServerKind.PLEX, name = "P", address = "https://plex:32400", serverId = "m",
            userId = "1", userName = "bob", token = "t", libraries = listOf(ServerLib("1", "Movies", "movie"))
        )
        val client = PlexServerClient(fake.client, "dev")
        assertEquals(listOf("Sci-Fi & Fantasy", "Drama"), client.genres(account, account.libraries[0]))
        client.filteredPage(account, account.libraries[0], filters, 0, 20)
        val sent = fake.sentTo("/library/sections/1/all").single()
        assertEquals("512", sent.query("genre"))
        assertEquals("1998", sent.query("year>>"))
        assertEquals("2012", sent.query("year<<"))
        assertEquals("1", sent.query("unwatched"))
        assertEquals("audienceRating:desc", sent.query("sort"))
        assertEquals("1", fake.sentTo("/library/sections/1/genre").size.toString())

        val none = client.filteredPage(account, account.libraries[0], filters.copy(genre = "Westerns"), 0, 20)
        assertEquals(0, none.total)
        assertEquals("no request for a genre the library doesn't have", 1, fake.sentTo("/library/sections/1/all").size)
    }

    @Test
    fun `filter rows are saved and read back`() {
        val store = filterRowsFile(Files.createTempDirectory("fr").toFile())
        val rows = listOf(
            FilterRow("a", "Unwatched sci-fi", FilterRowSource.ServerLibrary("acc-1", "lib-m"), filters),
            FilterRow("b", "Horror", FilterRowSource.AddonCatalog("com.linvo.cinemeta", "movie", "top"), FilterRowFilters(genre = "Horror"))
        )
        store.save(FilterRowsFile(rows))
        assertEquals(rows, store.load().rows)
        assertTrue(store.load().rows[1].source is FilterRowSource.AddonCatalog)
        assertNull(FilterRowFilters().genre)
    }
}
