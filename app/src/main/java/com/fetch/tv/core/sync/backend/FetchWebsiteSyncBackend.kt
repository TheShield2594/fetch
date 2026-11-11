package com.fetch.tv.core.sync.backend

import com.fetch.tv.core.sync.SyncHomeCatalogPayload
import com.fetch.tv.data.website.FetchWebsiteStore
import com.fetch.tv.data.website.FetchWebsiteSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Fetch website (`web/`) as a sync backend, for a TV paired with one. The HTTP side is in
 * [FetchWebsiteSyncService]; this maps it onto [SyncBackend].
 *
 * Watch progress, watched marks and lists go through the website's tracking provider
 * (`data/website/FetchWebsiteTracking.kt`) rather than through here. Parental limits travel
 * with the profiles, and PINs are checked by the website (`FetchWebsiteProfilePins`).
 */
@Singleton
class FetchWebsiteSyncBackend @Inject constructor(
    private val store: FetchWebsiteStore,
    private val website: dagger.Lazy<FetchWebsiteSyncService>
) : SyncBackend {
    override val kind = SyncBackendKind.FETCH_WEBSITE

    override val isConnected: Boolean get() = store.isPaired

    // One website per TV, and it has no user id of its own: the address it was paired with
    // tells one account from the next.
    override val accountKey: String? get() = store.session.value?.serverUrl?.let { "website:$it" }

    override suspend fun pushProfiles(): Result<Unit> = call { website.get().pushProfilesNow().getOrThrow() }

    override suspend fun pullProfiles(force: Boolean): Result<Boolean> = call { website.get().pullProfiles() }

    override suspend fun deleteProfileData(profileId: Int): Result<Unit> =
        call { website.get().deleteProfile(profileId).getOrThrow() }

    override suspend fun pushCollections(profileId: Int, collections: JsonArray): Result<Unit> =
        call { website.get().putCollections(profileId, collections) }

    override suspend fun pullCollections(profileId: Int, local: JsonArray): Result<JsonArray?> =
        call { website.get().collections(profileId, local) }

    override suspend fun pushAddons(ownerProfileId: Int, addons: List<SyncAddon>): Result<Unit> =
        call { website.get().putAddons(ownerProfileId, addons) }

    override suspend fun pullAddons(ownerProfileId: Int): Result<List<SyncAddon>?> =
        call { website.get().addons(ownerProfileId) }

    override suspend fun pushHomeLayout(profileId: Int, payload: SyncHomeCatalogPayload): Result<Unit> =
        call { website.get().pushHomeLayout(profileId, payload) }

    override suspend fun pullHomeLayout(profileId: Int, local: SyncHomeCatalogPayload): Result<SyncHomeCatalogPayload?> =
        call { website.get().pullHomeLayout(profileId, local) }

    // The website merges (three-way, see FetchWebsiteSyncService.reconcileSettings), so a push
    // and a pull are the same exchange.
    override suspend fun pushSettings(profileId: Int, blob: JsonObject): Result<JsonObject?> =
        call { website.get().reconcileSettings(profileId, blob) }

    override suspend fun pullSettings(profileId: Int, local: suspend () -> JsonObject): Result<JsonObject?> =
        call { website.get().reconcileSettings(profileId, local()) }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext Result.failure(SyncBackendNotConnectedException(kind))
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
