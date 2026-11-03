package com.fetch.desktop.parental

import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.ProgressFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TitleListsTest {
    private val show = ParentalTitle(ids = listOf("tt100", "tmdb:5"), type = "series", name = "Show")

    @Test
    fun `a hidden show hides its episodes and other ids`() {
        val lists = ProfileTitleLists.Empty.with(show, ParentalTitleState.HIDDEN)
        assertTrue(lists.isHidden("tt100", null, "series"))
        assertTrue(lists.isHidden("tt100:1:2", null, "series"))
        assertTrue(lists.isHidden("other", "tt100", null))
        // A movie sharing the TMDB id is another title.
        assertFalse(lists.isHidden("tmdb:5", null, "movie"))
        assertEquals(ParentalTitleState.HIDDEN, lists.stateOf(show))
    }

    @Test
    fun `cycling goes rating, hidden, allowed and back, one list at a time`() {
        val dir = Files.createTempDirectory("titles").toFile()
        val state = TitleListsState(titleListsFile(dir))
        state.cycle(2, show)
        assertEquals(ParentalTitleState.HIDDEN, state.stateFor(2, show))
        state.cycle(2, show)
        assertEquals(ParentalTitleState.ALLOWED, state.stateFor(2, show))
        assertTrue(state.listsFor(2).hidden.isEmpty())
        // Kept across restarts.
        assertEquals(ParentalTitleState.ALLOWED, TitleListsState(titleListsFile(dir)).stateFor(2, show))
        state.cycle(2, show)
        assertEquals(ParentalTitleState.BY_RATING, state.stateFor(2, show))
        assertTrue(2 !in state.all)
    }

    @Test
    fun `the main profile has no lists and a deleted profile's go with it`() {
        val state = TitleListsState(titleListsFile(Files.createTempDirectory("titles").toFile()))
        assertFalse(state.setState(1, show, ParentalTitleState.HIDDEN))
        state.setState(3, show, ParentalTitleState.HIDDEN)
        state.deleteProfile(3)
        assertEquals(ProfileTitleLists.Empty, state.listsFor(3))
    }

    @Test
    fun `hidden titles leave Continue Watching`() {
        fun entry(id: String) = ProgressEntry(contentId = id, contentType = "series", name = id, videoId = "$id:1:1", positionMs = 1, durationMs = 10, lastWatched = 0)
        val file = ProgressFile(entries = listOf(entry("tt100"), entry("tt200")))
        val lists = ProfileTitleLists.Empty.with(show, ParentalTitleState.HIDDEN)
        assertEquals(listOf("tt200"), visibleProgress(file, lists).entries.map { it.contentId })
    }
}
