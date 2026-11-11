package com.fetch.tv.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId")
    fun observe(profileId: Int): Flow<List<WatchProgressRow>>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND contentId = :contentId")
    fun observeContent(profileId: Int, contentId: String): Flow<List<WatchProgressRow>>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId")
    suspend fun all(profileId: Int): List<WatchProgressRow>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND kind = :kind AND `key` IN (:keys)")
    suspend fun get(profileId: Int, kind: String, keys: List<String>): List<WatchProgressRow>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND contentId = :contentId")
    suspend fun getContent(profileId: Int, contentId: String): List<WatchProgressRow>

    @Upsert
    suspend fun upsert(rows: List<WatchProgressRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(rows: List<WatchProgressRow>)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId AND kind = :kind AND `key` IN (:keys)")
    suspend fun delete(profileId: Int, kind: String, keys: List<String>)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId AND kind = :kind")
    suspend fun clearKind(profileId: Int, kind: String)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: Int)
}

@Dao
interface LibraryEntryDao {
    @Query("SELECT * FROM library_entries WHERE profileId = :profileId")
    fun observe(profileId: Int): Flow<List<LibraryEntryRow>>

    @Query("SELECT * FROM library_entries WHERE profileId = :profileId AND contentId = :contentId")
    fun observeContent(profileId: Int, contentId: String): Flow<List<LibraryEntryRow>>

    @Query("SELECT * FROM library_entries WHERE profileId = :profileId")
    suspend fun all(profileId: Int): List<LibraryEntryRow>

    @Query("SELECT * FROM library_entries WHERE profileId = :profileId AND kind = :kind AND `key` IN (:keys)")
    suspend fun get(profileId: Int, kind: String, keys: List<String>): List<LibraryEntryRow>

    @Query("SELECT * FROM library_entries WHERE profileId = :profileId AND contentId = :contentId")
    suspend fun getContent(profileId: Int, contentId: String): List<LibraryEntryRow>

    @Upsert
    suspend fun upsert(rows: List<LibraryEntryRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(rows: List<LibraryEntryRow>)

    @Query("DELETE FROM library_entries WHERE profileId = :profileId AND kind = :kind AND `key` IN (:keys)")
    suspend fun delete(profileId: Int, kind: String, keys: List<String>)

    @Query("DELETE FROM library_entries WHERE profileId = :profileId AND kind = :kind")
    suspend fun clearKind(profileId: Int, kind: String)

    @Query("DELETE FROM library_entries WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: Int)
}
