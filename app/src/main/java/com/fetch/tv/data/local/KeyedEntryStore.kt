package com.fetch.tv.data.local

import androidx.room.withTransaction
import com.fetch.tv.data.local.db.FetchDatabase
import com.fetch.tv.data.local.db.LibraryEntryRow
import com.fetch.tv.data.local.db.WatchProgressRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * One stored entry: its JSON under a key, within a [kind] of entry (a library item, a queued
 * change). [contentId] and [sortTime] are copied out of the JSON for queries.
 */
data class KeyedEntry(
    val kind: String,
    val key: String,
    val contentId: String,
    val sortTime: Long,
    val json: String
)

/**
 * A profile-scoped store of [KeyedEntry]s where each write touches only the entries it names, so
 * its cost doesn't grow with the store (audit G5). Backed by a Room table; tests use a map.
 */
interface KeyedEntryStore {
    fun observe(profileId: Int): Flow<List<KeyedEntry>>
    fun observeContent(profileId: Int, contentId: String): Flow<List<KeyedEntry>>
    suspend fun all(profileId: Int): List<KeyedEntry>
    suspend fun get(profileId: Int, kind: String, keys: Collection<String>): List<KeyedEntry>
    suspend fun getContent(profileId: Int, contentId: String): List<KeyedEntry>

    /** Runs [block] as one transaction: other writes see all of it or none of it. */
    suspend fun <T> transaction(block: suspend () -> T): T

    suspend fun upsert(profileId: Int, entries: Collection<KeyedEntry>)

    /** Adds the entries whose kind and key aren't stored yet; stored entries stay as they are. */
    suspend fun insertMissing(profileId: Int, entries: Collection<KeyedEntry>)
    suspend fun delete(profileId: Int, kind: String, keys: Collection<String>)
    suspend fun clearKind(profileId: Int, kind: String)
}

/**
 * Writes what turns [old] into [new] (both one kind's entries by key): only the entries that
 * differ are written and only the missing ones deleted.
 */
internal suspend fun KeyedEntryStore.writeChanges(
    profileId: Int,
    kind: String,
    old: Map<String, KeyedEntry>,
    new: Map<String, KeyedEntry>
) {
    val deletes = old.keys - new.keys
    val writes = new.values.filter { old[it.key] != it }
    if (deletes.isNotEmpty()) delete(profileId, kind, deletes)
    if (writes.isNotEmpty()) upsert(profileId, writes)
}

/** SQLite's limit on bound parameters is 999 on older Android versions. */
private const val MAX_KEYS_PER_QUERY = 900

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WatchProgressEntries

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibraryEntries

@Singleton
class RoomWatchProgressEntryStore @Inject constructor(
    private val database: FetchDatabase
) : KeyedEntryStore {
    private val dao get() = database.watchProgress()

    private fun WatchProgressRow.entry() = KeyedEntry(kind, key, contentId, sortTime, json)
    private fun KeyedEntry.row(profileId: Int) = WatchProgressRow(profileId, kind, key, contentId, sortTime, json)

    override fun observe(profileId: Int): Flow<List<KeyedEntry>> =
        dao.observe(profileId).distinctUntilChanged().map { rows -> rows.map { it.entry() } }

    override fun observeContent(profileId: Int, contentId: String): Flow<List<KeyedEntry>> =
        dao.observeContent(profileId, contentId).distinctUntilChanged().map { rows -> rows.map { it.entry() } }

    override suspend fun all(profileId: Int) = dao.all(profileId).map { it.entry() }

    override suspend fun get(profileId: Int, kind: String, keys: Collection<String>): List<KeyedEntry> =
        keys.distinct().chunked(MAX_KEYS_PER_QUERY).flatMap { chunk -> dao.get(profileId, kind, chunk).map { it.entry() } }

    override suspend fun getContent(profileId: Int, contentId: String) = dao.getContent(profileId, contentId).map { it.entry() }

    override suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction { block() }

    override suspend fun upsert(profileId: Int, entries: Collection<KeyedEntry>) {
        if (entries.isNotEmpty()) dao.upsert(entries.map { it.row(profileId) })
    }

    override suspend fun insertMissing(profileId: Int, entries: Collection<KeyedEntry>) {
        if (entries.isNotEmpty()) dao.insertMissing(entries.map { it.row(profileId) })
    }

    override suspend fun delete(profileId: Int, kind: String, keys: Collection<String>) {
        keys.distinct().chunked(MAX_KEYS_PER_QUERY).forEach { chunk -> dao.delete(profileId, kind, chunk) }
    }

    override suspend fun clearKind(profileId: Int, kind: String) = dao.clearKind(profileId, kind)
}

@Singleton
class RoomLibraryEntryStore @Inject constructor(
    private val database: FetchDatabase
) : KeyedEntryStore {
    private val dao get() = database.libraryEntries()

    private fun LibraryEntryRow.entry() = KeyedEntry(kind, key, contentId, sortTime, json)
    private fun KeyedEntry.row(profileId: Int) = LibraryEntryRow(profileId, kind, key, contentId, sortTime, json)

    override fun observe(profileId: Int): Flow<List<KeyedEntry>> =
        dao.observe(profileId).distinctUntilChanged().map { rows -> rows.map { it.entry() } }

    override fun observeContent(profileId: Int, contentId: String): Flow<List<KeyedEntry>> =
        dao.observeContent(profileId, contentId).distinctUntilChanged().map { rows -> rows.map { it.entry() } }

    override suspend fun all(profileId: Int) = dao.all(profileId).map { it.entry() }

    override suspend fun get(profileId: Int, kind: String, keys: Collection<String>): List<KeyedEntry> =
        keys.distinct().chunked(MAX_KEYS_PER_QUERY).flatMap { chunk -> dao.get(profileId, kind, chunk).map { it.entry() } }

    override suspend fun getContent(profileId: Int, contentId: String) = dao.getContent(profileId, contentId).map { it.entry() }

    override suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction { block() }

    override suspend fun upsert(profileId: Int, entries: Collection<KeyedEntry>) {
        if (entries.isNotEmpty()) dao.upsert(entries.map { it.row(profileId) })
    }

    override suspend fun insertMissing(profileId: Int, entries: Collection<KeyedEntry>) {
        if (entries.isNotEmpty()) dao.insertMissing(entries.map { it.row(profileId) })
    }

    override suspend fun delete(profileId: Int, kind: String, keys: Collection<String>) {
        keys.distinct().chunked(MAX_KEYS_PER_QUERY).forEach { chunk -> dao.delete(profileId, kind, chunk) }
    }

    override suspend fun clearKind(profileId: Int, kind: String) = dao.clearKind(profileId, kind)
}
