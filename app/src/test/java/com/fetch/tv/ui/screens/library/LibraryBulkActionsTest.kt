package com.fetch.tv.ui.screens.library

import com.fetch.tv.domain.model.ListMembershipSnapshot
import com.fetch.tv.domain.repository.LibraryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A bulk action stops writing once another profile is active (#653, review on #765). */
class LibraryBulkActionsTest {

    private val repository = mockk<LibraryRepository>(relaxed = true)
    private val actions = LibraryBulkActions(
        libraryRepository = repository,
        watchProgressRepository = mockk(relaxed = true),
        metaRepository = mockk(relaxed = true),
        watchedSeriesStateHolder = mockk(relaxed = true)
    )
    private val target = LibraryBulkTarget(id = "tt1", type = "movie", name = "Title", input = mockk(relaxed = true))

    @Test
    fun `a profile switch during the membership read starts no write`() = runBlocking {
        var current = true
        coEvery { repository.getMembershipSnapshot(any()) } answers {
            current = false // the user switches profiles while this read is suspended
            mockk<ListMembershipSnapshot> { io.mockk.every { listMembership } returns mapOf("trakt:watchlist" to true) }
        }

        val result = actions.changeMembership(
            listOf(target), tabs = emptyList(), addTo = null, removeFrom = "trakt:watchlist", isCurrent = { current }
        )

        coVerify(exactly = 0) { repository.applyMembershipChanges(any(), any()) }
        assertEquals(LibraryBulkResult(skipped = 1), result)
    }

    @Test
    fun `the write goes ahead while the starting profile is still active`() = runBlocking {
        coEvery { repository.getMembershipSnapshot(any()) } returns
            mockk { io.mockk.every { listMembership } returns mapOf("trakt:watchlist" to true) }

        actions.changeMembership(listOf(target), tabs = emptyList(), addTo = null, removeFrom = "trakt:watchlist", isCurrent = { true })

        coVerify(exactly = 1) { repository.applyMembershipChanges(any(), any()) }
    }
}
