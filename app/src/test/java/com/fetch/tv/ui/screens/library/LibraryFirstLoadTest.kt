package com.fetch.tv.ui.screens.library

import com.fetch.tv.domain.model.LibrarySourceMode
import com.fetch.tv.ui.screens.home.HeroBackdropState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sync shows the Library's skeleton only until the source's first answer (#846); #847's hand-off. */
class LibraryFirstLoadTest {

    @Test
    fun `an empty library syncing for the first time is loading, and later syncs are not`() {
        val answered = mutableSetOf<Pair<Int, LibrarySourceMode>>()
        val synced = mutableSetOf<Pair<Int, LibrarySourceMode>>()
        val trakt = 1 to LibrarySourceMode.TRAKT
        assertTrue(libraryFirstLoadPending(isSyncing = true, isEmpty = true, source = trakt, answered = answered, synced = synced))
        // The first sync ends with nothing on the watchlist: that is the empty state from now on.
        assertFalse(libraryFirstLoadPending(isSyncing = false, isEmpty = true, source = trakt, answered = answered, synced = synced))
        assertFalse(libraryFirstLoadPending(isSyncing = true, isEmpty = true, source = trakt, answered = answered, synced = synced))
    }

    @Test
    fun `idle and empty before the first sync is not an answer`() {
        val answered = mutableSetOf<Pair<Int, LibrarySourceMode>>()
        val synced = mutableSetOf<Pair<Int, LibrarySourceMode>>()
        val trakt = 1 to LibrarySourceMode.TRAKT
        // The repository's first emission: not syncing yet, nothing loaded.
        assertFalse(libraryFirstLoadPending(isSyncing = false, isEmpty = true, source = trakt, answered = answered, synced = synced))
        // The first sync then starts: still the loading state, not the empty one.
        assertTrue(libraryFirstLoadPending(isSyncing = true, isEmpty = true, source = trakt, answered = answered, synced = synced))
    }

    @Test
    fun `another profile or source waits for its own first answer`() {
        val answered = mutableSetOf(1 to LibrarySourceMode.TRAKT)
        assertTrue(libraryFirstLoadPending(isSyncing = true, isEmpty = true, source = 2 to LibrarySourceMode.TRAKT, answered = answered))
        assertTrue(libraryFirstLoadPending(isSyncing = true, isEmpty = true, source = 1 to LibrarySourceMode.SIMKL, answered = answered))
    }

    @Test
    fun `titles already there are never hidden behind a skeleton`() {
        val answered = mutableSetOf<Pair<Int, LibrarySourceMode>>()
        assertFalse(libraryFirstLoadPending(isSyncing = true, isEmpty = false, source = 1 to LibrarySourceMode.MDBLIST, answered = answered))
    }

    @Test
    fun `a hand-off to Detail no longer becomes Home's backdrop`() {
        HeroBackdropState.showOnHome("home-art")
        HeroBackdropState.update("library-art")
        assertEquals("library-art", HeroBackdropState.consumeAndClear())
        assertNull(HeroBackdropState.consumeAndClear())
        assertEquals("home-art", HeroBackdropState.lastDisplayedUrl)
    }
}
