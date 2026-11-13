package com.fetch.tv.data.mal

import android.util.Log
import com.fetch.tv.core.tracking.TrackingCapability
import com.fetch.tv.core.tracking.TrackingProvider
import com.fetch.tv.core.tracking.TrackingProviderDescriptor
import com.fetch.tv.core.tracking.TrackingProviderId
import com.fetch.tv.core.tracking.TrackingScrobbleAction
import com.fetch.tv.core.tracking.TrackingScrobbleEvent
import com.fetch.tv.core.tracking.TrackingScrobbler
import com.fetch.tv.data.anime.ANIME_WATCHED_PERCENT
import com.fetch.tv.data.anime.AnimeEntryResolver
import com.fetch.tv.data.anime.nextAnimeProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MyAnimeList as a scrobble target, like AniList: finishing an anime episode moves the matching
 * entry forward. Never a source of truth; its history comes in through the one-off import.
 */
@Singleton
class MalTrackingProvider @Inject constructor(
    auth: MalAuthStore,
    malScrobbler: MalScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.MAL,
        displayName = "MyAnimeList",
        capabilities = setOf(TrackingCapability.AUTHENTICATION, TrackingCapability.SCROBBLE)
    )
    override val isAuthenticated: StateFlow<Boolean> = auth.isAuthenticated
    override val scrobbler: TrackingScrobbler = malScrobbler
}

@Singleton
class MalScrobbler @Inject constructor(
    private val auth: MalAuthStore,
    private val api: MalApi,
    private val resolver: AnimeEntryResolver
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.MAL

    /** One read-then-write at a time, so two quick STOPs can't write progress from the same stale read. */
    private val updateLock = Mutex()

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (action != TrackingScrobbleAction.STOP || event.progressPercent < ANIME_WATCHED_PERCENT) return
        // The profile that watched, not whichever is active by the time this runs.
        val token = auth.validToken(event.profileId ?: auth.activeProfileId()) ?: return
        val target = resolver.forMal(event.media) ?: return
        val malId = target.malId ?: return
        updateLock.withLock {
            val entry = api.entry(token, malId) ?: return
            val update = nextAnimeProgress(entry, target.episode) ?: return
            api.saveProgress(token, malId, update.progress, update.status)
            Log.d(TAG, "MyAnimeList $malId progress ${update.progress} (${update.status})")
        }
    }

    private companion object {
        const val TAG = "MalScrobble"
    }
}
