package com.fetch.tv.ui.screens.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.streams.BestMatchPreviewer
import com.fetch.tv.data.local.BestMatchCacheDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.StreamAutoPlayMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks addons in the background about a few Continue Watching titles, then watchlist movies, so
 * Home can mark the ones that will start instantly. It runs only while Home is on screen, on an
 * unmetered network, with Best match and its preview on, one title at a time. Each lookup saves its pick through
 * [BestMatchPreviewer], which is what the ready dot and "Ready tonight" read.
 */
@Singleton
class HomeReadyChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val previewer: BestMatchPreviewer,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val bestMatchCacheDataStore: BestMatchCacheDataStore,
    private val profileManager: ProfileManager
) {
    /** When each "profile|key" was last asked about, found or not. Kept for the app's lifetime. */
    private val lastAttemptMs = ConcurrentHashMap<String, Long>()
    private val running = Mutex()

    internal suspend fun check(
        items: List<ContinueWatchingItem>,
        watchlist: List<com.fetch.tv.domain.model.LibraryEntry> = emptyList()
    ) {
        if ((items.isEmpty() && watchlist.isEmpty()) || !running.tryLock()) return
        try {
            val settings = playerSettingsDataStore.playerSettings.first()
            if (!settings.readyCheckEnabled || !settings.bestMatchPreviewEnabled) return
            if (settings.streamAutoPlayMode != StreamAutoPlayMode.BEST_MATCH) return
            if (!isUnmetered()) return

            val profilePrefix = "${profileManager.activeProfileId.value}|"
            val attempts = lastAttemptMs
                .filterKeys { it.startsWith(profilePrefix) }
                .mapKeys { (key, _) -> key.removePrefix(profilePrefix) }
            val requests = readyCheckCandidates(
                items = items,
                entries = bestMatchCacheDataStore.entries.first(),
                lastAttemptMs = attempts,
                nowMs = System.currentTimeMillis(),
                watchlist = watchlist
            )
            for (request in requests) {
                if (!isUnmetered()) return
                try {
                    withTimeout(LOOKUP_TIMEOUT_MS) {
                        previewer.preview(
                            type = request.type,
                            videoId = request.videoId,
                            season = request.season,
                            episode = request.episode,
                            contentId = request.contentId
                        ).collect { }
                    }
                } catch (_: TimeoutCancellationException) {
                    // Counted as an attempt below: a slow addon shouldn't be asked again at once.
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                }
                // Recorded only once a lookup ends, so leaving Home mid-lookup doesn't skip the title.
                lastAttemptMs[profilePrefix + request.key] = System.currentTimeMillis()
            }
        } finally {
            running.unlock()
        }
    }

    /** Wi-Fi or Ethernet that isn't metered, as the setting says; never mobile data. */
    private fun isUnmetered(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        val wifiOrEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return wifiOrEthernet && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 60_000L
    }
}
