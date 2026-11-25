package com.fetch.tv.ui.screens.library

import com.fetch.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.fetch.tv.data.local.CachedNextUpItem
import com.fetch.tv.domain.model.LibraryListTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySelectionTest {

    private fun reduce(vararg actions: LibrarySelectionAction): LibrarySelectionState =
        actions.fold(LibrarySelectionState()) { state, action -> reduceLibrarySelection(state, action) }

    @Test
    fun `enter starts empty and toggle adds then removes`() {
        val entered = reduce(LibrarySelectionAction.Enter)
        assertTrue(entered.active)
        assertEquals(0, entered.count)

        val one = reduceLibrarySelection(entered, LibrarySelectionAction.Toggle("movie:a"))
        assertEquals(setOf("movie:a"), one.selectedKeys)
        val none = reduceLibrarySelection(one, LibrarySelectionAction.Toggle("movie:a"))
        assertTrue(none.active)
        assertTrue(none.selectedKeys.isEmpty())
    }

    @Test
    fun `toggle outside selection mode does nothing`() {
        val state = reduce(LibrarySelectionAction.Toggle("movie:a"))
        assertFalse(state.active)
        assertTrue(state.selectedKeys.isEmpty())
    }

    @Test
    fun `exit clears the selection and enter starts afresh`() {
        val state = reduce(
            LibrarySelectionAction.Enter,
            LibrarySelectionAction.Toggle("movie:a"),
            LibrarySelectionAction.Exit
        )
        assertEquals(LibrarySelectionState(), state)
        assertTrue(reduceLibrarySelection(state, LibrarySelectionAction.Enter).selectedKeys.isEmpty())
    }

    @Test
    fun `retain drops chosen cards that left the grid and keeps the state otherwise`() {
        val chosen = reduce(
            LibrarySelectionAction.Enter,
            LibrarySelectionAction.Toggle("a"),
            LibrarySelectionAction.Toggle("b")
        )
        assertEquals(setOf("b"), reduceLibrarySelection(chosen, LibrarySelectionAction.Retain(setOf("b", "c"))).selectedKeys)
        assertSame(chosen, reduceLibrarySelection(chosen, LibrarySelectionAction.Retain(setOf("a", "b", "c"))))
    }

    @Test
    fun `focus after removal stays on a kept anchor, else moves forward, else back`() {
        val keys = listOf("a", "b", "c", "d")
        assertEquals("a", libraryFocusKeyAfterRemoval(keys, setOf("b"), "a"))
        assertEquals("d", libraryFocusKeyAfterRemoval(keys, setOf("b", "c"), "b"))
        assertEquals("b", libraryFocusKeyAfterRemoval(keys, setOf("c", "d"), "d"))
        assertNull(libraryFocusKeyAfterRemoval(keys, keys.toSet(), "c"))
        assertEquals("c", libraryFocusKeyAfterRemoval(keys, setOf("a", "b"), null))
    }

    private val watchlist = LibraryListTab("trakt:watchlist", "Watchlist", LibraryListTab.Type.WATCHLIST)
    private val personal = LibraryListTab("trakt:list:1", "Favourites", LibraryListTab.Type.PERSONAL)
    private val readOnly = LibraryListTab("trakt:external", "Liked", LibraryListTab.Type.EXTERNAL, isMembershipDestination = false)
    private val moviesOnly = LibraryListTab("trakt:list:2", "Films", LibraryListTab.Type.PERSONAL, supportedContentTypes = setOf("movie"))
    private val watching = LibraryListTab("simkl:watching", "Watching", LibraryListTab.Type.STATUS, trackingProviderId = "SIMKL", selectionGroup = "status")
    private val planned = LibraryListTab("simkl:plantowatch", "Plan to watch", LibraryListTab.Type.STATUS, trackingProviderId = "SIMKL", selectionGroup = "status")

    @Test
    fun `destinations leave out the current list and lists that take no titles`() {
        val tabs = listOf(watchlist, personal, readOnly)
        assertEquals(listOf(personal), libraryBulkDestinations(tabs, "trakt:watchlist", "Library"))
        assertEquals(listOf(watchlist, personal), libraryBulkDestinations(tabs, null, "Library"))
    }

    @Test
    fun `with no lists the activity tabs can still add to this TV's library`() {
        val destinations = libraryBulkDestinations(emptyList(), null, "Library")
        assertEquals(listOf(LOCAL_LIBRARY_LIST_KEY), destinations.map { it.key })
        assertTrue(libraryBulkDestinations(emptyList(), LOCAL_LIBRARY_LIST_KEY, "Library").isEmpty())
    }

    @Test
    fun `current list key falls back to this TV's library`() {
        assertEquals(LOCAL_LIBRARY_LIST_KEY, libraryCurrentListKey(null))
        assertEquals("trakt:list:1", libraryCurrentListKey("trakt:list:1"))
    }

    @Test
    fun `a move keeps every other list and switches the two that change`() {
        val snapshot = mapOf(LOCAL_LIBRARY_LIST_KEY to true, "trakt:watchlist" to true, "trakt:list:1" to false)
        val desired = libraryBulkMembership(snapshot, listOf(watchlist, personal), "movie", addTo = "trakt:list:1", removeFrom = "trakt:watchlist")
        assertEquals(mapOf(LOCAL_LIBRARY_LIST_KEY to true, "trakt:watchlist" to false, "trakt:list:1" to true), desired)
    }

    @Test
    fun `a removal only switches the current list off`() {
        val snapshot = mapOf(LOCAL_LIBRARY_LIST_KEY to true, "trakt:list:1" to true)
        assertEquals(
            mapOf(LOCAL_LIBRARY_LIST_KEY to false, "trakt:list:1" to true),
            libraryBulkMembership(snapshot, emptyList(), "series", addTo = null, removeFrom = LOCAL_LIBRARY_LIST_KEY)
        )
    }

    @Test
    fun `a list that can't hold the title skips it`() {
        assertNull(libraryBulkMembership(emptyMap(), listOf(moviesOnly), "series", addTo = "trakt:list:2", removeFrom = null))
        assertNull(libraryBulkMembership(emptyMap(), listOf(personal), "movie", addTo = "unknown", removeFrom = null))
    }

    @Test
    fun `a status list switches off the rest of its group`() {
        val snapshot = mapOf("simkl:watching" to true, "simkl:plantowatch" to false)
        assertEquals(
            mapOf("simkl:watching" to false, "simkl:plantowatch" to true),
            libraryBulkMembership(snapshot, listOf(watching, planned), "series", addTo = "simkl:plantowatch", removeFrom = null)
        )
    }

    private fun nextUp(id: String, alert: Boolean, newSeason: Boolean = false, aired: Boolean = true) = CachedNextUpItem(
        contentId = id, contentType = "series", name = id, poster = null, backdrop = null, logo = null,
        videoId = "$id:1:1", season = 1, episode = 1, episodeTitle = null, thumbnail = null,
        hasAired = aired, lastWatched = 0L, sortTimestamp = 0L,
        isReleaseAlert = alert, isNewSeasonRelease = newSeason
    )

    @Test
    fun `badges come from aired release alerts only`() {
        val badges = libraryNewEpisodeBadges(
            listOf(
                nextUp("tt1", alert = true),
                nextUp("tt2", alert = true, newSeason = true),
                nextUp("tt3", alert = false),
                nextUp("tt4", alert = true, aired = false)
            )
        )
        assertEquals(
            mapOf("tt1" to LibraryNewEpisodeBadge.NEW_EPISODE, "tt2" to LibraryNewEpisodeBadge.NEW_SEASON),
            badges
        )
    }

    @Test
    fun `a show listed twice keeps the new season badge`() {
        val badges = libraryNewEpisodeBadges(
            listOf(nextUp("tt1", alert = true, newSeason = true), nextUp("tt1", alert = true))
        )
        assertEquals(LibraryNewEpisodeBadge.NEW_SEASON, badges["tt1"])
    }

    @Test
    fun `badge lookup falls back to the imdb id`() {
        val badges = mapOf("tt9" to LibraryNewEpisodeBadge.NEW_EPISODE)
        assertEquals(LibraryNewEpisodeBadge.NEW_EPISODE, badges.badgeFor("tmdb:9", "tt9"))
        assertNull(badges.badgeFor("tmdb:9", null))
        assertNull(badges.badgeFor("tmdb:9", ""))
    }
}
