package com.fetch.tv.core.tracking

import android.util.Log
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.TraktSettingsDataStore
import com.fetch.tv.data.local.WatchProgressPreferences
import com.fetch.tv.data.local.WatchProgressSource
import com.fetch.tv.data.local.WatchedItemsPreferences
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.util.runSuspendCatching
import com.fetch.tv.data.anilist.AniListApi
import com.fetch.tv.data.anilist.AniListAuthStore
import com.fetch.tv.data.mal.MalApi
import com.fetch.tv.data.mal.MalAuthStore
import com.fetch.tv.data.stremio.StremioAccountApi
import com.fetch.tv.domain.model.Video
import com.fetch.tv.domain.repository.MetaRepository
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface TrackingImportStatus {
    data object Idle : TrackingImportStatus
    data class Running(val source: TrackingImportOrigin, val target: WatchProgressSource) : TrackingImportStatus
    data class Done(val result: TrackingImportResult) : TrackingImportStatus
    data class Failed(val source: TrackingImportOrigin, val target: WatchProgressSource) : TrackingImportStatus
}

/** Signing a Stremio account in for an import, through Stremio's link code. */
sealed interface StremioLinkState {
    data object Idle : StremioLinkState
    data object Creating : StremioLinkState
    data class Waiting(val code: String, val link: String, val expiresAtEpochMs: Long) : StremioLinkState
    data class Error(val reason: Reason) : StremioLinkState {
        enum class Reason { NETWORK, EXPIRED, PROFILE_CHANGED }
    }
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
    private val profileManager: ProfileManager,
    private val stremioApi: StremioAccountApi,
    private val metaRepository: MetaRepository,
    private val aniListAuth: AniListAuthStore,
    private val aniListApi: AniListApi,
    private val malAuth: MalAuthStore,
    private val malApi: MalApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val importer = TrackingImporter()
    private val _status = MutableStateFlow<TrackingImportStatus>(TrackingImportStatus.Idle)
    val status: StateFlow<TrackingImportStatus> = _status.asStateFlow()
    private val _stremioLink = MutableStateFlow<StremioLinkState>(StremioLinkState.Idle)
    val stremioLink: StateFlow<StremioLinkState> = _stremioLink.asStateFlow()
    private var stremioLinkJob: Job? = null

    /** The current source of truth, after falling back for a provider that is no longer connected. */
    suspend fun sourceOfTruth(): WatchProgressSource =
        effectiveWatchProgressSource(settingsDataStore.watchProgressSource.first(), providers::isAuthenticated)

    /** Sources the user can import from: every connected one except the source of truth. */
    fun importSources(target: WatchProgressSource, connected: Set<TrackingProviderId>): List<WatchProgressSource> =
        availableWatchProgressSources(connected).filter { it != target }

    fun start(source: WatchProgressSource) {
        run(TrackingImportOrigin.Connected(source)) { profileId -> endpoint(source, profileId) }
    }

    /**
     * Shows a Stremio link code and waits for it to be approved; the import starts by itself
     * once it is. The account is only read, and signed out again when the import ends.
     */
    fun startStremioLink() {
        if (_status.value is TrackingImportStatus.Running) return
        stremioLinkJob?.cancel()
        // The import lands in the profile that asked for it, not whichever is active at approval.
        val profileId = profileManager.activeProfileId.value
        stremioLinkJob = scope.launch {
            _stremioLink.value = StremioLinkState.Creating
            val code = try {
                stremioApi.createLinkCode()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Couldn't get a Stremio link code", error)
                _stremioLink.value = StremioLinkState.Error(StremioLinkState.Error.Reason.NETWORK)
                return@launch
            }
            val expiresAt = System.currentTimeMillis() + STREMIO_LINK_TIMEOUT_MS
            _stremioLink.value = StremioLinkState.Waiting(code.code, code.link, expiresAt)
            var authKey: String? = null
            var failures = 0
            while (authKey == null && System.currentTimeMillis() < expiresAt) {
                delay(STREMIO_LINK_POLL_MS)
                authKey = try {
                    stremioApi.readLinkCode(code.code).also { failures = 0 }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (++failures >= STREMIO_LINK_MAX_FAILURES) {
                        Log.w(TAG, "Stopped waiting for the Stremio link code", error)
                        _stremioLink.value = StremioLinkState.Error(StremioLinkState.Error.Reason.NETWORK)
                        return@launch
                    }
                    null
                }
            }
            if (authKey == null) {
                _stremioLink.value = StremioLinkState.Error(StremioLinkState.Error.Reason.EXPIRED)
                return@launch
            }
            val reader = StremioImportReader(authKey)
            if (profileManager.activeProfileId.value != profileId) {
                // The profile changed while the phone approved: importing now would write another
                // profile's history, and its source of truth is a different one too.
                reader.close()
                _stremioLink.value = StremioLinkState.Error(StremioLinkState.Error.Reason.PROFILE_CHANGED)
                return@launch
            }
            _stremioLink.value = StremioLinkState.Idle
            // Another import got there first: don't leave the session signed in.
            if (!run(TrackingImportOrigin.Stremio, profileId) { reader }) reader.close()
        }
    }

