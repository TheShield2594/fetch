package com.fetch.tv.data.local

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tracking.LocalListManagementCapabilities
import com.fetch.tv.core.tracking.LocalListsReducer
import com.fetch.tv.core.tracking.TrackingListManagementCapabilities
import com.fetch.tv.core.tracking.TrackingListManager
import com.fetch.tv.core.tracking.localListIdFromKey
import com.fetch.tv.domain.model.LibraryListPrivacy
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Manages the lists the local library keeps on the device; see [LocalListsDataStore]. */
@Singleton
class LocalTrackingListManager @Inject constructor(
    private val store: LocalListsDataStore,
    private val profileManager: ProfileManager
) : TrackingListManager {
    override val capabilities: TrackingListManagementCapabilities = LocalListManagementCapabilities

    override suspend fun createList(name: String, description: String?, privacy: LibraryListPrivacy) {
        store.update(profileManager.activeProfileId.value) { lists ->
            LocalListsReducer.create(
                lists = lists,
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                nowEpochMs = System.currentTimeMillis()
            )
        }
    }

    override suspend fun updateList(key: String, name: String, description: String?, privacy: LibraryListPrivacy) {
        val id = requireListId(key)
        store.update(profileManager.activeProfileId.value) { lists ->
            LocalListsReducer.update(lists, id, name, description, System.currentTimeMillis())
        }
    }

    override suspend fun deleteList(key: String) {
        val id = requireListId(key)
        store.update(profileManager.activeProfileId.value) { lists -> LocalListsReducer.delete(lists, id) }
    }

    override suspend fun reorderLists(keys: List<String>) {
        val ids = keys.map(::requireListId)
        store.update(profileManager.activeProfileId.value) { lists -> LocalListsReducer.reorder(lists, ids) }
    }

    private fun requireListId(key: String): String =
        requireNotNull(localListIdFromKey(key)) { "Invalid list for the local library" }
}
