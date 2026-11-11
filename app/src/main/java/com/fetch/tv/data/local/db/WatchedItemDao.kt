package com.fetch.tv.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchedItemDao {
    @Query("SELECT * FROM watched_items WHERE profileId = :profileId")
    fun observe(profileId: Int): Flow<List<WatchedItemRow>>

    @Query("SELECT * FROM watched_items WHERE profileId = :profileId AND contentId = :contentId")
    fun observeContent(profileId: Int, contentId: String): Flow<List<WatchedItemRow>>

    @Query("SELECT * FROM watched_items WHERE profileId = :profileId")
    suspend fun all(profileId: Int): List<WatchedItemRow>

    @Query("SELECT COUNT(*) FROM watched_items WHERE profileId = :profileId")
    suspend fun count(profileId: Int): Int

    @Upsert
    suspend fun upsert(rows: List<WatchedItemRow>)

    /** Adds the rows not already there; an existing row for the same key is kept as it is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(rows: List<WatchedItemRow>)

    @Query(
        "DELETE FROM watched_items WHERE profileId = :profileId AND contentId = :contentId " +
            "AND season = :season AND episode = :episode"
    )
    suspend fun delete(profileId: Int, contentId: String, season: Int, episode: Int)

    @Query("DELETE FROM watched_items WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: Int)
}