    /** Imports the connected AniList account's anime into the source of truth. */
    fun startAniList() {
        run(TrackingImportOrigin.AniList) { profileId ->
            val state = aniListAuth.current(profileId)
            val token = state.accessToken?.takeIf { state.isAuthenticated() }
            val userId = state.userId
            if (token == null || userId == null) null else AniListImportReader(token, userId)
        }
    }

    /** Imports the connected MyAnimeList account's anime into the source of truth. */
    fun startMal() {
        run(TrackingImportOrigin.MyAnimeList) { profileId ->
            malAuth.validToken(profileId)?.let { token ->
                object : TrackingImportReader {
                    override val origin = TrackingImportOrigin.MyAnimeList
                    override val capabilities = setOf(TrackingCapability.WATCHED_READ)
                    override suspend fun read() = buildAnimeListSnapshot(malApi.animeList(token))
                }
            }
        }
    }

    fun cancelStremioLink() {
        stremioLinkJob?.cancel()
        stremioLinkJob = null
        _stremioLink.value = StremioLinkState.Idle
    }

    /** False when another import is already running and this one didn't start. */
    private fun run(
        origin: TrackingImportOrigin,
        profileId: Int = profileManager.activeProfileId.value,
        reader: suspend (profileId: Int) -> TrackingImportReader?
    ): Boolean {
        if (_status.value is TrackingImportStatus.Running) return false
        scope.launch {
            val target = sourceOfTruth()
            if (origin == TrackingImportOrigin.Connected(target)) return@launch
            _status.value = TrackingImportStatus.Running(origin, target)
            var from: TrackingImportReader? = null
            _status.value = try {
                from = reader(profileId)
                val into = endpoint(target, profileId)?.let { endpoint ->
                    ProfileBoundImportEndpoint(endpoint, profileId) { profileManager.activeProfileId.value }
                }
                if (from == null || into == null) {
                    TrackingImportStatus.Failed(origin, target)
                } else {
                    val result = importer.import(from, into)
                    // Switched profile mid-import: what was written stays in the right profile,
                    // but the rest was refused, so report it as not done.
                    if (into.profileChanged) TrackingImportStatus.Failed(origin, target) else TrackingImportStatus.Done(result)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Import from $origin into ${target.name} failed", error)
                TrackingImportStatus.Failed(origin, target)
            } finally {
                (from as? StremioImportReader)?.close()
            }
        }
        return true
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

    /** A Stremio account's library, read once with an auth key that is signed out afterwards. */
    private inner class StremioImportReader(private val authKey: String) : TrackingImportReader {
        override val origin = TrackingImportOrigin.Stremio
        override val capabilities = setOf(TrackingCapability.WATCHED_READ, TrackingCapability.PROGRESS_READ)

        override suspend fun read(): TrackingImportSnapshot {
            val items = stremioApi.library(authKey)
            val lookups = Semaphore(STREMIO_META_CONCURRENCY)
            // Episode lists are fetched in parallel up front; the snapshot is then built from them.
            val videos = withContext(Dispatchers.IO) {
                items.filter { it.isSeries && (!it.watched.isNullOrBlank() || it.timeOffset > 0L) }
                    .map { item -> async { item.id to lookups.withPermit { episodesOf(item) } } }
                    .awaitAll()
                    .toMap()
            }
            return buildStremioSnapshot(items) { item -> videos[item.id] }
        }

        private suspend fun episodesOf(item: StremioLibraryItem): List<Video>? = try {
            withTimeoutOrNull(STREMIO_META_TIMEOUT_MS) {
                metaRepository.getMetaFromAllAddons(type = "series", id = item.id)
                    .first { it is NetworkResult.Success || it is NetworkResult.Error }
                    .let { (it as? NetworkResult.Success)?.data?.videos }
                    ?.takeIf { it.isNotEmpty() }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "No episode list for a Stremio library item", error)
            null
        }

        suspend fun close() {
            withContext(NonCancellable) {
                runSuspendCatching { stremioApi.logout(authKey) }
                    .onFailure { Log.w(TAG, "Couldn't sign the Stremio import session out", it) }
            }
        }
    }

    private inner class AniListImportReader(
        private val token: String,
        private val userId: Long
    ) : TrackingImportReader {
        override val origin = TrackingImportOrigin.AniList
        override val capabilities = setOf(TrackingCapability.WATCHED_READ)

        override suspend fun read(): TrackingImportSnapshot = buildAnimeListSnapshot(aniListApi.animeLists(token, userId))
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
        const val STREMIO_LINK_POLL_MS = 3_000L
        /** Stremio doesn't say how long a link code lives; stop asking after this long. */
        const val STREMIO_LINK_TIMEOUT_MS = 10L * 60_000L
        const val STREMIO_LINK_MAX_FAILURES = 5
        const val STREMIO_META_CONCURRENCY = 4
        const val STREMIO_META_TIMEOUT_MS = 20_000L
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
