package com.fetch.tv.data.website

import android.os.SystemClock
import android.util.Log
import com.fetch.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.fetch.tv.core.tracking.TrackingCapability
import com.fetch.tv.core.tracking.TrackingHistoryItem
import com.fetch.tv.core.tracking.TrackingHistoryWriter
import com.fetch.tv.core.tracking.TrackingLibraryProvider
import com.fetch.tv.core.tracking.TrackingListManagementCapabilities
import com.fetch.tv.core.tracking.TrackingListManager
import com.fetch.tv.core.tracking.TrackingMediaKind
import com.fetch.tv.core.tracking.TrackingMediaReference
import com.fetch.tv.core.tracking.TrackingMutationResult
import com.fetch.tv.core.tracking.TrackingProgressProvider
import com.fetch.tv.core.tracking.TrackingProvider
import com.fetch.tv.core.tracking.TrackingProviderDescriptor
import com.fetch.tv.core.tracking.TrackingProviderId
import com.fetch.tv.core.tracking.TrackingRefreshIntent
import com.fetch.tv.core.tracking.TrackingScrobbleAction
import com.fetch.tv.core.tracking.TrackingScrobbleEvent
import com.fetch.tv.core.tracking.TrackingScrobbler
import com.fetch.tv.core.tracking.scrobbleDiagnosticSummary
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.repository.RemoteProgressWriteDeduplicator
import com.fetch.tv.domain.model.LibraryEntryInput
import com.fetch.tv.domain.model.LibraryListPrivacy
import com.fetch.tv.domain.model.ListMembershipChanges
import com.fetch.tv.domain.model.ListMembershipSnapshot
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The Fetch website as a tracking provider, next to Trakt, Simkl and MDBList. "Connected"
// means this TV is paired with a website: plays, scrobbles and watched marks then reach it
// like any connected provider's, and choosing it as the watch progress or library source
// makes Continue Watching, watched badges and the Library read from it. The website only
// knows titles by IMDb id; anything else stays on this TV.

private const val TAG = "FetchWebsiteTracking"

/** The website's longest position; a duration past it isn't sent. */
private const val MAX_PLAYBACK_SECONDS = 12L * 60 * 60

@Singleton
class FetchWebsiteTrackingProvider @Inject constructor(
    repository: FetchWebsiteTrackingRepository,
    override val scrobbler: FetchWebsiteTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.FETCH_WEBSITE,
        displayName = "Fetch website",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE
        )
    )
    override val isAuthenticated = repository.isAuthenticated
}

@Singleton
class FetchWebsiteTrackingScrobbler @Inject constructor(
    private val repository: FetchWebsiteTrackingRepository,
    private val client: FetchWebsiteClient
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.FETCH_WEBSITE

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        val body = fetchWebsiteScrobbleBody(event)
        if (body == null) {
            Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "fetch_website adapter skipped action=${action.wireValue} reason=no_imdb_id ${event.scrobbleDiagnosticSummary()}")
            return
        }
        repository.asProfile { profileId, token -> client.scrobble(profileId, token, action.wireValue, body) }
        // A pause or stop moved a resume point, or recorded a watch: read it back.
        if (action != TrackingScrobbleAction.START) repository.invalidate()
    }
}

@Singleton
class FetchWebsiteTrackingHistoryWriter @Inject constructor(
    private val repository: FetchWebsiteTrackingRepository,
    private val client: FetchWebsiteClient
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.FETCH_WEBSITE

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        // A show-level mark would mean "every episode"; the caller reports those one by one.
        val bodies = items.filterNot(TrackingHistoryItem::isWholeSeriesMark).map(::fetchWebsiteWatchBody)
        val pushable = bodies.filterNotNull()
        if (pushable.isEmpty()) return TrackingMutationResult(0, notFoundCount = bodies.size)
        repository.asProfile(profileId) { websiteProfileId, token ->
            pushable.forEach { body -> client.recordWatch(websiteProfileId, token, body) }
        } ?: return TrackingMutationResult(0)
        repository.mutate { snapshot -> snapshot.withWatched(items) }
        return TrackingMutationResult(pushable.size, notFoundCount = bodies.size - pushable.size)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        // The website unmarks episodes of numbered seasons only.
        val removable = items.mapNotNull { it.websiteTarget() }.filter { it.season == null || it.season > 0 }
        if (removable.isEmpty()) return TrackingMutationResult(0, notFoundCount = items.size)
        repository.asProfile(profileId) { websiteProfileId, token ->
            removable.forEach { target ->
                if (target.season == null || target.episode == null) {
                    client.unwatchMovie(websiteProfileId, token, target.imdbId)
                } else {
                    client.unwatchEpisode(websiteProfileId, token, target.imdbId, target.season, target.episode)
                }
            }
        } ?: return TrackingMutationResult(0)
        repository.mutate { snapshot -> snapshot.withoutWatched(removable) }
        return TrackingMutationResult(removable.size, notFoundCount = items.size - removable.size)
    }
}

