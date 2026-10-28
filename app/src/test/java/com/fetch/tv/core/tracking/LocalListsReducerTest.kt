package com.fetch.tv.core.tracking

import com.fetch.tv.data.repository.localLibraryEntries
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalListsReducerTest {
    @Test
    fun `create, rename, reorder and delete keep the other lists as they were`() {
        var lists = LocalListsReducer.create(emptyList(), "a", "  Weekend movies ", null, nowEpochMs = 1)
        lists = LocalListsReducer.create(lists, "b", "Kids' shows", " For Saturday ", nowEpochMs = 2)

        assertEquals(listOf("Weekend movies", "Kids' shows"), lists.map { it.name })
        assertEquals(LocalListKind.CUSTOM, lists.first().kind)
        assertEquals("For Saturday", lists.last().description)

        lists = LocalListsReducer.update(lists, "a", "Friday movies", "", nowEpochMs = 3)
        assertEquals("Friday movies", lists.first().name)
        assertNull(lists.first().description)
        assertEquals(3L, lists.first().updatedAt)

        lists = LocalListsReducer.reorder(lists, listOf("b", "a"))
        assertEquals(listOf("b", "a"), lists.map { it.id })

        lists = LocalListsReducer.delete(lists, "b")
        assertEquals(listOf("a"), lists.map { it.id })
    }

    @Test
    fun `a blank name or an unknown list is refused`() {
        assertTrue(runCatching { LocalListsReducer.create(emptyList(), "a", " ", null, 1) }.isFailure)
        assertTrue(runCatching { LocalListsReducer.delete(emptyList(), "missing") }.isFailure)
    }

    @Test
    fun `a reorder naming only some lists keeps the rest after them`() {
        val lists = listOf(list("a"), list("b"), list("c"))

        assertEquals(listOf("c", "a", "b"), LocalListsReducer.reorder(lists, listOf("c", "missing")).map { it.id })
    }

    @Test
    fun `membership adds once, removes, and leaves unnamed lists alone`() {
        val movie = item("tt1", "movie")
        var lists = listOf(list("a"), list("b"))

        lists = LocalListsReducer.applyMembership(lists, movie, mapOf("a" to true), nowEpochMs = 10)
        lists = LocalListsReducer.applyMembership(lists, movie, mapOf("a" to true), nowEpochMs = 20)

        assertEquals(listOf(10L), lists.first().items.map { it.addedAt })
        assertTrue(lists.last().items.isEmpty())
        assertEquals(
            mapOf("local:list:a" to true, "local:list:b" to false),
            LocalListsReducer.membership(lists, "tt1", "MOVIE")
        )

        lists = LocalListsReducer.applyMembership(lists, movie, mapOf("a" to false), nowEpochMs = 30)
        assertTrue(lists.first().items.isEmpty())
    }

    @Test
    fun `sanitize drops what a damaged file leaves behind`() {
        val duplicate = item("tt1", "movie")
        val sanitized = LocalListsReducer.sanitize(
            listOf(
                null,
                list("a").copy(items = listOf(duplicate, duplicate, item(" ", "movie"), item("tt2", ""))),
                list("a"),
                list(" ")
            )
        )

        assertEquals(listOf("a"), sanitized.map { it.id })
        assertEquals(1, sanitized.single().items.size)
    }

    @Test
    fun `list keys round-trip and nothing else reads as a local list`() {
        assertEquals("abc", localListIdFromKey(localListKey("abc")))
        assertNull(localListIdFromKey(LOCAL_LIBRARY_LIST_KEY))
        assertNull(localListIdFromKey("personal:7"))
        assertFalse(isLocalListKey("local:list:"))
    }

    @Test
    fun `the picker opens in the local library only once the profile has a list`() {
        val localTab = list("a").toListTab()
        val traktTab = LibraryListTab("watchlist", "Watchlist", LibraryListTab.Type.WATCHLIST, trackingProviderId = "trakt")

        assertFalse(usesLibraryListPicker(isLocalSource = true, membershipTabs = listOf(traktTab)))
        assertTrue(usesLibraryListPicker(isLocalSource = true, membershipTabs = listOf(localTab)))
        assertTrue(usesLibraryListPicker(isLocalSource = false, membershipTabs = emptyList()))
    }

    @Test
    fun `library entries carry the saved key and each list a title is on`() {
        val saved = item("tt1", "movie", addedAt = 5)
        val listed = item("tt2", "series", addedAt = 7)
        val entries = localLibraryEntries(
            saved = listOf(saved),
            lists = listOf(
                list("a").copy(items = listOf(saved.copy(addedAt = 9), listed)),
                list("b").copy(items = listOf(listed))
            )
        )

        assertEquals(listOf("tt1", "tt2"), entries.map { it.id })
        assertEquals(setOf(LOCAL_LIBRARY_LIST_KEY, "local:list:a"), entries.first().listKeys)
        assertEquals(9L, entries.first().listedAt)
        assertEquals(setOf("local:list:a", "local:list:b"), entries.last().listKeys)
        assertEquals(
            mapOf(LOCAL_LIBRARY_LIST_KEY to 5L, "local:list:a" to 9L),
            entries.first().listAddedAt
        )
    }

    private fun list(id: String) = LocalList(id = id, name = "List $id")

    private fun item(id: String, type: String, addedAt: Long = 0L) = SavedLibraryItem(
        id = id,
        type = type,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        addedAt = addedAt
    )
}
