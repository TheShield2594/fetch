package com.fetch.tv.ui.screens.home

import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.YouTubeAuthDataStore
import com.fetch.tv.data.local.YouTubeAuthState
import com.fetch.tv.data.local.YouTubeSettingsDataStore
import com.fetch.tv.data.youtube.YouTubeFeedFilter
import com.fetch.tv.data.youtube.YouTubePlayRequests
import com.fetch.tv.data.youtube.YouTubeService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Home combines [HomeYouTubeRow.videos] into its own state, so whatever YouTube does, that flow
 * must start empty at once and never fail (audit E3): a broken YouTube costs only the row.
 */
class HomeYouTubeRowFailureTest {
    private fun row(
        service: YouTubeService,
        authState: Flow<YouTubeAuthState> = flowOf(YouTubeAuthState(accessToken = "token", refreshToken = "refresh"))
    ): HomeYouTubeRow {
        val settings = mockk<YouTubeSettingsDataStore> {
            every { homeRowEnabled } returns flowOf(true)
            every { feedFilter } returns flowOf(YouTubeFeedFilter())
        }
        val auth = mockk<YouTubeAuthDataStore> { every { state } returns authState }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val parental = mockk<ParentalControls> { every { activeRestrictions } returns MutableStateFlow(ProfileRestrictions()) }
        return HomeYouTubeRow(settings, auth, service, profiles, parental, mockk<YouTubePlayRequests>(relaxed = true))
    }

    @Test
    fun `unreachable YouTube leaves the row empty without failing`() = runTest {
        val service = mockk<YouTubeService> {
            coEvery { loadFeed(any()) } throws IOException("Unable to resolve host www.youtube.com")
        }

        val received = mutableListOf<List<Any>>()
        // A failure here would fail the test: backgroundScope reports it.
        backgroundScope.launch { row(service).videos.collect { received += it } }
        runCurrent()

        assertEquals(listOf(emptyList<Any>()), received)
    }

    @Test
    fun `a parser failure leaves the row empty without failing`() = runTest {
        val service = mockk<YouTubeService> {
            coEvery { loadFeed(any()) } throws IllegalStateException("unexpected response shape")
        }

        val received = mutableListOf<List<Any>>()
        backgroundScope.launch { row(service).videos.collect { received += it } }
        runCurrent()

        assertEquals(listOf(emptyList<Any>()), received)
    }

    @Test
    fun `a stalled YouTube neither delays the first value nor holds the fetch past its timeout`() = runTest {
        var gaveUp = false
        val service = mockk<YouTubeService> {
            coEvery { loadFeed(any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    gaveUp = true
                }
            }
        }
        val row = row(service).apply { timeoutMs = 1_000L }
        val received = mutableListOf<List<Any>>()
        backgroundScope.launch { row.videos.collect { received += it } }

        runCurrent()
        // Home's first value is there while the fetch is still waiting on YouTube.
        assertEquals(listOf(emptyList<Any>()), received)
        assertFalse(gaveUp)

        advanceTimeBy(1_001L)
        runCurrent()
        assertTrue(gaveUp)
        assertEquals(listOf(emptyList<Any>()), received)
    }

    @Test
    fun `a sign-in store that fails ends the row, not its collector`() = runTest {
        val service = mockk<YouTubeService>(relaxed = true)
        val broken = flow<YouTubeAuthState> { throw IOException("datastore corrupted") }

        val emitted = row(service, authState = broken).videos.toList()

        assertEquals(listOf(emptyList<Any>()), emitted)
    }
}
