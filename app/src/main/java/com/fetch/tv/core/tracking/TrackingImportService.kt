package com.fetch.tv.core.tracking

import android.util.Log
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.TraktSettingsDataStore
import com.fetch.tv.data.local.WatchProgressPreferences
import com.fetch.tv.data.local.WatchProgressSource
import com.fetch.tv.data.local.WatchedItemsPreferences
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface TrackingImportStatus {
    data object Idle : TrackingImportStatus
    data class Running(val source: WatchProgressSource, val target: WatchProgressSource) : TrackingImportStatus
    data class Done(val result: TrackingImportResult) : TrackingImportStatus
    data class Failed(val source: WatchProgressSource, val target: WatchProgressSource) : TrackingImportStatus
}

/**
 * Runs a one-off import from another connected source into the current watch progress source
 * (the source of truth), outside any screen's lifetime so leaving Settings doesn't cut it short.
 */
@Singleton
class TrackingImportService @Inject constructor(
    private val settingsDataStore: TraktSettingsDataStore,
    private val providers: TrackingProviderRegistry,
    private val progressProviders: TrackingProgressProviderRegistry,
    private val historyWriters: TrackingHistoryWriterRegistry,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val importer = TrackingImporter()
    private val _status = MutableStateFlow<TrackingImportStatus>(TrackingImportStatus.Idle)
    val status: StateFlow<TrackingImportStatus> = _status.asStateFlow()

    /** The current source of truth, after falling back for a provider that is no longer connected. */
    suspend fun sourceOfTruth(): WatchProgressSource =
        effectiveWatchProgressSource(settingsDataStore.watchProgressSource.first(), providers::isAuthenticated)

    /** Sources the user can import from: every connected one except the source of truth. */
    fun importSources(target: WatchProgressSource, connected: Set<TrackingProviderId>): List<WatchProgressSource> =
        availableWatchProgressSources(connected).filter { it != target }

    fun start(source: WatchProgressSource) {
        if (_status.value is TrackingImportStatus.Running) return
        scope.launch {
            val target = sourceOfTruth()
            if (source == target) return@launch
            _status.value = TrackingImportStatus.Running(source, target)
            val profileId = profileManager.activeProfileId.value
            _status.value = try {
                val from = endpoint(source, profileId)
                val into = endpoint(target, profileId)
                if (from == null || into == null) {
                    TrackingImportStatus.Failed(source, target)
                } else {
                    TrackingImportStatus.Done(importer.import(from, into))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Import from ${source.name} into ${target.name} failed", error)
                TrackingImportStatus.Failed(source, target)
            }
        }
    }

    fun acknowledge() {
        if (_status.value !is TrackingImportStatus.Running) _status.value = TrackingImportStatus.Idle
    }

    private fun endpoint(source: WatchProgressSource, profileId: Int): TrackingImportEndpoint? {
        val providerId = source.providerId ?: return LocalImportEndpoint(profileId)
        if (!providers.isAuthenticated(providerId)) return null
        return ProviderImportEndpoint(
            source = source,
            profileId = profileId,
            provider = providers.provider(providerId) ?: return null,
            progress = progressProviders.provider(providerId),
            writer = historyWriters.writer(providerId)
        )
    }

    private inner class ProviderImportEndpoint(
        override val source: WatchProgressSource,
        private val profileId: Int,
        private val provider: TrackingProvider,
        private val progress: TrackingProgressProvider?,
        private val writer: TrackingHistoryWriter?
    ) : TrackingImportEndpoint {
        override val capabilities: Set<TrackingCapability> = provider.descriptor.capabilities
            .filterTo(mutableSetOf()) { capability ->
                when (capability) {
                    TrackingCapability.WATCHED_READ, TrackingCapability.PROGRESS_READ -> progress != null
                    TrackingCapability.WATCHED_WRITE -> writer != null
                    TrackingCapability.PROGRESS_WRITE ->
                        provider.scrobbler != null && TrackingCapability.SCROBBLE in provider.descriptor.capabilities
                    else -> false
                }
            }

        override suspend fun read(): TrackingImportSnapshot {
            val progress = progress ?: return TrackingImportSnapshot()
            progress.refresh(TrackingRefreshIntent.USER_INITIATED)
            withTimeoutOrNull(READ_TIMEOUT_MS) { progress.remoteProgressLoaded.first { it } }
            val all = progress.allProgress.first()
            return TrackingImportSnapshot(
                watched = collectWatched(
                    items = progress.watchedItems.first(),
                    progress = all,
                    movieIds = progress.watchedMovieIds.first(),
                    episodes = progress.watchedShowEpisodes()
                ),
                inProgress = all.filter { it.isInProgress() }
            )
        }

        override suspend fun writeWatched(items: List<WatchedItem>): TrackingImportWriteResult {
            val writer = writer ?: return TrackingImportWriteResult(0, items.size)
            val result = writer.addToHistory(profileId, items.map(WatchedItem::toTrackingHistoryItem))
            val written = (result.attemptedCount - result.notFoundCount).coerceIn(0, items.size)
            return TrackingImportWriteResult(written, items.size - written)
        }

        override suspend fun writeProgress(items: List<WatchProgress>): TrackingImportWriteResult {
            val scrobbler = provider.scrobbler ?: return TrackingImportWriteResult(0, items.size)
            var written = 0
            items.forEach { item ->
                try {
                    // A pause leaves a resume point at this percentage, as it does from the player.
                    scrobbler.scrobble(
                        TrackingScrobbleAction.PAUSE,
                        TrackingScrobbleEvent(item.toTrackingMediaReference(), item.progressPercentage * 100.0)
                    )
                    written++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "${source.name} didn't take a resume point for ${item.contentId}", error)
                }
            }
            return TrackingImportWriteResult(written, items.size - written)
        }

        override suspend fun finish() {
            progress?.refresh(TrackingRefreshIntent.USER_INITIATED)
        }
    }

    /** Fetch Sync: this TV's own watch state for the profile. */
    private inner class LocalImportEndpoint(private val profileId: Int) : TrackingImportEndpoint {
        override val source = WatchProgressSource.FETCH_SYNC
        override val capabilities = setOf(
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE
        )

        override suspend fun read(): TrackingImportSnapshot {
            val progress = watchProgressPreferences.getAllRawEntries(profileId).values.toList()
            return TrackingImportSnapshot(
                watched = collectWatched(
                    items = watchedItemsPreferences.getAllItems(profileId),
                    progress = progress,
                    movieIds = emptySet(),
                    episodes = emptyMap()
                ),
                inProgress = progress.filter { it.isInProgress() }
            )
        }

        override suspend fun writeWatched(items: List<WatchedItem>): TrackingImportWriteResult {
            watchedItemsPreferences.markAsWatchedBatch(items, profileId)
            return TrackingImportWriteResult(items.size)
        }

        override suspend fun writeProgress(items: List<WatchProgress>): TrackingImportWriteResult {
            watchProgressPreferences.saveProgressBatch(items.map { it.copy(source = WatchProgress.SOURCE_LOCAL) }, profileId)
            return TrackingImportWriteResult(items.size)
        }
    }

    private companion object {
        const val TAG = "TrackingImport"
        const val READ_TIMEOUT_MS = 30_000L
    }
}

