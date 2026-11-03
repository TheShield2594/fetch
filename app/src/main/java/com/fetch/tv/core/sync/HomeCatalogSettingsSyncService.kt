package com.fetch.tv.core.sync

import android.util.Log
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.backend.SyncBackends
import com.fetch.tv.data.local.CollectionsDataStore
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.domain.model.enabledAddons
import com.fetch.tv.domain.repository.AddonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HomeCatalogSettingsSyncService"
private const val PAYLOAD_SAMPLE_LIMIT = 5

@Serializable
data class SyncCatalogItem(
    @SerialName("addon_id") val addonId: String,
    val type: String,
    @SerialName("catalog_id") val catalogId: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    @SerialName("custom_title") val customTitle: String = "",
    @SerialName("is_collection") val isCollection: Boolean = false,
    @SerialName("collection_id") val collectionId: String = "",
)

@Serializable
data class SyncHomeCatalogPayload(
    @SerialName("hide_unreleased_content") val hideUnreleasedContent: Boolean = false,
    val items: List<SyncCatalogItem> = emptyList(),
)

/** The account and profile a sync started for; a result for another one is dropped. */
private data class HomeCatalogSyncScope(
    val accountKey: String,
    val profileId: Int
)

internal fun mergeHomeCatalogSettingsJson(
    remoteJson: JsonObject?,
    localJson: JsonObject
): JsonObject = buildJsonObject {
    remoteJson?.forEach { (key, value) -> put(key, value) }
    localJson.forEach { (key, value) -> put(key, value) }
}

/** The active profile's Home row order, through whichever sync backend is in use ([SyncBackends]). */
@Singleton
class HomeCatalogSettingsSyncService @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val profileManager: ProfileManager,
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val backends: SyncBackends
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isSyncingFromRemote: Boolean = false

    private var pushJob: Job? = null

    suspend fun pushToRemote(reason: String = "unspecified"): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val backend = backends.active ?: return@withContext Result.success(Unit)
            currentScope(profileId) ?: return@withContext Result.success(Unit)
            val payload = loadLocalPayload()
            Log.d(TAG, "Push start backend=${backend.kind} profile=$profileId reason=$reason ${payload.summary()}")
            backend.pushHomeLayout(profileId, payload).getOrThrow()

            Log.d(TAG, "Push success profile=$profileId reason=$reason")
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Push failed reason=$reason", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val backend = backends.active ?: return@withContext Result.success(false)
            val syncScope = currentScope(profileId)
                ?: return@withContext Result.success(false)
            val localState = layoutPreferenceDataStore.getHomeCatalogSettingsState()
            Log.d(TAG, "Pull start backend=${backend.kind} profile=$profileId ${localState.summary()}")

            if (localState.disabledKeys.any(::hasLegacyHomeCatalogDisabledKeyFormat)) {
                val localPayload = loadLocalPayload()
                if (localPayload.items.isNotEmpty()) {
                    isSyncingFromRemote = true
                    try {
                        layoutPreferenceDataStore.applyCatalogSettingsFromRemote(localPayload)
                    } finally {
                        isSyncingFromRemote = false
                    }
                    Log.i(TAG, "Migrated legacy local keys profile=$profileId ${localPayload.summary()} (no startup push)")
                    return@withContext Result.success(true)
                }
            }

            val localPayload = loadLocalPayload()
            val remotePayload = backend.pullHomeLayout(profileId, localPayload).getOrThrow()
            if (currentScope(profileId) != syncScope) {
                return@withContext Result.success(false)
            }
            if (remotePayload == null) {
                Log.d(TAG, "No remote Home rows profile=$profileId; preserving local (startup is pull-only)")
                return@withContext Result.success(false)
            }
            Log.d(TAG, "Pull remote payload profile=$profileId ${remotePayload.summary()}")

            if (remotePayload.items.isEmpty()) {
                Log.d(TAG, "Remote payload empty profile=$profileId; preserving local (startup is pull-only)")
                return@withContext Result.success(false)
            }

            isSyncingFromRemote = true
            try {
                layoutPreferenceDataStore.applyCatalogSettingsFromRemote(remotePayload)
            } finally {
                isSyncingFromRemote = false
            }

            Log.d(TAG, "Pull apply success profile=$profileId ${remotePayload.summary()}")
            Result.success(true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull home catalog settings", e)
            Result.failure(e)
        }
    }

    fun triggerPush() {
        if (isSyncingFromRemote) return
        if (!backends.canSync) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            pushToRemote(reason = "triggerPush")
        }
    }

    private suspend fun loadLocalPayload(): SyncHomeCatalogPayload {
        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        val collections = collectionsDataStore.getCurrentCollections()
        return layoutPreferenceDataStore.exportCatalogSettingsToSyncPayload(addons, collections)
    }

    private fun currentScope(profileId: Int): HomeCatalogSyncScope? {
        if (profileManager.activeProfileId.value != profileId) return null
        val accountKey = backends.active?.accountKey ?: return null
        return HomeCatalogSyncScope(accountKey = accountKey, profileId = profileId)
    }

}

private fun LocalHomeCatalogSettingsState.summary(): String {
    val orderSample = orderKeys.take(PAYLOAD_SAMPLE_LIMIT)
    val disabledSample = disabledKeys.take(PAYLOAD_SAMPLE_LIMIT)
    val titleSample = customTitles.keys.take(PAYLOAD_SAMPLE_LIMIT)
    return "localState(order=${orderKeys.size}, disabled=${disabledKeys.size}, titles=${customTitles.size}, orderSample=$orderSample, disabledSample=$disabledSample, titleSample=$titleSample)"
}

private fun SyncHomeCatalogPayload.summary(): String {
    val disabledCount = items.count { !it.enabled }
    val collectionCount = items.count { it.isCollection }
    val sample = items.take(PAYLOAD_SAMPLE_LIMIT).joinToString(separator = " | ") { item ->
        if (item.isCollection) {
            "collection:${item.collectionId},enabled=${item.enabled},order=${item.order}"
        } else {
            "catalog:${item.addonId}/${item.type}/${item.catalogId},enabled=${item.enabled},order=${item.order}"
        }
    }
    return "payload(items=${items.size}, disabled=$disabledCount, collections=$collectionCount, hideUnreleased=$hideUnreleasedContent, sample=[$sample])"
}
