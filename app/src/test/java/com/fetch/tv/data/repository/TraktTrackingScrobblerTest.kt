package com.fetch.tv.data.repository

import com.fetch.tv.core.tracking.TrackingExternalIds
import com.fetch.tv.core.tracking.TrackingMediaKind
import com.fetch.tv.core.tracking.TrackingMediaReference
import com.fetch.tv.core.tracking.TrackingScrobbleAction
import com.fetch.tv.core.tracking.TrackingScrobbleEvent
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TraktTrackingScrobblerTest {
    @Test
    fun `pause preserves trakt stop behavior`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = TraktTrackingScrobbler(
            service = service,
            episodeMappingService = mockk(relaxed = true)
        )

        scrobbler.scrobble(
            action = TrackingScrobbleAction.PAUSE,
            event = TrackingScrobbleEvent(
                media = TrackingMediaReference(
                    kind = TrackingMediaKind.MOVIE,
                    ids = TrackingExternalIds(imdb = "tt0111161")
                ),
                progressPercent = 45.0
            )
        )

        coVerify(exactly = 1) { service.scrobbleStop(any(), 45f) }
        coVerify(exactly = 0) { service.scrobblePause(any(), any()) }
    }
}