/**
 * Everything a source knows as watched, one entry per title or episode. Providers differ in what
 * they expose: some list watched items with times, others only ids, and finished resume points
 * carry the time they were finished.
 */
internal fun collectWatched(
    items: List<WatchedItem>,
    progress: List<WatchProgress>,
    movieIds: Set<String>,
    episodes: Map<String, Set<Pair<Int, Int>>>
): List<WatchedItem> = buildList {
    addAll(items)
    progress.filter { it.isCompleted() }.forEach { entry ->
        add(
            WatchedItem(
                contentId = entry.contentId,
                contentType = entry.contentType,
                title = entry.name,
                season = entry.season,
                episode = entry.episode,
                watchedAt = entry.lastWatched,
                poster = entry.poster
            )
        )
    }
    movieIds.forEach { id -> add(WatchedItem(contentId = id, contentType = "movie", title = "", watchedAt = 0L)) }
    episodes.forEach { (contentId, watched) ->
        watched.forEach { (season, episode) ->
            add(
                WatchedItem(
                    contentId = contentId,
                    contentType = "series",
                    title = "",
                    season = season,
                    episode = episode,
                    watchedAt = 0L
                )
            )
        }
    }
}

internal fun WatchedItem.toTrackingHistoryItem(): TrackingHistoryItem = TrackingHistoryItem(
    media = buildTrackingMediaReference(
        contentType = contentType,
        parentMetaId = contentId,
        title = title.takeIf(String::isNotBlank),
        releaseInfo = releaseInfo,
        seasonNumber = season,
        episodeNumber = episode
    ),
    watchedAtEpochMs = watchedAt.takeIf { it > 0L }
)

internal fun WatchProgress.toTrackingMediaReference(): TrackingMediaReference = buildTrackingMediaReference(
    contentType = contentType,
    parentMetaId = contentId,
    videoId = videoId.takeIf { it.isNotBlank() && it != contentId },
    title = name.takeIf { it.isNotBlank() && it != contentId },
    seasonNumber = season,
    episodeNumber = episode,
    episodeTitle = episodeTitle
)
