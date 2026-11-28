package com.fetch.tv.ui.screens.library

import com.fetch.tv.data.local.BestMatchCacheDataStore
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Library cards' progress bar, Ready chip and Select all (#854, #855, #856). */
class LibraryCardExtrasTest {

    private fun progress(id: String, lastWatched: Long, position: Long, type: String = "movie", videoId: String = id) =
        WatchProgress(
            contentId = id,
            contentType = type,
            name = id,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = null,
            episode = null,
            episodeTitle = null,
            position = position,
            duration = 1_000L,
            lastWatched = lastWatched
        )

    private fun entry(id: String, type: String = "movie") = LibraryEntry(
        id = id, type = type, name = id, poster = null, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
    )

    @Test
    fun `progress takes each title's latest play and leaves out titles not started`() {
        val map = libraryProgressByContentId(
            listOf(
                progress("a", lastWatched = 1L, position = 900L),
                progress("a", lastWatched = 2L, position = 250L),
                progress("b", lastWatched = 1L, position = 0L)
            )
        )
        assertEquals(mapOf("a" to 0.25f), map)
    }

    @Test
    fun `a saved movie gets its ready labels and a series none`() {
        val labels = mapOf(BestMatchCacheDataStore.contentKey("movie", "tt1") to listOf("4K", "DV", "Atmos"))
        assertEquals(listOf("4K", "DV", "Atmos"), libraryEntryReadyLabels(entry("tt1"), labels))
        assertNull(libraryEntryReadyLabels(entry("tt2"), labels))
        assertNull(libraryEntryReadyLabels(entry("tt1", type = "series"), labels))
    }

    @Test
    fun `an in progress card is ready by the video it resumes`() {
        val resume = progress("tt9", lastWatched = 1L, position = 100L, type = "series", videoId = "tt9:1:2")
        val item = libraryInProgressItems(listOf(resume)).single()
        assertEquals(resume, item.resume)
        val labels = mapOf(BestMatchCacheDataStore.contentKey("series", "tt9:1:2") to emptyList<String>())
        assertEquals(emptyList<String>(), libraryActivityReadyLabels(item, labels))
        assertNull(libraryActivityReadyLabels(item.copy(resume = null), labels))
    }

    @Test
    fun `the chip shows the picture and HDR labels, else Ready`() {
        assertEquals("4K DV", libraryReadyChipText(listOf("4K", "DV", "Atmos"), "Ready"))
        assertEquals("1080p", libraryReadyChipText(listOf("1080p"), "Ready"))
        assertEquals("Ready", libraryReadyChipText(emptyList(), "Ready"))
    }

    @Test
    fun `upcoming and stats stay out of the tab row, which holds only grid tabs`() {
        val rowTabs = LibraryTab.entries.filter { !it.opensPage }
        assertEquals(
            listOf(LibraryTab.WATCHLIST, LibraryTab.IN_PROGRESS, LibraryTab.HISTORY, LibraryTab.LISTS, LibraryTab.SERVERS),
            rowTabs
        )
        assertTrue(LibraryTab.UPCOMING.opensPage)
        assertTrue(LibraryTab.STATS.opensPage)
    }

    @Test
    fun `select all chooses what is shown and clear keeps selection mode on`() {
        val entered = reduceLibrarySelection(LibrarySelectionState(), LibrarySelectionAction.Enter)
        val all = reduceLibrarySelection(entered, LibrarySelectionAction.SelectAll(listOf("movie:a", "movie:b")))
        assertEquals(setOf("movie:a", "movie:b"), all.selectedKeys)
        val cleared = reduceLibrarySelection(all, LibrarySelectionAction.Clear)
        assertTrue(cleared.active)
        assertTrue(cleared.selectedKeys.isEmpty())
    }

    @Test
    fun `select all outside selection mode does nothing`() {
        val state = reduceLibrarySelection(LibrarySelectionState(), LibrarySelectionAction.SelectAll(listOf("movie:a")))
        assertFalse(state.active)
        assertTrue(state.selectedKeys.isEmpty())
    }
}
