package com.fetch.desktop.ratings

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fills in TMDB certifications for titles whose addon metadata has no rating, so profile rating
 * limits can apply to them. [ratings] is Compose state keyed by IMDb id: absent = not known yet,
 * present with null = TMDB has no rating (unrated), otherwise the certification.
 *
 * [request] answers from [cache] first and queues the rest; lookups run [maxConcurrent] at a time
 * with [batchDelayMs] between batches to stay well under TMDB's rate limit. A failed lookup
 * (network, 5xx, 429) isn't cached and is retried no sooner than [retryAfterMs] later. Nothing
 * is looked up, or cached, while the client has no TMDB key.
 */
class RatingsResolver(
    private val scope: CoroutineScope,
    private val client: TmdbCertificationClient,
    private val cache: CertificationCache,
    private val maxConcurrent: Int = 4,
    private val batchDelayMs: Long = 250,
    private val retryAfterMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = mutableStateMapOf<String, Certification?>()

    /** IMDb id → certification (null value = known unrated). Reading it in Compose subscribes. */
    val ratings: Map<String, Certification?> get() = state

    private val lock = Any()
    private val queue = ArrayDeque<Pair<String, String>>()
    private val pending = HashSet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private var drain: Job? = null

    /** Looks up each `imdb to type` pair not already known, cache first, then TMDB. */
    fun request(imdbIds: Collection<Pair<String, String>>) {
        val misses = mutableListOf<Pair<String, String>>()
        for ((rawId, type) in imdbIds) {
            val imdb = rawId.trim().substringBefore(':')
            if (!imdb.startsWith("tt") || state.containsKey(imdb)) continue
            val hit = cache.get(type, imdb)
            if (hit != null) state[imdb] = hit.certification else misses += imdb to type
        }
        if (misses.isEmpty() || !client.isConfigured) return
        synchronized(lock) {
            val now = clock()
            for (item in misses) {
                val key = CertificationCache.key(item.second, item.first)
                val failed = failedAt[key]
                if (failed != null && now - failed < retryAfterMs) continue
                if (pending.add(key)) queue.addLast(item)
            }
            if (queue.isNotEmpty() && drain?.isActive != true) drain = scope.launch { drainQueue() }
        }
    }

    /** The certification for [imdbId] if known; null when unrated or not known yet. */
    fun cached(imdbId: String): Certification? = state[imdbId.trim().substringBefore(':')]

    /** True when TMDB was asked and has no usable rating for [imdbId]. */
    fun isKnownUnrated(imdbId: String): Boolean {
        val imdb = imdbId.trim().substringBefore(':')
        return state.containsKey(imdb) && state[imdb] == null
    }

    /** Waits for queued lookups to finish; for tests and shutdown. */
    suspend fun awaitIdle() {
        while (true) {
            val job = synchronized(lock) { drain } ?: return
            job.join()
            synchronized(lock) { if (drain === job) drain = null }
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val batch = synchronized(lock) {
                if (queue.isEmpty()) {
                    drain = null
                    return
                }
                List(minOf(maxConcurrent, queue.size)) { queue.removeFirst() }
            }
            coroutineScope { batch.map { (imdb, type) -> async { lookup(imdb, type) } }.awaitAll() }
            runCatching { cache.save() }
            if (synchronized(lock) { queue.isNotEmpty() }) delay(batchDelayMs)
        }
    }

    private suspend fun lookup(imdb: String, type: String) {
        val key = CertificationCache.key(type, imdb)
        try {
            val result = client.certification(imdb, type)
            if (!client.isConfigured) return // key removed mid-lookup: don't cache a false "none"
            cache.put(type, imdb, result)
            state[imdb] = result
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failedAt[key] = clock()
        } finally {
            synchronized(lock) { pending.remove(key) }
        }
    }
}
