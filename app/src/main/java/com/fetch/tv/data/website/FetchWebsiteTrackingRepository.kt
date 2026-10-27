package com.fetch.tv.data.website

import android.util.Log
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tracking.TrackingRefreshIntent
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "FetchWebsiteTracking"

/**
 * The Fetch website's watch state and lists for the profile active on this TV: its resume
 * points, everything it has watched, and its lists with their items.
 *
 * Read whole (a handful of requests) and kept in memory. While something on screen shows it,
 * it is read again every [POLL_INTERVAL_MS], so a change made on the website reaches the TV
 * within a minute; writes from this TV land in it straight away and are confirmed by the next
 * read. A snapshot belongs to the TV profile slot it was read for and is never shown for another.
 */
@Singleton
class FetchWebsiteTrackingRepository @Inject constructor(
    private val store: FetchWebsiteStore,
    private val client: FetchWebsiteClient,
    private val websiteSync: dagger.Lazy<FetchWebsiteSyncService>,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val mutationGeneration = AtomicLong()
    private val _snapshot = MutableStateFlow<FetchWebsiteTrackingSnapshot?>(null)
    private val _refreshing = MutableStateFlow(false)

    @Volatile
    private var lastFailureAtMs = 0L

    val isAuthenticated: StateFlow<Boolean> = store.session
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, store.isPaired)

    /** The active profile's snapshot; null when not paired, not read yet, or read for another profile. */
    val snapshot: StateFlow<FetchWebsiteTrackingSnapshot?> = combine(
        _snapshot,
        profileManager.activeProfileId,
        store.session
    ) { snapshot, slot, session ->
        snapshot?.takeIf { session != null && it.slot == slot }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    internal val progress: StateFlow<FetchWebsiteProgressProjection> = snapshot
        .map(::FetchWebsiteProgressProjection)
        .stateIn(scope, SharingStarted.Eagerly, FetchWebsiteProgressProjection.Empty)

    internal val library: StateFlow<FetchWebsiteLibraryProjection> = snapshot
        .map(::FetchWebsiteLibraryProjection)
        .stateIn(scope, SharingStarted.Eagerly, FetchWebsiteLibraryProjection.Empty)

    val isRefreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun currentSnapshot(): FetchWebsiteTrackingSnapshot? = snapshot.value

    suspend fun ensureLoaded() {
        if (currentSnapshot() == null) refresh(TrackingRefreshIntent.AUTOMATIC)
    }

    /**
     * Reads the website again. An automatic refresh is skipped while the last read is younger
     * than [POLL_INTERVAL_MS], and for a while after a failure; any other always runs.
     */
    suspend fun refresh(intent: TrackingRefreshIntent) {
        if (!store.isPaired) {
            _snapshot.value = null
            return
        }
        refreshMutex.withLock {
            val slot = profileManager.activeProfileId.value
            val now = System.currentTimeMillis()
            if (intent == TrackingRefreshIntent.AUTOMATIC) {
                val current = currentSnapshot()
                if (current != null && now - current.fetchedAtMs in 0 until POLL_INTERVAL_MS) return
                if (now - lastFailureAtMs in 0 until ERROR_RETRY_MS) return
            }
            _refreshing.value = true
            try {
                val generation = mutationGeneration.get()
                val read = readProfile(slot)
                if (profileManager.activeProfileId.value != slot) return
                if (mutationGeneration.get() != generation) {
                    // A write from this TV landed while reading; what was read may predate it.
                    // Keep the optimistic state and read again shortly, not at once: saves can come
                    // every second while seeking, and each would otherwise restart the read.
                    _snapshot.update { it?.copy(fetchedAtMs = 0L) }
                    scope.launch {
                        delay(CONFLICT_RETRY_MS)
                        refresh(TrackingRefreshIntent.AUTOMATIC)
                    }
                    return
                }
                _snapshot.value = read
                lastFailureAtMs = 0L
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailureAtMs = now
                // Never thrown: choosing the source refreshes from a scope with nowhere to catch it,
                // and what was read last stays on screen until the website answers again.
                Log.w(TAG, "Couldn't read watch state and lists from the website", error)
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun refreshAsync() {
        scope.launch { refresh(TrackingRefreshIntent.AUTOMATIC) }
    }

    /** After a write: what is held is applied as [transform] now and read back on the next refresh. */
    fun mutate(transform: (FetchWebsiteTrackingSnapshot) -> FetchWebsiteTrackingSnapshot) {
        mutationGeneration.incrementAndGet()
        _snapshot.update { current -> current?.let(transform)?.copy(fetchedAtMs = 0L) }
    }

    /** Something changed on the website because of this TV; read it again now. */
    fun invalidate() {
        mutate { it }
        refreshAsync()
    }

    /** Runs [block] as the website profile in TV slot [slot]; null when this TV can't reach one. */
    suspend fun <T> asProfile(
        slot: Int = profileManager.activeProfileId.value,
        block: suspend (profileId: String, profileToken: String?) -> T
    ): T? {
        if (!store.isPaired) return null
        val sync = websiteSync.get()
        val remote = sync.remoteFor(slot) ?: return null
        return block(remote.id, sync.profileToken(remote.id))
    }

    /**
     * [this] flow, reading the website every [POLL_INTERVAL_MS] while it is collected, so a
     * screen showing website state stays within a minute of it. A profile switch or a new
     * sign-in reads at once rather than at the next tick.
     */
    fun <T> Flow<T>.polledWhileCollected(): Flow<T> {
        val upstream = this
        return channelFlow {
            val poller = launch {
                combine(profileManager.activeProfileId, store.session) { slot, session -> slot to session }
                    .distinctUntilChanged()
                    .collectLatest {
                        while (true) {
                            refresh(TrackingRefreshIntent.AUTOMATIC)
                            delay(POLL_INTERVAL_MS)
                        }
                    }
            }
            upstream.collect { send(it) }
            poller.cancel()
        }.distinctUntilChanged()
    }

    private suspend fun readProfile(slot: Int): FetchWebsiteTrackingSnapshot? {
        val sync = websiteSync.get()
        val remote = sync.remoteFor(slot) ?: return null
        val token = sync.profileToken(remote.id)
        return try {
            coroutineScope {
                val playback = async { client.playback(remote.id, token) }
                val watchState = async { client.watchState(remote.id, token) }
                val lists = client.lists(remote.id, token)
                val items = lists.map { list -> async { list.id to client.listItems(remote.id, token, list.id) } }
                FetchWebsiteTrackingSnapshot(
                    slot = slot,
                    websiteProfileId = remote.id,
                    playback = playback.await(),
                    watchState = watchState.await(),
                    lists = lists,
                    listItems = items.awaitAll().toMap(),
                    fetchedAtMs = System.currentTimeMillis()
                )
            }
        } catch (error: FetchWebsiteException) {
            // A PIN-protected profile nobody has unlocked on this TV: there is nothing to show.
            if (error.code != "profile_verification_required") throw error
            FetchWebsiteTrackingSnapshot(slot, remote.id, fetchedAtMs = System.currentTimeMillis(), locked = true)
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 60_000L
        private const val ERROR_RETRY_MS = 30_000L
        private const val CONFLICT_RETRY_MS = 2_000L
    }
}
