package com.fetch.tv.core.sync

import android.util.Log
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.backend.SyncBackends
import com.fetch.tv.data.local.CollectionsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CollectionSyncService"

/** The active profile's collections, as one document, through whichever sync backend is in use ([SyncBackends]). */
@Singleton
class CollectionSyncService @Inject constructor(
    private val collectionsDataStore: CollectionsDataStore,
    private val profileManager: ProfileManager,
    private val backends: SyncBackends
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isSyncingFromRemote: Boolean = false

    private var pushJob: Job? = null

    private suspend fun localCollectionsJson(): JsonArray {
        val json = collectionsDataStore.exportCurrentProfileJson()
        return if (json.isNullOrBlank()) JsonArray(emptyList()) else Json.parseToJsonElement(json) as JsonArray
    }

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        val backend = backends.active ?: return@withContext Result.success(Unit)
        val profileId = profileManager.activeProfileId.value
        val collections = runCatching { localCollectionsJson() }.getOrElse { return@withContext Result.failure(it) }
        backend.pushCollections(profileId, collections)
            .onSuccess { Log.d(TAG, "Pushed ${collections.size} collections to ${backend.kind} for profile $profileId") }
            .onFailure { Log.e(TAG, "Failed to push collections to ${backend.kind}", it) }
    }

    /**
     * Pulls the active profile's collections and applies them locally.
     * Returns true if local state was updated.
     */
    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        val backend = backends.active ?: return@withContext Result.success(false)
        try {
            val profileId = profileManager.activeProfileId.value
            val remote = backend.pullCollections(profileId, localCollectionsJson()).getOrThrow()
            if (remote == null) {
                Log.d(TAG, "No remote collections to apply for profile $profileId; keeping local")
                return@withContext Result.success(false)
            }
            if (profileManager.activeProfileId.value != profileId) return@withContext Result.success(false)

            val remoteCollections = collectionsDataStore.importFromJson(remote.toString())
            val localJson = collectionsDataStore.exportCurrentProfileJson().orEmpty()
            if (collectionsDataStore.exportToJson(remoteCollections) == localJson) {
                Log.d(TAG, "Remote collections already match local for profile $profileId")
                return@withContext Result.success(false)
            }

            isSyncingFromRemote = true
            try {
                collectionsDataStore.setCollections(remoteCollections)
            } finally {
                isSyncingFromRemote = false
            }

            Log.d(TAG, "Applied ${remoteCollections.size} collections from ${backend.kind} for profile $profileId")
            Result.success(true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull collections from ${backend.kind}", e)
            Result.failure(e)
        }
    }

    /**
     * Trigger a debounced push to remote after a local change.
     */
    fun triggerPush() {
        if (isSyncingFromRemote) return
        if (!backends.canSync) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            pushToRemote()
        }
    }
}
