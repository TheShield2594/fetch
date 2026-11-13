package com.fetch.tv.data.anilist

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

/**
 * AniList as a scrobble target: finishing an anime episode in Fetch moves the matching AniList
 * entry's progress forward. It is never a source of truth; its history comes in through the
 * one-off import instead.
 */
@Singleton
class AniListTrackingProvider @Inject constructor(
    auth: AniListAuthStore,
    aniListScrobbler: AniListScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.ANILIST,
        displayName = "AniList",
        capabilities = setOf(TrackingCapability.AUTHENTICATION, TrackingCapability.SCROBBLE)
    )
    override val isAuthenticated: StateFlow<Boolean> = auth.isAuthenticated
    override val scrobbler: TrackingScrobbler = aniListScrobbler
}

@Singleton
class AniListScrobbler @Inject constructor(
    private val auth: AniListAuthStore,
    private val api: AniListApi,
    private val resolver: AnimeEntryResolver
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.ANILIST

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (action != TrackingScrobbleAction.STOP || event.progressPercent < ANIME_WATCHED_PERCENT) return
        // Captured before any network call so a profile switch can't move another profile's list.
        val state = auth.current(auth.activeProfileId())
        val token = state.accessToken?.takeIf { state.isAuthenticated() } ?: return
        val target = resolver.forAniList(event.media, token) ?: return
        val mediaId = target.aniListId ?: return
        val update = nextAnimeProgress(api.entry(token, mediaId), target.episode) ?: return
        api.saveProgress(token, mediaId, update.progress, update.status)
        Log.d(TAG, "AniList $mediaId progress ${update.progress} (${update.status})")
    }

    private companion object {
        const val TAG = "AniListScrobble"
    }
}
