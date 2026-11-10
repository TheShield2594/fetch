package com.fetch.tv.ui.screens.detail

import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.streams.BestMatchPreviewer
import com.fetch.tv.data.local.BestMatchCacheDataStore
import com.fetch.tv.data.local.BingeGroupCacheDataStore
import com.fetch.tv.data.local.CachedBestMatch
import com.fetch.tv.data.local.PlayerSettings
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.StreamLinkCacheDataStore
import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Counts stream searches: each one tells every stream addon which title is open and can cost
 * debrid requests, so passing through titles must not start any.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BestMatchPreviewLookupTest {
    /** Each call is one search across every stream addon. */
    private var searchCount = 0
    private val streamRepository = mockk<StreamRepository> {
        every { getStreamsFromAllAddons(any(), any(), any(), any(), any()) } answers {
            searchCount++
            flowOf(NetworkResult.Success(emptyList<AddonStreams>()))
        }
    }

    private fun previewer(cache: Map<String, CachedBestMatch> = emptyMap()) = BestMatchPreviewer(
        streamRepository = streamRepository,
        playerSettingsDataStore = mockk<PlayerSettingsDataStore> {
            // Best match with its preview on, and no reused link or binge group to defer to.
            every { playerSettings } returns flowOf(PlayerSettings(streamReuseLastLinkEnabled = false))
        },
        addonRepository = mockk<AddonRepository> {
            every { getInstalledAddons() } returns flowOf(emptyList())
        },
        bingeGroupCacheDataStore = mockk<BingeGroupCacheDataStore>(),
        streamLinkCacheDataStore = mockk<StreamLinkCacheDataStore>(),
        bestMatchCacheDataStore = mockk<BestMatchCacheDataStore> {
            every { entries } returns flowOf(cache)
            coEvery { save(any(), any(), any(), any()) } returns Unit
        },
        serverStreams = mockk<com.fetch.tv.data.mediaserver.ServerStreams> {
            every { isNativeRequest(any()) } returns false
            every { preferredSourceNames(any(), any()) } returns emptySet()
        }
    )

    /** One detail page, opened with focus on Play as the page does, then left after [stayMs]. */
    private fun TestScope.visit(lookup: BestMatchPreviewLookup, index: Int, stayMs: Long) {
        lookup.show("movie", "tt000000$index", null, null, "tt000000$index")
        lookup.onPlayFocusChanged(true)
        advanceTimeBy(stayMs)
        runCurrent()
        lookup.onPlayFocusChanged(false)
        lookup.pause()
    }

    @Test
    fun `browsing ten titles without pausing asks no addon`() = runTest {
        val previewer = previewer()
        // Each page keeps its own lookup, and pages stay on the back stack while the next opens.
        val pages = (1..10).map { BestMatchPreviewLookup(backgroundScope, previewer) }
        pages.forEachIndexed { index, lookup -> visit(lookup, index, stayMs = 1_500) }
        // Long after: nothing left behind on the back stack starts a search either.
        advanceTimeBy(BestMatchPreviewLookup.DWELL_MS * 4)
        runCurrent()

        assertEquals(0, searchCount)
    }

    @Test
    fun `a fresh saved pick is shown without asking any addon`() = runTest {
        val cache = mapOf(
            BestMatchCacheDataStore.contentKey("movie", "tt1") to CachedBestMatch(
                labels = listOf("4K", "DV", "Atmos"),
                isCached = true,
                savedAtMs = System.currentTimeMillis()
            )
        )
        val lookup = BestMatchPreviewLookup(backgroundScope, previewer(cache))
        lookup.show("movie", "tt1", null, null, "tt1")
        lookup.onPlayFocusChanged(true)
        advanceTimeBy(BestMatchPreviewLookup.DWELL_MS * 2)
        runCurrent()

        val shown = lookup.preview.value
        assertNotNull(shown)
        assertEquals(listOf("4K", "DV", "Atmos"), shown!!.labels)
        assertTrue(shown.isCached)
        assertEquals(0, searchCount)
    }

    @Test
    fun `a pick older than a day is not used`() = runTest {
        val cache = mapOf(
            BestMatchCacheDataStore.contentKey("movie", "tt1") to CachedBestMatch(
                labels = listOf("1080p"),
                isCached = false,
                savedAtMs = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
            )
        )
        val lookup = BestMatchPreviewLookup(backgroundScope, previewer(cache))
        lookup.show("movie", "tt1", null, null, "tt1")
        runCurrent()

        assertEquals(null, lookup.preview.value)
    }

    @Test
    fun `holding focus on Play searches once`() = runTest {
        val lookup = BestMatchPreviewLookup(backgroundScope, previewer())
        lookup.show("movie", "tt1", null, null, "tt1")
        lookup.onPlayFocusChanged(true)
        advanceTimeBy(BestMatchPreviewLookup.PLAY_HOLD_MS - 1)
        runCurrent()
        assertEquals(0, searchCount)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, searchCount)

        // The dwell ending later finds the search already made.
        advanceTimeBy(BestMatchPreviewLookup.DWELL_MS)
        runCurrent()
        assertEquals(1, searchCount)
    }

    @Test
    fun `staying on the page with focus elsewhere searches after the dwell`() = runTest {
        val lookup = BestMatchPreviewLookup(backgroundScope, previewer())
        lookup.show("movie", "tt1", null, null, "tt1")
        advanceTimeBy(BestMatchPreviewLookup.DWELL_MS - 1)
        runCurrent()
        assertEquals(0, searchCount)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, searchCount)
    }

    @Test
    fun `moving off Play before the hold ends does not search`() = runTest {
        val lookup = BestMatchPreviewLookup(backgroundScope, previewer())
        lookup.show("movie", "tt1", null, null, "tt1")
        lookup.onPlayFocusChanged(true)
        advanceTimeBy(BestMatchPreviewLookup.PLAY_HOLD_MS / 2)
        lookup.onPlayFocusChanged(false)
        advanceTimeBy(BestMatchPreviewLookup.PLAY_HOLD_MS)
        runCurrent()

        assertEquals(0, searchCount)
    }
}
