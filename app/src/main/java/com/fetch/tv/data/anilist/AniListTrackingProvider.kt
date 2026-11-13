package com.fetch.tv.data.anilist

import android.util.Log
import com.fetch.tv.core.tracking.TrackingCapability
import com.fetch.tv.core.tracking.TrackingProvider
import com.fetch.tv.core.tracking.TrackingProviderDescriptor
import com.fetch.tv.core.tracking.TrackingProviderId
import com.fetch.tv.core.tracking.TrackingScrobbleAction
import com.fetch.tv.core.tracking.TrackingScrobbleEvent
import com.fetch.tv.core.tracking.TrackingScrobbler
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
    private val resolver: AniListEntryResolver
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.ANILIST

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (action != TrackingScrobbleAction.STOP || event.progressPercent < WATCHED_PERCENT) return
        // Captured before any network call so a profile switch can't move another profile's list.
        val state = auth.current(auth.activeProfileId())
        val token = state.accessToken?.takeIf { state.isAuthenticated() } ?: return
        val target = resolver.resolve(event.media, token) ?: return
        val entry = api.entry(token, target.mediaId)
        val update = nextAniListProgress(entry, target.episode) ?: return
        api.saveProgress(token, target.mediaId, update.progress, update.status)
        Log.d(TAG, "AniList ${target.mediaId} progress ${update.progress} (${update.status})")
    }

    private companion object {
        const val TAG = "AniListScrobble"

        /** Trakt's rule for a play that counts as watched; Fetch scrobbles every service the same way. */
        const val WATCHED_PERCENT = 80.0
    }
}

data class AniListProgressUpdate(val progress: Int, val status: AniListStatus)

/**
 * What a finished [episode] does to an AniList entry: progress only moves forward, the entry is
 * completed on its last episode, and a completed entry being rewatched outside a repeat is left
 * alone. Null means nothing to change.
 */
fun nextAniListProgress(entry: AniListEntry?, episode: Int): AniListProgressUpdate? {
    if (episode <= 0) return null
    val total = entry?.episodes
    val progress = total?.let { episode.coerceAtMost(it) } ?: episode
    val current = entry?.progress ?: 0
    if (entry?.status == AniListStatus.COMPLETED || progress <= current) return null
    val status = when {
        total != null && progress >= total -> AniListStatus.COMPLETED
        entry?.status == AniListStatus.REPEATING -> AniListStatus.REPEATING
        else -> AniListStatus.CURRENT
    }
    return AniListProgressUpdate(progress, status)
}
