package com.fetch.tv.data.local.db

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * A profile's rows in [FetchDatabase]. Deleting a profile deletes them, as it deletes the
 * profile's DataStore files, so a new profile that reuses the id starts empty.
 */
@Singleton
class ProfileDatabaseData @Inject constructor(
    // A Provider, so a profile deleted before anything else used the database is the first to open it.
    private val database: Provider<FetchDatabase>
) {
    suspend fun deleteProfile(profileId: Int) {
        val db = database.get()
        db.withTransaction {
            db.watchedItems().deleteProfile(profileId)
            db.watchProgress().deleteProfile(profileId)
            db.libraryEntries().deleteProfile(profileId)
        }
    }
}
