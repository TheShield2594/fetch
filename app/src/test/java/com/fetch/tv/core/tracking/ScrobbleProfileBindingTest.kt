package com.fetch.tv.core.tracking

import com.fetch.tv.data.anilist.AniListApi
import com.fetch.tv.data.anilist.AniListAuthState
import com.fetch.tv.data.anilist.AniListAuthStore
import com.fetch.tv.data.anilist.AniListScrobbler
import com.fetch.tv.data.anime.AnimeEntryResolver
import com.fetch.tv.data.mdblist.MdbListAuthScope
import com.fetch.tv.data.mdblist.MdbListScrobbleService
import com.fetch.tv.data.mdblist.MdbListSyncRepository
import com.fetch.tv.data.mdblist.MdbListTrackingScrobbler
import com.fetch.tv.data.repository.TraktScrobbleService
import com.fetch.tv.data.repository.TraktTrackingScrobbler
import com.fetch.tv.data.website.FetchWebsiteClient
import com.fetch.tv.data.website.FetchWebsiteTrackingRepository
import com.fetch.tv.data.website.FetchWebsiteTrackingScrobbler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A scrobble acts for the profile that watched, not whichever profile is active by the time it
 * runs: the viewer may have switched profiles since (here the active profile is 1 and the
 * playback was profile 2's).
 */
class ScrobbleProfileBindingTest {
    private val movie = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        ids = TrackingExternalIds(imdb = "tt0111161"),
        catalog = TrackingCatalogReference("tt0111161", "movie")
    )
    private val fromProfile2 = TrackingScrobbleEvent(movie, 95.0, profileId = 2)

    @Test
    fun `Trakt sends with the watching profile's token`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)

        TraktTrackingScrobbler(service, mockk(relaxed = true)).scrobble(TrackingScrobbleAction.STOP, fromProfile2)

        coVerify(exactly = 1) { service.scrobbleStop(any(), 95f, 2) }
    }

    @Test
    fun `the website scrobble goes to the watching profile's slot`() = runTest {
        val repository = mockk<FetchWebsiteTrackingRepository>(relaxed = true) {
            every { activeSlot() } returns 1
        }

        FetchWebsiteTrackingScrobbler(repository, mockk<FetchWebsiteClient>(relaxed = true))
            .scrobble(TrackingScrobbleAction.STOP, fromProfile2)

        coVerify(exactly = 1) { repository.asProfile<Any?>(2, any()) }
        coVerify(exactly = 0) { repository.asProfile<Any?>(1, any()) }
    }

    @Test
    fun `MDBList, holding only the active profile's sign-in, drops another profile's scrobble`() = runTest {
        val sync = mockk<MdbListSyncRepository> { every { currentScope() } returns MdbListAuthScope(profileId = 1, generation = 0) }
        val service = mockk<MdbListScrobbleService>(relaxed = true)
        val scrobbler = MdbListTrackingScrobbler(sync, service)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, fromProfile2)
        coVerify(exactly = 0) { service.scrobble(any(), any(), any()) }

        scrobbler.scrobble(TrackingScrobbleAction.STOP, fromProfile2.copy(profileId = 1))
        coVerify(exactly = 1) { service.scrobble(any(), any(), any()) }
    }

    @Test
    fun `AniList reads the watching profile's sign-in`() = runTest {
        val auth = mockk<AniListAuthStore> {
            every { activeProfileId() } returns 1
            coEvery { current(any()) } returns AniListAuthState()
        }

        AniListScrobbler(auth, mockk<AniListApi>(), mockk<AnimeEntryResolver>()).scrobble(TrackingScrobbleAction.STOP, fromProfile2)

        coVerify(exactly = 1) { auth.current(2) }
        verify(exactly = 0) { auth.activeProfileId() }
    }
}
