package com.fetch.tv.data.local

import androidx.room.withTransaction
import com.fetch.tv.data.local.db.FetchDatabase
import com.fetch.tv.data.local.db.WatchedItemRow
import com.fetch.tv.data.local.db.episodeColumn
import com.fetch.tv.data.local.db.seasonColumn
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.model.WatchedItemKey
import com.fetch.tv.domain.model.itemKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a profile's watched items are kept, one entry per [WatchedItemKey]. Each write touches
 * only the entries it names, so its cost doesn't grow with the history (audit G5).
 */
interface WatchedItemStore {
    fun observe(profileId: Int): Flow<List<WatchedItem>>

    /** The entries of one title (a movie, or a series and its episodes). */
    fun observeContent(profileId: Int, contentId: String): Flow<List<WatchedItem>>

    suspend fun all(profileId: Int): List<WatchedItem>

    /** Adds or replaces [items]. */
    suspend fun upsert(profileId: Int, items: List<WatchedItem>)

    /** Adds the [items] whose key isn't stored yet; stored entries stay as they are. */
    suspend fun insertMissing(profileId: Int, items: List<WatchedItem>)

    suspend fun delete(profileId: Int, keys: Collection<WatchedItemKey>)

    /**
     * Replaces the profile's entries with what [transform] returns from the current ones, as one
     * transaction, so nothing written meanwhile is lost. Only the entries that differ are written.
     */
    suspend fun transform(profileId: Int, transform: (List<WatchedItem>) -> List<WatchedItem>)

    suspend fun clear(profileId: Int)
}

/** The entries of [new] to write and the keys to delete, to turn [old] into [new]. */
internal fun watchedItemChanges(
    old: List<WatchedItem>,
    new: List<WatchedItem>
): Pair<List<WatchedItem>, Set<WatchedItemKey>> {
    val oldByKey = old.associateBy { it.itemKey() }
    val newByKey = new.associateBy { it.itemKey() }
    val writes = newByKey.values.filter { oldByKey[it.itemKey()] != it }
    val deletes = oldByKey.keys - newByKey.keys
    return writes to deletes
}

@Singleton
class RoomWatchedItemStore @Inject constructor(
    private val database: FetchDatabase
) : WatchedItemStore {
    private val dao get() = database.watchedItems()

    override fun observe(profileId: Int): Flow<List<WatchedItem>> =
        // Room re-runs the query after any write to the table, another profile's included.
        dao.observe(profileId).distinctUntilChanged().map { rows -> rows.map(WatchedItemRow::toWatchedItem) }

    override fun observeContent(profileId: Int, contentId: String): Flow<List<WatchedItem>> =
        dao.observeContent(profileId, contentId).distinctUntilChanged().map { rows -> rows.map(WatchedItemRow::toWatchedItem) }

    override suspend fun all(profileId: Int): List<WatchedItem> = dao.all(profileId).map(WatchedItemRow::toWatchedItem)

    override suspend fun upsert(profileId: Int, items: List<WatchedItem>) {
        if (items.isNotEmpty()) dao.upsert(items.map { WatchedItemRow.of(profileId, it) })
    }

    override suspend fun insertMissing(profileId: Int, items: List<WatchedItem>) {
        if (items.isNotEmpty()) dao.insertMissing(items.map { WatchedItemRow.of(profileId, it) })
    }

    override suspend fun delete(profileId: Int, keys: Collection<WatchedItemKey>) {
        if (keys.isEmpty()) return
        database.withTransaction { deleteKeys(profileId, keys) }
    }

    override suspend fun transform(profileId: Int, transform: (List<WatchedItem>) -> List<WatchedItem>) {
        database.withTransaction {
            val current = dao.all(profileId).map(WatchedItemRow::toWatchedItem)
            val (writes, deletes) = watchedItemChanges(current, transform(current))
            deleteKeys(profileId, deletes)
            if (writes.isNotEmpty()) dao.upsert(writes.map { WatchedItemRow.of(profileId, it) })
        }
    }

    override suspend fun clear(profileId: Int) = dao.deleteProfile(profileId)

    private suspend fun deleteKeys(profileId: Int, keys: Collection<WatchedItemKey>) {
        keys.forEach { key -> dao.delete(profileId, key.contentId, key.seasonColumn(), key.episodeColumn()) }
    }
}