@Singleton
class FetchWebsiteTrackingProgressProvider @Inject constructor(
    private val repository: FetchWebsiteTrackingRepository,
    private val client: FetchWebsiteClient,
    layoutPreferences: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeDeduplicator = RemoteProgressWriteDeduplicator()
    private val projection = with(repository) { repository.progress.polledWhileCollected() }

    override val providerId = TrackingProviderId.FETCH_WEBSITE
    override val isAuthenticated: Flow<Boolean> = repository.isAuthenticated
    override val allProgress = projection.map { it.progress }.distinctUntilChanged()
    override val remoteProgressLoaded = repository.snapshot.map { it != null }.distinctUntilChanged()
    override val nextUpSeeds = combine(projection, layoutPreferences.nextUpFromFurthestEpisode) { projection, furthest ->
        projection.nextUp(furthest)
    }.distinctUntilChanged()
    override val watchedMovieIds = projection.map { it.watchedMovieIds }.distinctUntilChanged()
    override val watchedItems: Flow<List<WatchedItem>> = projection.map { it.watchedItems }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        projection.map { it.episodeProgress(contentId) }.distinctUntilChanged()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        projection.map { it.isWatched(contentId, season, episode) }.distinctUntilChanged()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        return repository.progress.value.watchedShowEpisodes
    }

    // The website knows a show by its IMDb id alone, so there are no other ids to tie together.
    override suspend fun showIdSiblings(): Map<String, Set<String>> = emptyMap()

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? =
        repository.progress.value.isWatchedByVideoId(videoId, episode)

    override suspend fun refresh(intent: TrackingRefreshIntent) = repository.refresh(intent)

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        repository.ensureLoaded()
        val ids = repository.currentSnapshot()?.playbackIdsFor(contentId, season, episode).orEmpty()
        if (ids.isEmpty()) return
        repository.asProfile { profileId, token ->
            ids.forEach { id ->
                try {
                    client.deletePlayback(profileId, token, id)
                } catch (error: FetchWebsiteException) {
                    // Already gone is what was asked for.
                    if (error.status != 404) throw error
                }
            }
        }
        repository.mutate { snapshot -> snapshot.copy(playback = snapshot.playback.filterNot { it.id in ids }) }
    }

    /**
     * Each save from the player moves the website's resume point to the exact second, so a TV
     * switched off mid-film resumes where it was rather than at its last pause. A completed
     * title is left to the scrobble stop, which records the watch.
     */
    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) {
        val playback = progress.toWebsitePlayback() ?: return
        repository.mutate { snapshot -> snapshot.withPlayback(playback) }
        if (quiet || progress.isCompleted()) return
        val body = fetchWebsitePlaybackBody(progress) ?: return
        val key = "${progress.contentId}:${progress.season ?: -1}:${progress.episode ?: -1}"
        if (!writeDeduplicator.shouldSend(0, key, progress, SystemClock.elapsedRealtime())) return
        scope.launch {
            try {
                repository.asProfile { profileId, token -> client.putPlayback(profileId, token, body) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Couldn't save the resume point on the website", error)
            }
        }
    }

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) {
        repository.mutate { snapshot ->
            val ids = snapshot.playbackIdsFor(contentId, season, episode).toSet()
            snapshot.copy(playback = snapshot.playback.filterNot { it.id in ids })
        }
    }

    override fun clearOptimistic() = Unit

    override fun isHiddenFromProgress(contentId: String): Boolean = repository.progress.value.isHidden(contentId)

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress

    // Titles the website can't hold (no IMDb id) keep their progress on this TV.
    override fun retainsLocalProgress(contentId: String): Boolean = !contentId.startsWith("tt")

    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = retainsLocalProgress(item.contentId)
}

