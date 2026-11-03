package com.fetch.desktop.mdblist

import androidx.compose.runtime.mutableStateMapOf
import com.fetch.tv.domain.model.MDBListRatings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * MDBList ratings for the title page and Home's hero, as the TV's `MdbListRatingsLoader` and
 * `MDBListRepository` load them: looked up by IMDb id, from [cache] first, and the rest gathered
 * for [batchWindowMs] and asked for together, up to [maxBatch] titles per request. A title
 * MDBList has nothing for is remembered (in memory) so it isn't asked again; a failed lookup is
 * retried no sooner than [retryAfterMs] later. Ratings use this profile's API key, or else the
 * connected MDBList account ([accountAuth]).
 */
class MdbListRatingsState(
    private val scope: CoroutineScope,
    private val client: MdbListRatingsClient,
    private val cache: MdbListRatingsCache,
    private val settings: () -> MdbListSettings,
    private val accountConnected: () -> Boolean = { false },
    private val accountAuth: suspend () -> MdbListAuth? = { null },
    private val batchWindowMs: Long = 50,
    private val maxBatch: Int = 200,
    private val retryAfterMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Cache key → ratings; an empty value means MDBList has none. Reading it in Compose subscribes. */
    private val state = mutableStateMapOf<String, MDBListRatings>()

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pair<String, String>>()
    private val inFlight = HashSet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private var flushJob: Job? = null

    /** On, with a key or an account to ask with. */
    val active: Boolean
        get() = settings().let { it.enabled && it.toShared().hasEnabledProviders() } && (settings().apiKey.isNotBlank() || accountConnected())

    /**
     * The badges for a title page: this title's ratings with the hidden providers removed, or
     * null while they load, when MDBList has none, or when MDBList ratings are off. Asks MDBList
     * for them the first time.
     */
    fun forTitle(id: String?, type: String): MDBListRatings? {
        if (!active) return null
        val imdb = imdbIdOf(id) ?: return null
        val key = MdbListRatingsCache.key(type, imdb)
        val known = state[key]
        if (known == null) scope.launch { request(listOf(imdb to type)) }
        return known?.let(settings()::filter)?.takeUnless { it.isEmpty() }
    }

    /** [forTitle] for Home's hero, only with "Use Ratings on Hero" on. */
    fun forHero(id: String?, type: String): MDBListRatings? = if (settings().showOnHero) forTitle(id, type) else null

    /** Looks up a row's titles ahead of the hero showing them (TV: the focused row's batch). */
    fun prefetch(items: Collection<Pair<String?, String>>) {
        if (!active || !settings().showOnHero) return
        val wanted = items.mapNotNull { (id, type) -> imdbIdOf(id)?.let { it to type } }
            .filter { (imdb, type) -> MdbListRatingsCache.key(type, imdb) !in state }
        if (wanted.isNotEmpty()) scope.launch { request(wanted) }
    }

    /** Queues each `imdb to type` not known yet: the cache answers first, MDBList the rest. */
    fun request(items: Collection<Pair<String, String>>) {
        val now = clock()
        synchronized(lock) {
            for ((imdb, type) in items) {
                val key = MdbListRatingsCache.key(type, imdb)
                if (state.containsKey(key) || key in pending || key in inFlight) continue
                cache.get(key)?.let { state[key] = it; continue }
                failedAt[key]?.let { if (now - it < retryAfterMs) continue }
                pending[key] = imdb to type
            }
            if (pending.isNotEmpty() && flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(batchWindowMs)
                    flush()
                }
            }
        }
    }

    /** Whether MDBList accepts [key], checked before it's saved (TV: `validateAndSaveApiKey`). */
    suspend fun verify(key: String): Boolean = client.verify(key)

    /** Waits for queued lookups to finish; for tests. */
    suspend fun awaitIdle() {
        while (true) {
            val job = synchronized(lock) { flushJob } ?: return
            job.join()
            synchronized(lock) { if (flushJob === job && pending.isEmpty()) flushJob = null }
        }
    }

    private suspend fun flush() {
        while (true) {
            val batch = synchronized(lock) {
                if (pending.isEmpty()) {
                    flushJob = null
                    return
                }
                pending.entries.toList().also {
                    pending.clear()
                    inFlight += it.map { e -> e.key }
                }
            }
            val auth = runCatching { if (settings().apiKey.isNotBlank()) MdbListAuth.ApiKey(settings().apiKey.trim()) else accountAuth() }.getOrNull()
            batch.groupBy { MdbListRatingsClient.normalizeMediaType(it.value.second) }.forEach { (type, group) ->
                group.chunked(maxBatch).forEach { chunk -> fetch(type, chunk.map { it.key to it.value.first }, auth) }
            }
            runCatching { cache.save() }
        }
    }

    private suspend fun fetch(type: String, chunk: List<Pair<String, String>>, auth: MdbListAuth?) {
        try {
            if (auth == null) throw MdbListException(message = "No MDBList key")
            val found = client.ratings(type, chunk.map { it.second }, auth)
            chunk.forEach { (key, imdb) ->
                val ratings = found[imdb] ?: MDBListRatings()
                cache.put(key, ratings)
                state[key] = ratings
                failedAt.remove(key)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val now = clock()
            chunk.forEach { (key, _) -> failedAt[key] = now }
        } finally {
            synchronized(lock) { chunk.forEach { inFlight.remove(it.first) } }
        }
    }

    companion object {
        private val IMDB = Regex("tt\\d+")

        /** The IMDb id in an addon id (`tt123`, `tt123:1:2`, `imdb:tt123`), or null. */
        fun imdbIdOf(id: String?): String? = id?.let { IMDB.find(it)?.value }
    }
}
