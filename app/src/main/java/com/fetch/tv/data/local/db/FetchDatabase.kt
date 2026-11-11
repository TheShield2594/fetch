package com.fetch.tv.data.local.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The TV's SQLite database, for the stores that grow with watch history: watched items, watch
 * progress and the library with its queue of changes waiting to sync. DataStore rewrites a
 * whole file on every edit, so a store that holds thousands of entries made each small change
 * (marking one episode watched) cost a write the size of the history. Here a change writes the
 * rows it touches. Settings stay in DataStore.
 *
 * Rows are scoped by profile id; [ProfileDatabaseData] deletes a profile's rows with the profile.
 */
@Database(
    entities = [WatchedItemRow::class, WatchProgressRow::class, LibraryEntryRow::class],
    version = 2,
    exportSchema = true,
    // Version 2 adds the progress and library tables; nothing existing changes.
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
abstract class FetchDatabase : RoomDatabase() {
    abstract fun watchedItems(): WatchedItemDao
    abstract fun watchProgress(): WatchProgressDao
    abstract fun libraryEntries(): LibraryEntryDao

    companion object {
        const val NAME = "fetch.db"
    }
}
