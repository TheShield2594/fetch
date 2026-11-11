package com.fetch.tv.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The TV's SQLite database, for the stores that grow with watch history. DataStore rewrites a
 * whole file on every edit, so a store that holds thousands of entries made each small change
 * (marking one episode watched) cost a write the size of the history. Here a change writes the
 * rows it touches. Settings stay in DataStore.
 *
 * Rows are scoped by profile id; [ProfileDatabaseData] deletes a profile's rows with the profile.
 */
@Database(
    entities = [WatchedItemRow::class],
    version = 1,
    exportSchema = true
)
abstract class FetchDatabase : RoomDatabase() {
    abstract fun watchedItems(): WatchedItemDao

    companion object {
        const val NAME = "fetch.db"
    }
}
