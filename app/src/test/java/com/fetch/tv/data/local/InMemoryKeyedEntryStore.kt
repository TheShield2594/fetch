package com.fetch.tv.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [KeyedEntryStore] in memory for JVM tests, with the Room store's semantics. It counts the
 * entries each call writes, so a test can check that a write stays bounded, and publishes a
 * transaction's changes to observers only when it ends, as SQLite does.
 */
internal class InMemoryKeyedEntryStore : KeyedEntryStore {
    private data class Id(val profileId: Int, val kind: String, val key: String)

    private val committed = MutableStateFlow<Map<Id, KeyedEntry>>(emptyMap())
    private val lock = Any()
    private val transactionMutex = Mutex()

    // Writes inside a transaction go here and reach `committed` when it ends.
    @Volatile
    private var pending: MutableMap<Id, KeyedEntry>? = null

    /** Entries written (added, replaced or deleted) since the last [resetWriteCount]. */
    @Volatile
    var entriesWritten: Int = 0
        private set

    fun resetWriteCount() {
        entriesWritten = 0
    }

    private fun current(): Map<Id, KeyedEntry> = synchronized(lock) { pending ?: committed.value }

    private fun mutate(block: (MutableMap<Id, KeyedEntry>) -> Unit) = synchronized(lock) {
        val target = pending
        if (target != null) {
            block(target)
        } else {
            committed.value = LinkedHashMap(committed.value).also(block)
        }
    }

    private fun Map<Id, KeyedEntry>.of(profileId: Int) = filterKeys { it.profileId == profileId }.values.toList()

    override fun observe(profileId: Int): Flow<List<KeyedEntry>> =
        committed.map { it.of(profileId) }.distinctUntilChanged()

    override fun observeContent(profileId: Int, contentId: String): Flow<List<KeyedEntry>> =
        observe(profileId).map { rows -> rows.filter { it.contentId == contentId } }.distinctUntilChanged()

    override suspend fun all(profileId: Int): List<KeyedEntry> = current().of(profileId)

    override suspend fun get(profileId: Int, kind: String, keys: Collection<String>): List<KeyedEntry> {
        val rows = current()
        return keys.distinct().mapNotNull { rows[Id(profileId, kind, it)] }
    }

    override suspend fun getContent(profileId: Int, contentId: String): List<KeyedEntry> =
        current().of(profileId).filter { it.contentId == contentId }

    override suspend fun <T> transaction(block: suspend () -> T): T = transactionMutex.withLock {
        synchronized(lock) { pending = LinkedHashMap(committed.value) }
        try {
            block().also { synchronized(lock) { committed.value = pending!! } }
        } finally {
            synchronized(lock) { pending = null }
        }
    }

    override suspend fun upsert(profileId: Int, entries: Collection<KeyedEntry>) = mutate { rows ->
        entries.forEach { rows[Id(profileId, it.kind, it.key)] = it }
        entriesWritten += entries.size
    }

    override suspend fun insertMissing(profileId: Int, entries: Collection<KeyedEntry>) = mutate { rows ->
        entries.forEach { entry ->
            val id = Id(profileId, entry.kind, entry.key)
            if (id !in rows) {
                rows[id] = entry
                entriesWritten++
            }
        }
    }

    override suspend fun delete(profileId: Int, kind: String, keys: Collection<String>) = mutate { rows ->
        keys.forEach { if (rows.remove(Id(profileId, kind, it)) != null) entriesWritten++ }
    }

    override suspend fun clearKind(profileId: Int, kind: String) = mutate { rows ->
        val ids = rows.keys.filter { it.profileId == profileId && it.kind == kind }
        ids.forEach { rows.remove(it) }
        entriesWritten += ids.size
    }
}
