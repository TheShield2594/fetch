package com.fetch.tv.ui.screens.home

/**
 * Items the Home MDBList prefetch should not ask about again for a while: ones MDBList had no
 * ratings for, and ones whose request failed (a shorter wait, so a rate limit or an outage
 * recovers). Bounded, dropping the oldest entry first (TheShield2594/fetch#823).
 */
internal class MdbListNegativeCache(
    private val missTtlMs: Long = MISS_TTL_MS,
    private val failureTtlMs: Long = FAILURE_TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    // Insertion order: re-recording an id moves it to the end, so the first entry is the oldest.
    private val expiresAtById = LinkedHashMap<String, Long>()

    @Synchronized
    fun contains(id: String): Boolean {
        val expiresAt = expiresAtById[id] ?: return false
        if (clock() < expiresAt) return true
        expiresAtById.remove(id)
        return false
    }

    fun recordMiss(id: String) = record(id, missTtlMs)

    fun recordFailure(id: String) = record(id, failureTtlMs)

    @Synchronized
    fun clear() = expiresAtById.clear()

    @get:Synchronized
    val size: Int get() = expiresAtById.size

    @Synchronized
    private fun record(id: String, ttlMs: Long) {
        expiresAtById.remove(id)
        expiresAtById[id] = clock() + ttlMs
        while (expiresAtById.size > maxEntries) {
            val oldest = expiresAtById.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    companion object {
        const val MISS_TTL_MS = 60L * 60L * 1000L
        const val FAILURE_TTL_MS = 5L * 60L * 1000L
        const val MAX_ENTRIES = 2_000
    }
}
