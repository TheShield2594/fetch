package com.fetch.tv.core.sync.backend

import android.os.SystemClock
import android.util.Log
import com.fetch.tv.core.auth.AuthManager
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.ProfilePullFreshness
import com.fetch.tv.core.sync.ProfileSettingsSyncService
import com.fetch.tv.core.sync.SyncClientIdentity
import com.fetch.tv.core.sync.SyncHomeCatalogPayload
import com.fetch.tv.core.sync.mergeHomeCatalogSettingsJson
import com.fetch.tv.core.sync.putSyncOriginClientId
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.remote.supabase.SupabaseAddon
import com.fetch.tv.data.remote.supabase.SupabaseCollectionBlob
import com.fetch.tv.data.remote.supabase.SupabaseHomeCatalogSettingsBlob
import com.fetch.tv.data.remote.supabase.SupabaseProfile
import com.fetch.tv.data.remote.supabase.SupabaseProfileSettingsBlob
import com.fetch.tv.domain.model.AuthState
import com.fetch.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SupabaseSyncBackend"
private const val SETTINGS_SYNC_PLATFORM = "tv"
private const val HOME_CATALOG_SHARED_SYNC_PLATFORM = "home_catalog_shared"
private const val HIDE_UNRELEASED_CONTENT_KEY = "hide_unreleased_content"

/**
 * A self-hosted server that speaks the Supabase sync protocol inherited from NuvioTV: the
 * `sync_push_*` / `sync_pull_*` RPCs, behind an email or TV-code sign-in (`AuthManager`).
 *
 * Release builds ship with no server baked in (`SUPABASE_URL` is blank and resolves to
 * `https://sync.invalid`), so this backend only does anything once a builder bakes one in or
 * someone connects their own from the server options in Settings → Account.
 *
 * Besides the domains in [SyncBackend], only this protocol carries the built-in watch progress,
 * watched items and library delta sync, profile PINs checked by the server
 * (`ProfileSyncService.setProfilePin` and friends), copying one profile's setup to another,
 * provider credentials, media servers and watch party.
 */
