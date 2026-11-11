package com.fetch.tv.ui.screens.home

import android.os.SystemClock
import android.util.Log
import com.fetch.tv.core.build.AppFeaturePolicy
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.YouTubeAuthDataStore
import com.fetch.tv.data.local.YouTubeSettingsDataStore
import com.fetch.tv.data.youtube.YouTubeFeed
import com.fetch.tv.data.youtube.YouTubeFeedFilter
import com.fetch.tv.data.youtube.YouTubePage
import com.fetch.tv.data.youtube.YouTubePlayRequests
import com.fetch.tv.data.youtube.YouTubeService
import com.fetch.tv.data.youtube.YouTubeVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Most videos the YouTube row on Home shows. */
internal const val YOUTUBE_HOME_ROW_MAX_VIDEOS = 20

/** How long the subscriptions feed fetched for Home is reused before a return to Home fetches it again. */
internal const val YOUTUBE_HOME_ROW_CACHE_TTL_MS = 10L * 60L * 1000L

/**
 * Whether Home may show the YouTube row at all. Restricted and kids profiles don't get it,
 * as they don't get the YouTube screen it opens.
 */
internal fun youTubeHomeRowAllowed(
    youTubeInBuild: Boolean,
    rowEnabled: Boolean,
    signedIn: Boolean,
    restrictedProfile: Boolean
): Boolean = youTubeInBuild && rowEnabled && signedIn && !restrictedProfile

/** The videos the row shows from [page]: what [filter] leaves, each once, at most [max]. */
internal fun youTubeHomeRowVideos(
    page: YouTubePage,
    filter: YouTubeFeedFilter,
    max: Int = YOUTUBE_HOME_ROW_MAX_VIDEOS
): List<YouTubeVideo> = filter.apply(page, YouTubeFeed.SUBSCRIPTIONS).shelves
    .asSequence()
    .flatMap { it.videos }
    .distinctBy { it.videoId }
    .take(max.coerceAtLeast(0))
    .toList()

/**
 * Longest the row waits for the subscriptions feed. Home never waits on it either way; this only
 * bounds how long a stalled YouTube keeps a fetch (and the row's cache lock) busy.
 */
internal const val YOUTUBE_HOME_ROW_TIMEOUT_MS = 20_000L

internal fun youTubeHomeRowCacheFresh(
    fetchedAtMs: Long,
    nowMs: Long,
    ttlMs: Long = YOUTUBE_HOME_ROW_CACHE_TTL_MS
): Boolean = nowMs - fetchedAtMs in 0 until ttlMs

/**
 * Loads the optional "YouTube subscriptions" row on Home. A failure only leaves the row out;
 * Home never waits on it or shows its errors.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class HomeYouTubeRow @Inject constructor(
    private val settings: YouTubeSettingsDataStore,
    private val authDataStore: YouTubeAuthDataStore,
    private val youTubeService: YouTubeService,
    private val profileManager: ProfileManager,
    private val parentalControls: ParentalControls,
    private val playRequests: YouTubePlayRequests
) {
    /** How long a feed fetch may take; a test shortens it. */
    internal var timeoutMs: Long = YOUTUBE_HOME_ROW_TIMEOUT_MS

    // The channel matters as well as the profile: switching channel shows other subscriptions.
    private data class Gate(val show: Boolean, val profileId: Int, val pageId: String?)

    private class CachedPage(val profileId: Int, val pageId: String?, val fetchedAtMs: Long, val page: YouTubePage)

    private val cacheMutex = Mutex()
    private var cached: CachedPage? = null

    // Bumped each time Home comes back, so a feed older than the cache time is fetched again.
    private val homeVisits = MutableStateFlow(0)

    /** The videos for the row, empty when it shouldn't show. Starts empty so Home never waits on it. */
    val videos: Flow<List<YouTubeVideo>> = combine(
        settings.homeRowEnabled,
        authDataStore.state.map { it.isSignedIn to it.pageId }.distinctUntilChanged(),
        parentalControls.activeRestrictions.map { it.isRestricted }.distinctUntilChanged(),
        profileManager.activeProfileId
    ) { rowEnabled, (signedIn, pageId), restricted, profileId ->
        Gate(
            show = youTubeHomeRowAllowed(
                youTubeInBuild = AppFeaturePolicy.youTubeSectionEnabled,
                rowEnabled = rowEnabled,
                signedIn = signedIn,
                restrictedProfile = restricted
            ),
            profileId = profileId,
            pageId = pageId
        )
    }
        .distinctUntilChanged()
        .flatMapLatest { gate ->
            if (!gate.show) {
                // Turning the row back on, or signing in again, then fetches afresh.
                cacheMutex.withLock { cached = null }
                flowOf(emptyList())
            } else {
                combine(
                    homeVisits.map { page(gate.profileId, gate.pageId) },
                    settings.feedFilter
                ) { page, filter -> youTubeHomeRowVideos(page, filter) }
            }
        }
        .onStart { emit(emptyList()) }
        // A broken YouTube (a store that can't be read, a changed response the parser throws on)
        // ends the row, never Home: Home combines this flow, and an error here would end that too.
        .catch { error ->
            Log.w(TAG, "YouTube row stopped: ${error.message}")
            emit(emptyList())
        }
        .distinctUntilChanged()

    /** Home is showing again; refetches the feed if the cached one is older than the cache time. */
    fun onHomeVisible() {
        homeVisits.value += 1
    }

    /** Plays [video] on the YouTube screen, which picks the request up once it opens. */
    fun play(video: YouTubeVideo) {
        playRequests.request(video)
    }

    private suspend fun page(profileId: Int, pageId: String?): YouTubePage = cacheMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        cached?.takeIf { it.profileId == profileId && it.pageId == pageId && youTubeHomeRowCacheFresh(it.fetchedAtMs, now) }
            ?.let { return@withLock it.page }
        val loaded = withTimeoutOrNull(timeoutMs) {
            try {
                youTubeService.loadFeed(YouTubeFeed.SUBSCRIPTIONS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not load YouTube subscriptions for Home: ${e.message}")
                null
            }
        }
        // Signed out, offline, unreachable or a changed response: the row stays hidden, and the
        // empty page is kept for the cache time so each return to Home doesn't try again.
        val page = loaded ?: YouTubePage(emptyList(), null)
        cached = CachedPage(profileId, pageId, now, page)
        page
    }

    private companion object {
        const val TAG = "HomeYouTubeRow"
    }
}
