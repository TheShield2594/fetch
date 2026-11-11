package com.fetch.tv.data.local

import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.model.WatchedMutationKey
import com.fetch.tv.domain.model.mutationKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [WatchedItemStore] in memory, with the same semantics as the Room one, for JVM tests. It counts
 * the entries each call writes, so a test can check that a write stays bounded.
 */
internal class InMemoryWatchedItemStore : WatchedItemStore {
    private val profiles = MutableStateFlow<Map<Int, Map<WatchedMutationKey, WatchedItem>>>(emptyMap())
    private val mutex = Mutex()

    /** Entries written (added, replaced or deleted) since the last [resetWriteCount]. */
    @Volatile
    var entriesWritten: Int = 0
        private set

    fun resetWriteCount() {
        entriesWritten = 0
    }

    private fun rows(profileId: Int) = profiles.value[profileId].orEmpty()

    private suspend fun edit(profileId: Int, block: (MutableMap<WatchedMutationKey, WatchedItem>) -> Unit) = mutex.withLock {
        val updated = LinkedHashMap(rows(profileId)).also(block)
        profiles.value = profiles.value + (profileId to updated)
    }

    override fun observe(profileId: Int): Flow<List<WatchedItem>> =
        profiles.map { it[profileId].orEmpty().values.toList() }.distinctUntilChanged()

    override fun observeContent(profileId: Int, contentId: String): Flow<List<WatchedItem>> =
        observe(profileId).map { items -> items.filter { it.contentId == contentId } }.distinctUntilChanged()

    override suspend fun all(profileId: Int): List<WatchedItem> = rows(profileId).values.toList()

    override suspend fun upsert(profileId: Int, items: List<WatchedItem>) = edit(profileId) { rows ->
        items.forEach { rows[it.mutationKey()] = it }
        entriesWritten += items.size
    }

    override suspend fun insertMissing(profileId: Int, items: List<WatchedItem>) = edit(profileId) { rows ->
        items.forEach { item ->
            if (item.mutationKey() !in rows) {
                rows[item.mutationKey()] = item
                entriesWritten++
            }
        }
    }

    override suspend fun delete(profileId: Int, keys: Collection<WatchedMutationKey>) = edit(profileId) { rows ->
        keys.forEach { if (rows.remove(it) != null) entriesWritten++ }
    }

    override suspend fun transform(profileId: Int, transform: (List<WatchedItem>) -> List<WatchedItem>) = edit(profileId) { rows ->
        val (writes, deletes) = watchedItemChanges(rows.values.toList(), transform(rows.values.toList()))
        deletes.forEach { rows.remove(it) }
        writes.forEach { rows[it.mutationKey()] = it }
        entriesWritten += writes.size + deletes.size
    }

    override suspend fun clear(profileId: Int) = edit(profileId) { rows ->
        entriesWritten += rows.size
        rows.clear()
    }
}