@Singleton
class SupabaseSyncBackend @Inject constructor(
    private val authManager: AuthManager,
    private val postgrestLazy: dagger.Lazy<Postgrest>,
    private val profileManager: ProfileManager,
    private val profileDataStore: ProfileDataStore,
    private val syncClientIdentity: SyncClientIdentity,
    private val profileSettingsSync: dagger.Lazy<ProfileSettingsSyncService>
) : SyncBackend {
    private val postgrest: Postgrest get() = postgrestLazy.get()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val profilePullMutex = Mutex()
    private var profilePullFreshness = ProfilePullFreshness()

    /**
     * The Home rows blob is shared with other platforms (`home_catalog_shared`), which keep keys
     * the TV doesn't know; a push writes the whole blob, so it lays the TV's keys over the last
     * copy read for the same account and profile.
     */
    @Volatile
    private var cachedHomeSettings: Pair<String, JsonObject>? = null

    override val kind = SyncBackendKind.SUPABASE

    override val isConnected: Boolean get() = authManager.isAuthenticated

    override val accountKey: String?
        get() = (authManager.authState.value as? AuthState.FullAccount)?.userId?.let { "supabase:$it" }

    override val features = SyncBackendFeatures(
        watchStateProvider = null,
        parentalLimitsInSettings = true,
        serverCheckedPins = true,
        watchParty = true
    )

    // ─── Profiles ───

    override suspend fun pushProfiles(): Result<Unit> = call("push profiles") {
        val profiles = profileManager.profiles.value
        val params = buildJsonObject {
            put("p_client_max_profiles", ProfileManager.MAX_PROFILES)
            put("p_profiles", buildJsonArray {
                profiles.forEach { profile ->
                    addJsonObject {
                        put("profile_index", profile.id)
                        put("name", profile.name)
                        put("avatar_color_hex", profile.avatarColorHex)
                        put("uses_primary_addons", profile.usesPrimaryAddons)
                        // Fetch no longer has plugins; the sync schema still carries the column.
                        put("uses_primary_plugins", false)
                        put("avatar_id", if (profile.avatarUrl.isNullOrBlank()) profile.avatarId else null)
                        put("avatar_url", profile.avatarUrl?.takeIf { it.isNotBlank() })
                        put("profile_background_id", profile.profileBackgroundId)
                        put("profile_background_url", profile.profileBackgroundUrl?.takeIf { it.isNotBlank() })
                    }
                }
            })
            putSyncOriginClientId(syncClientIdentity)
        }
        rpc { postgrest.rpc("sync_push_profiles", params) }
        Log.d(TAG, "Pushed ${profiles.size} profiles")
    }

    override suspend fun pullProfiles(force: Boolean): Result<Boolean> = call("pull profiles") {
        profilePullMutex.withLock {
            val userId = (authManager.authState.value as? AuthState.FullAccount)?.userId
            if (!force && userId != null && profilePullFreshness.isRecent(userId, SystemClock.elapsedRealtime())) {
                return@withLock false
            }
            val remote = rpc { postgrest.rpc("sync_pull_profiles") }.decodeList<SupabaseProfile>()
            Log.d(TAG, "Pulled ${remote.size} profiles")
            val profiles = remote.map { entry ->
                UserProfile(
                    id = entry.profileIndex,
                    name = entry.name,
                    avatarColorHex = entry.avatarColorHex,
                    usesPrimaryAddons = entry.usesPrimaryAddons,
                    avatarId = entry.avatarId,
                    avatarUrl = entry.avatarUrl,
                    profileBackgroundId = entry.profileBackgroundId,
                    profileBackgroundUrl = entry.profileBackgroundUrl
                )
            }
            var limitsSynced = true
            if (profiles.isNotEmpty()) {
                profileDataStore.replaceAllProfiles(profiles)
                // Limits travel with each profile's settings; failures are logged inside, and
                // the pull isn't marked fresh so the next one tries again.
                limitsSynced = profileSettingsSync.get().pullParentalLimits(profiles.map { it.id })
            }
            val currentUserId = (authManager.authState.value as? AuthState.FullAccount)?.userId
            if (userId != null && currentUserId == userId && limitsSynced) {
                profilePullFreshness = ProfilePullFreshness(userId = userId, pulledAtMs = SystemClock.elapsedRealtime())
            }
            profiles.isNotEmpty()
        }
    }

    override suspend fun deleteProfileData(profileId: Int): Result<Unit> = call("delete profile $profileId") {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            putSyncOriginClientId(syncClientIdentity)
        }
        rpc { postgrest.rpc("sync_delete_profile_data", params) }
    }

    // ─── Collections ───

    override suspend fun pushCollections(profileId: Int, collections: JsonArray): Result<Unit> =
        call("push collections") {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_collections_json", collections)
                putSyncOriginClientId(syncClientIdentity)
            }
            rpc { postgrest.rpc("sync_push_collections", params) }
        }

    override suspend fun pullCollections(profileId: Int, local: JsonArray): Result<JsonArray?> =
        call("pull collections") {
            val params = buildJsonObject { put("p_profile_id", profileId) }
            val blob = rpc { postgrest.rpc("sync_pull_collections", params) }
                .decodeList<SupabaseCollectionBlob>()
                .firstOrNull()
                ?: return@call null
            val remote = blob.collectionsJson as? JsonArray ?: return@call null
            // An empty row can be one nobody wrote yet; it doesn't get to empty this TV.
            if (remote.isEmpty() && local.isNotEmpty()) {
                Log.w(TAG, "Remote collections empty while local has ${local.size}; keeping local")
                return@call null
            }
            remote
        }

    // ─── Addons ───

    override suspend fun pushAddons(ownerProfileId: Int, addons: List<SyncAddon>): Result<Unit> =
        call("push addons") {
            // A profile that uses the primary's addons reads the primary's row, which only the
            // primary writes.
            if (ownerProfileId != profileManager.activeProfileId.value) return@call
            val params = buildJsonObject {
                put("p_addons", buildJsonArray {
                    addons.forEachIndexed { index, addon ->
                        addJsonObject {
                            put("url", addon.url)
                            put("sort_order", index)
                            put("enabled", addon.enabled)
                            addon.name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                        }
                    }
                })
                put("p_profile_id", ownerProfileId)
                putSyncOriginClientId(syncClientIdentity)
            }
            rpc { postgrest.rpc("sync_push_addons", params) }
            Log.d(TAG, "Pushed ${addons.size} addons for profile $ownerProfileId")
        }

    /** Never null: the protocol can't tell a profile with no addons from one never synced. */
    override suspend fun pullAddons(ownerProfileId: Int): Result<List<SyncAddon>?> = call("pull addons") {
        val ownerId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
            ?: throw IllegalStateException("Unable to resolve sync owner for addon sync")
        rpc {
            postgrest.from("addons")
                .select {
                    filter {
                        eq("user_id", ownerId)
                        eq("profile_id", ownerProfileId)
                    }
                }
                .decodeList<SupabaseAddon>()
        }
            .sortedBy { it.sortOrder }
            .map { SyncAddon(url = it.url, enabled = it.enabled, name = it.name?.takeIf { name -> name.isNotBlank() }) }
    }

    // ─── Home rows ───

    override suspend fun pushHomeLayout(profileId: Int, payload: SyncHomeCatalogPayload): Result<Unit> =
        call("push Home rows") {
            val key = homeKey(profileId)
            val local = json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), payload).jsonObject
            val merged = mergeHomeCatalogSettingsJson(cachedHomeSettings?.takeIf { it.first == key }?.second, local)
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_settings_json", merged)
                put("p_platform", HOME_CATALOG_SHARED_SYNC_PLATFORM)
                putSyncOriginClientId(syncClientIdentity)
            }
            rpc { postgrest.rpc("sync_push_home_catalog_settings", params) }
            cachedHomeSettings = key to merged
        }

    override suspend fun pullHomeLayout(
        profileId: Int,
        local: SyncHomeCatalogPayload
    ): Result<SyncHomeCatalogPayload?> = call("pull Home rows") {
        val key = homeKey(profileId)
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", HOME_CATALOG_SHARED_SYNC_PLATFORM)
        }
        val blob = rpc { postgrest.rpc("sync_pull_home_catalog_settings", params) }
            .decodeList<SupabaseHomeCatalogSettingsBlob>()
            .firstOrNull()
        cachedHomeSettings = key to (blob?.settingsJson ?: buildJsonObject { })
        val settings = blob?.settingsJson ?: return@call null
        runCatching {
            val decoded = json.decodeFromJsonElement(SyncHomeCatalogPayload.serializer(), settings)
            // Older rows have no such key; they keep this TV's choice.
            if (settings.containsKey(HIDE_UNRELEASED_CONTENT_KEY)) decoded
            else decoded.copy(hideUnreleasedContent = local.hideUnreleasedContent)
        }.onFailure { Log.w(TAG, "Couldn't read the Home rows for profile $profileId", it) }.getOrNull()
    }

    private fun homeKey(profileId: Int): String = "${accountKey.orEmpty()}#$profileId"

    // ─── Settings ───

    override suspend fun pushSettings(profileId: Int, blob: JsonObject): Result<JsonObject?> =
        call("push settings") {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_settings_json", blob)
                put("p_platform", SETTINGS_SYNC_PLATFORM)
                putSyncOriginClientId(syncClientIdentity)
            }
            rpc { postgrest.rpc("sync_push_profile_settings_blob", params) }
            null
        }

    override suspend fun pullSettings(profileId: Int, local: suspend () -> JsonObject): Result<JsonObject?> =
        call("pull settings") {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_platform", SETTINGS_SYNC_PLATFORM)
            }
            rpc { postgrest.rpc("sync_pull_profile_settings_blob", params) }
                .decodeList<SupabaseProfileSettingsBlob>()
                .firstOrNull()
                ?.settingsJson
        }

    // ─── Plumbing ───

    /** Retried once after refreshing an expired session. */
    private suspend fun <T> rpc(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (!authManager.refreshSessionIfJwtExpired(error)) throw error
        block()
    }

    private suspend fun <T> call(what: String, block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext Result.failure(SyncBackendNotConnectedException(kind))
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Failed to $what", error)
            Result.failure(error)
        }
    }
}
