package com.fetch.tv.ui.screens.library

import com.fetch.tv.MainDispatcherRule
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.LibraryListPrivacy
import com.fetch.tv.domain.model.LibrarySourceMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryListManagementTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `MDBList editor saves the provider key and omits unsupported existing descriptions`() = runTest {
        val f = LibraryViewModelTestFixture()
        runCurrent()
        f.viewModel.onOpenManageLists()
        f.viewModel.onStartEditList()
        assertEquals("mdblist:list:7", f.viewModel.uiState.value.listEditorState!!.listId)
        f.viewModel.onUpdateEditorName("Renamed")
        f.viewModel.onUpdateEditorPrivacy(LibraryListPrivacy.FRIENDS)
        assertEquals(LibraryListPrivacy.PRIVATE, f.viewModel.uiState.value.listEditorState!!.privacy)
        f.viewModel.onUpdateEditorPrivacy(LibraryListPrivacy.PUBLIC)
        f.viewModel.onSubmitEditor()
        runCurrent()
        coVerify(exactly = 1) {
            f.repository.updatePersonalList("mdblist:list:7", "Renamed", null, LibraryListPrivacy.PUBLIC, LibrarySourceMode.MDBLIST)
        }
        assertNull(f.viewModel.uiState.value.listEditorState)
    }

    @Test
    fun `the local library manages lists with no account and filters by its list tabs`() = runTest {
        val f = LibraryViewModelTestFixture()
        f.source.value = LibrarySourceMode.LOCAL
        f.tabs.value = listOf(
            com.fetch.tv.domain.model.LibraryListTab("local", "Fetch Library", com.fetch.tv.domain.model.LibraryListTab.Type.WATCHLIST),
            com.fetch.tv.domain.model.LibraryListTab("local:list:a", "Weekend movies", com.fetch.tv.domain.model.LibraryListTab.Type.PERSONAL)
        )
        f.items.value = listOf(entry("tt1", setOf("local")), entry("tt2", setOf("local:list:a")))
        runCurrent()

        assertEquals(listOf("tt1"), f.viewModel.uiState.value.visibleItems.map { it.id })
        f.viewModel.onSelectListTab("local:list:a")
        assertEquals(listOf("tt2"), f.viewModel.uiState.value.visibleItems.map { it.id })

        f.viewModel.onOpenManageLists()
        assertTrue(f.viewModel.uiState.value.showManageDialog)
        assertEquals("local:list:a", f.viewModel.uiState.value.manageSelectedListKey)
        f.viewModel.onStartCreateList()
        f.viewModel.onUpdateEditorName("Kids' shows")
        f.viewModel.onSubmitEditor()
        runCurrent()
        coVerify(exactly = 1) {
            f.repository.createPersonalList("Kids' shows", null, LibraryListPrivacy.PRIVATE, LibrarySourceMode.LOCAL)
        }
    }

    private fun entry(id: String, listKeys: Set<String>) = LibraryEntry(
        id = id, type = "movie", name = id, poster = null, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null, listKeys = listKeys
    )

    @Test
    fun `profile switch clears the editor and prevents submitting to the new profile`() = runTest {
        val f = LibraryViewModelTestFixture()
        runCurrent()
        f.viewModel.onOpenManageLists()
        f.viewModel.onStartCreateList()
        f.viewModel.onUpdateEditorName("Old profile")
        f.profile.value = 2
        f.viewModel.onSubmitEditor()
        runCurrent()
        assertFalse(f.viewModel.uiState.value.showManageDialog)
        assertNull(f.viewModel.uiState.value.listEditorState)
        coVerify(exactly = 0) { f.repository.createPersonalList(any(), any(), any(), any()) }
    }

    @Test
    fun `disconnect and library source changes dismiss management`() = runTest {
        for (disconnect in listOf(true, false)) {
            val f = LibraryViewModelTestFixture()
            runCurrent()
            f.viewModel.onOpenManageLists()
            f.viewModel.onStartEditList()
            if (disconnect) f.authenticated.value = false else f.source.value = LibrarySourceMode.LOCAL
            runCurrent()
            assertFalse(f.viewModel.uiState.value.showManageDialog)
            f.viewModel.onDeleteSelectedList()
            coVerify(exactly = 0) { f.repository.deletePersonalList(any(), any()) }
        }
    }

    @Test
    fun `duplicate save is suppressed and an old completion cannot close a newly opened editor`() = runTest {
        val f = LibraryViewModelTestFixture()
        val finished = CompletableDeferred<Unit>()
        coEvery { f.repository.createPersonalList(any(), any(), any(), any()) } coAnswers { finished.await() }
        runCurrent()
        f.viewModel.onOpenManageLists()
        f.viewModel.onStartCreateList()
        f.viewModel.onUpdateEditorName("First")
        f.viewModel.onSubmitEditor()
        f.viewModel.onSubmitEditor()
        runCurrent()
        assertTrue(f.viewModel.uiState.value.pendingOperation)
        f.viewModel.onCloseManageLists()
        f.viewModel.onOpenManageLists()
        f.viewModel.onStartCreateList()
        f.viewModel.onUpdateEditorName("New draft")
        finished.complete(Unit)
        runCurrent()
        assertEquals("New draft", f.viewModel.uiState.value.listEditorState!!.name)
        coVerify(exactly = 1) { f.repository.createPersonalList(any(), any(), any(), any()) }
    }

    @Test
    fun `MDBList never offers list reordering and defaults to each selected list order`() = runTest {
        val f = LibraryViewModelTestFixture()
        val second = f.tab.copy(key = "mdblist:list:8", title = "Other")
        f.tabs.value = listOf(f.tab, second)
        fun item(id: String, first: Int, other: Int) = LibraryEntry(
            id, "movie", id, null, background = null, logo = null, description = null, releaseInfo = null,
            imdbRating = null, genres = emptyList(), addonBaseUrl = null,
            listKeys = setOf(f.tab.key, second.key), listRanks = mapOf(f.tab.key to first, second.key to other)
        )
        f.items.value = listOf(item("First", 1, 2), item("Second", 2, 1))
        runCurrent()
        assertEquals(listOf("First", "Second"), f.viewModel.uiState.value.visibleItems.map { it.name })
        f.viewModel.onSelectListTab(second.key)
        assertEquals(listOf("Second", "First"), f.viewModel.uiState.value.visibleItems.map { it.name })
        f.viewModel.onOpenManageLists()
        f.viewModel.onMoveSelectedListDown()
        runCurrent()
        coVerify(exactly = 0) { f.repository.reorderPersonalLists(any(), any()) }
    }

}