@Singleton
class FetchWebsiteTrackingLibraryProvider @Inject constructor(
    private val repository: FetchWebsiteTrackingRepository,
    private val client: FetchWebsiteClient
) : TrackingLibraryProvider {
    private val projection = with(repository) { repository.library.polledWhileCollected() }

    override val providerId = TrackingProviderId.FETCH_WEBSITE
    override val listManager: TrackingListManager = FetchWebsiteListManager(repository, client)
    override val isAuthenticated: Flow<Boolean> = repository.isAuthenticated
    override val isRefreshing: Flow<Boolean> = repository.isRefreshing
    override val items = projection.map { it.entries }.distinctUntilChanged()
    override val tabs = projection.map { it.tabs }.distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean = fetchWebsiteListId(key) != null

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
        repository.library.map { it.membership(itemId, itemType) }.distinctUntilChanged()

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> {
        val watchlist = repository.library.value.watchlistKey ?: return currentMembership
        return currentMembership + (watchlist to (currentMembership[watchlist] != true))
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        repository.ensureLoaded()
        val library = repository.library.value
        val current = item.websiteImdbId()?.let { library.membership(it, item.itemType) }.orEmpty()
        return ListMembershipSnapshot(library.tabs.associate { tab -> tab.key to (tab.key in current) })
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        val imdbId = item.websiteImdbId()
            ?: throw IllegalStateException("The Fetch website keeps lists of titles with an IMDb id only")
        val type = fetchWebsiteItemType(item.itemType)
        repository.ensureLoaded()
        val current = repository.library.value.membership(imdbId, item.itemType)
        val wanted = changes.desiredMembership.filterKeys(::recognizesListKey)
        val toAdd = wanted.filter { (key, member) -> member && key !in current }.keys
        val toRemove = wanted.filter { (key, member) -> !member && key in current }.keys
        if (toAdd.isEmpty() && toRemove.isEmpty()) return
        repository.asProfile { profileId, token ->
            toAdd.mapNotNull(::fetchWebsiteListId).forEach { listId ->
                client.addListItem(profileId, token, listId, type, imdbId, item.title)
            }
            toRemove.mapNotNull(::fetchWebsiteListId).forEach { listId ->
                client.removeListItem(profileId, token, listId, type, imdbId)
            }
        } ?: throw IllegalStateException("Sign in to the Fetch website first")
        repository.mutate { snapshot ->
            snapshot.withListMembership(toAdd.mapNotNull(::fetchWebsiteListId), toRemove.mapNotNull(::fetchWebsiteListId), type, imdbId, item)
        }
        repository.refreshAsync()
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = repository.refresh(intent)
}

/** Lists on the website: its own custom lists can be made, renamed and deleted from the TV. */
internal class FetchWebsiteListManager(
    private val repository: FetchWebsiteTrackingRepository,
    private val client: FetchWebsiteClient
) : TrackingListManager {
    // Website lists belong to the profile and are never shared, so there is one privacy.
    override val capabilities = TrackingListManagementCapabilities(privacyOptions = listOf(LibraryListPrivacy.PRIVATE))

    override suspend fun createList(name: String, description: String?, privacy: LibraryListPrivacy) {
        val created = repository.asProfile { profileId, token -> client.createList(profileId, token, name.trim()) }
            ?: throw IllegalStateException("Sign in to the Fetch website first")
        repository.mutate { it.copy(lists = it.lists + created, listItems = it.listItems + (created.id to emptyList())) }
        repository.refresh(TrackingRefreshIntent.USER_INITIATED)
    }

    override suspend fun updateList(key: String, name: String, description: String?, privacy: LibraryListPrivacy) {
        val listId = fetchWebsiteListId(key) ?: throw IllegalArgumentException("Not a Fetch website list")
        repository.asProfile { profileId, token -> client.renameList(profileId, token, listId, name.trim()) }
            ?: throw IllegalStateException("Sign in to the Fetch website first")
        repository.mutate { snapshot ->
            snapshot.copy(lists = snapshot.lists.map { if (it.id == listId) it.copy(name = name.trim()) else it })
        }
        repository.refresh(TrackingRefreshIntent.USER_INITIATED)
    }

    override suspend fun deleteList(key: String) {
        val listId = fetchWebsiteListId(key) ?: throw IllegalArgumentException("Not a Fetch website list")
        try {
            repository.asProfile { profileId, token -> client.deleteList(profileId, token, listId) }
                ?: throw IllegalStateException("Sign in to the Fetch website first")
        } catch (error: FetchWebsiteException) {
            // The profile's watchlist and collection can't be deleted; the website says why.
            throw IllegalStateException(error.message, error)
        }
        repository.mutate { snapshot ->
            snapshot.copy(lists = snapshot.lists.filterNot { it.id == listId }, listItems = snapshot.listItems - listId)
        }
        repository.refresh(TrackingRefreshIntent.USER_INITIATED)
    }
}

// ─── Request bodies ───

internal data class FetchWebsiteTarget(val imdbId: String, val season: Int?, val episode: Int?)

/** What the website calls this title: films by their IMDb id, episodes by the series'. Null without one. */
internal fun TrackingMediaReference.websiteTarget(): FetchWebsiteTarget? {
    val imdbId = ids.imdb?.trim()?.takeIf { it.startsWith("tt") }
        ?: catalog?.contentId?.trim()?.takeIf { it.startsWith("tt") }?.substringBefore(':')
        ?: return null
    if (kind == TrackingMediaKind.MOVIE) return FetchWebsiteTarget(imdbId, null, null)
    val episode = episode ?: return null
    val season = episode.season ?: return null
    return FetchWebsiteTarget(imdbId, season, episode.number)
}

internal fun fetchWebsiteScrobbleBody(event: TrackingScrobbleEvent): JsonObject? {
    val target = event.media.websiteTarget() ?: return null
    return buildJsonObject {
        put("type", if (target.season == null) "movie" else "episode")
        put("imdbId", target.imdbId)
        if (target.season != null && target.episode != null) {
            put("seriesImdbId", target.imdbId)
            put("season", target.season)
            put("episode", target.episode)
        }
        put("progress", event.progressPercent.coerceIn(0.0, 100.0))
    }
}

internal fun fetchWebsiteWatchBody(item: TrackingHistoryItem): JsonObject? {
    val target = item.media.websiteTarget() ?: return null
    return buildJsonObject {
        put("type", if (target.season == null) "movie" else "episode")
        put("imdbId", target.imdbId)
        if (target.season != null && target.episode != null) {
            put("seriesImdbId", target.imdbId)
            put("season", target.season)
            put("episode", target.episode)
        }
        item.watchedAtEpochMs?.let { put("watchedAt", Instant.ofEpochMilli(it).toString()) }
    }
}

/** A resume point to `PUT /playback`, in whole seconds when the player knows them. */
internal fun fetchWebsitePlaybackBody(progress: WatchProgress): JsonObject? {
    if (!progress.contentId.startsWith("tt")) return null
    val isEpisode = progress.season != null && progress.episode != null
    if (!isEpisode && !progress.contentType.equals("movie", ignoreCase = true)) return null
    val durationSeconds = progress.duration / 1000L
    val positionSeconds = progress.position / 1000L
    return buildJsonObject {
        put("type", if (isEpisode) "episode" else "movie")
        put("imdbId", progress.contentId)
        if (isEpisode) {
            put("seriesImdbId", progress.contentId)
            put("season", progress.season)
            put("episode", progress.episode)
        }
        if (durationSeconds in 1..MAX_PLAYBACK_SECONDS && positionSeconds in 0..durationSeconds) {
            put("positionSeconds", positionSeconds)
            put("durationSeconds", durationSeconds)
        } else {
            put("progress", (progress.progressPercentage * 100f * 10f).roundToInt() / 10.0)
        }
    }
}

private fun LibraryEntryInput.websiteImdbId(): String? =
    imdbId?.takeIf { it.startsWith("tt") } ?: itemId.takeIf { it.startsWith("tt") }?.substringBefore(':')

private fun WatchProgress.toWebsitePlayback(): WebsitePlayback? {
    if (!contentId.startsWith("tt")) return null
    val isEpisode = season != null && episode != null
    return WebsitePlayback(
        id = trackingProviderItemId?.takeIf { trackingProviderId == FETCH_WEBSITE_PROVIDER_STORAGE_ID }
            ?: "local:$contentId:${season ?: -1}:${episode ?: -1}",
        type = if (isEpisode) "episode" else "movie",
        imdbId = contentId,
        season = season,
        episode = episode,
        progress = (progressPercentage * 100f).toDouble(),
        positionSeconds = (position / 1000L).takeIf { duration > 0L },
        durationSeconds = (duration / 1000L).takeIf { it > 0L },
        pausedAt = Instant.ofEpochMilli(lastWatched.takeIf { it > 0L } ?: System.currentTimeMillis()).toString(),
        name = name.takeIf { it.isNotBlank() && it != contentId },
        poster = poster
    )
}

// ─── Optimistic snapshot edits, confirmed by the next read ───

internal fun FetchWebsiteTrackingSnapshot.withPlayback(entry: WebsitePlayback): FetchWebsiteTrackingSnapshot {
    val others = playback.filterNot { it.imdbId == entry.imdbId && it.season == entry.season && it.episode == entry.episode }
    val previous = playback.firstOrNull { it.imdbId == entry.imdbId && it.season == entry.season && it.episode == entry.episode }
    val merged = previous?.let { entry.copy(id = it.id, name = entry.name ?: it.name, poster = entry.poster ?: it.poster) } ?: entry
    return copy(playback = listOf(merged) + others)
}

internal fun FetchWebsiteTrackingSnapshot.withWatched(items: Collection<TrackingHistoryItem>): FetchWebsiteTrackingSnapshot {
    var state = watchState
    var resume = playback
    items.forEach { item ->
        val target = item.media.websiteTarget() ?: return@forEach
        val at = Instant.ofEpochMilli(item.watchedAtEpochMs ?: System.currentTimeMillis()).toString()
        state = if (target.season == null || target.episode == null) {
            state.copy(movies = state.movies.filterNot { it.imdbId == target.imdbId } +
                WebsiteWatchedMovie(target.imdbId, at, item.media.title, item.media.posterUrl))
        } else {
            val previous = state.episodes.firstOrNull { it.imdbId == target.imdbId }
            state.copy(episodes = state.episodes.filterNot {
                it.imdbId == target.imdbId && it.season == target.season && it.episode == target.episode
            } + WebsiteWatchedEpisode(target.imdbId, target.season, target.episode, at, previous?.name ?: item.media.title, previous?.poster))
        }
        // Watching a title clears its resume point, as on the website.
        resume = resume.filterNot { it.imdbId == target.imdbId && it.season == target.season && it.episode == target.episode }
    }
    return copy(watchState = state, playback = resume)
}

internal fun FetchWebsiteTrackingSnapshot.withoutWatched(targets: Collection<FetchWebsiteTarget>): FetchWebsiteTrackingSnapshot {
    var state = watchState
    targets.forEach { target ->
        state = if (target.season == null || target.episode == null) {
            state.copy(movies = state.movies.filterNot { it.imdbId == target.imdbId })
        } else {
            state.copy(episodes = state.episodes.filterNot {
                it.imdbId == target.imdbId && it.season == target.season && it.episode == target.episode
            })
        }
    }
    return copy(watchState = state)
}

internal fun FetchWebsiteTrackingSnapshot.withListMembership(
    addTo: Collection<String>,
    removeFrom: Collection<String>,
    type: String,
    imdbId: String,
    input: LibraryEntryInput
): FetchWebsiteTrackingSnapshot {
    val added = WebsiteListItem(
        type = type,
        imdbId = imdbId,
        addedAt = Instant.now().toString(),
        title = input.title,
        metadata = WebsiteListItemMetadata(name = input.title, poster = input.poster, year = input.year)
    )
    val updated = listItems.toMutableMap()
    addTo.forEach { listId ->
        val items = updated[listId].orEmpty()
        if (items.none { it.type == type && it.imdbId == imdbId }) updated[listId] = listOf(added) + items
    }
    removeFrom.forEach { listId ->
        updated[listId] = updated[listId].orEmpty().filterNot { it.type == type && it.imdbId == imdbId }
    }
    return copy(listItems = updated)
}
