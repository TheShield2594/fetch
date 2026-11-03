package com.fetch.tv.core.sync.backend

import com.fetch.tv.core.sync.SyncHomeCatalogPayload
import com.fetch.tv.core.tracking.TrackingProviderId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Where this TV keeps its account. Picked in Settings → Account. */
enum class SyncBackendKind {
    /** Nothing leaves the TV. The default. */
    LOCAL_ONLY,

    /** A self-hosted Fetch website (`web/`) this TV is paired with. */
    FETCH_WEBSITE,

    /** A self-hosted server that speaks the Supabase sync protocol inherited from NuvioTV. */
    SUPABASE
}

/** What a backend carries beyond the domains every backend has. */
data class SyncBackendFeatures(
    /**
     * How watch progress, watched items and the library travel. A backend that names a
     * tracking provider carries them as that provider (scrobbles, resume points, watched marks
     * and lists, read when it is the chosen source). Null means the built-in delta sync in
     * `WatchProgressSyncService`, `WatchedItemsSyncService` and `LibrarySyncService`, which
     * only the Supabase protocol speaks.
     */
    val watchStateProvider: TrackingProviderId?,
    /**
     * Parental limits, kids rules and the day's screen time ride inside each profile's settings
     * blob and are tightened on the way in. When false they come with the profiles instead.
     */
    val parentalLimitsInSettings: Boolean,
    /** Profile PINs are checked by the server rather than only on the TV. */
    val serverCheckedPins: Boolean,
    /** Watch party rooms (`core/party/`). */
    val watchParty: Boolean
)

/** One installed addon as a backend stores it, in the TV's own base URL form. */
data class SyncAddon(
    val url: String,
    val enabled: Boolean = true,
    val name: String? = null
)

/**
 * The transport behind the TV's sync services. Each service in `core/sync/` still decides when
 * to push and what to apply, the same way for every backend; this is only how the data gets to
 * the account and back. [SyncBackends.active] is the one in use.
 *
 * Domains: profiles, each profile's collections, addons, Home rows and settings. The settings
 * blob also carries the episode queue (`EpisodeQueueStore.FEATURE`), so the queue follows the
 * profile on every backend. Watch progress, watched items and the library are routed by
 * [SyncBackendFeatures.watchStateProvider].
 *
 * Every call fails rather than returns when the backend can't be reached; a backend that isn't
 * [isConnected] fails too.
 */
interface SyncBackend {
    val kind: SyncBackendKind

    /** Signed in or paired: there is an account to sync with right now. */
    val isConnected: Boolean

    /**
     * Who the synced data belongs to. Changes when the TV signs in to another account, so a
     * pull that started under one account can tell its result no longer applies. Null when
     * not connected.
     */
    val accountKey: String?

    val features: SyncBackendFeatures

    // ─── Profiles ───

    /** Sends every profile on this TV, creating the ones the account doesn't have yet. */
    suspend fun pushProfiles(): Result<Unit>

    /**
     * Reads the account's profiles and writes them to this TV, with whatever the backend keeps
     * next to them (parental limits, which profiles have a PIN). [force] skips any freshness
     * cache. Returns false when nothing was written, for example an account with no profiles.
     */
    suspend fun pullProfiles(force: Boolean = false): Result<Boolean>

    /** Deletes the profile in [profileId] from the account, with everything it holds there. */
    suspend fun deleteProfileData(profileId: Int): Result<Unit>

    // ─── Collections ───

    suspend fun pushCollections(profileId: Int, collections: JsonArray): Result<Unit>

    /**
     * The account's collections for [profileId], or null to keep [local] as it is: the account
     * has none yet (a backend may take [local] then, so connecting doesn't empty the Home
     * screen), or the profile is locked and not unlocked on this TV.
     */
    suspend fun pullCollections(profileId: Int, local: JsonArray): Result<JsonArray?>

    // ─── Addons ───

    /**
     * Replaces the addon list kept for [ownerProfileId], the profile whose addons the active
     * one uses (the primary's, for a profile set to share them).
     */
    suspend fun pushAddons(ownerProfileId: Int, addons: List<SyncAddon>): Result<Unit>

    /** The addon list kept for [ownerProfileId], in order. Null when the account has never had one. */
    suspend fun pullAddons(ownerProfileId: Int): Result<List<SyncAddon>?>

    // ─── Home rows ───

    suspend fun pushHomeLayout(profileId: Int, payload: SyncHomeCatalogPayload): Result<Unit>

    /**
     * The account's Home rows for [profileId], read over [local] so rows keep what only the TV
     * knows. Null when the account has never been given a layout for this profile.
     */
    suspend fun pullHomeLayout(profileId: Int, local: SyncHomeCatalogPayload): Result<SyncHomeCatalogPayload?>

    // ─── Settings ───

    /**
     * Saves [blob] as [profileId]'s settings. A backend that merges on the way (the website)
     * returns what both sides hold afterwards, which can carry changes made elsewhere; one that
     * replaces returns null.
     */
    suspend fun pushSettings(profileId: Int, blob: JsonObject): Result<JsonObject?>

    /**
     * The account's settings blob for [profileId], or null when it has none. A backend that
     * merges needs this TV's blob to do it and calls [local] for it.
     */
    suspend fun pullSettings(profileId: Int, local: suspend () -> JsonObject): Result<JsonObject?>
}

internal class SyncBackendNotConnectedException(kind: SyncBackendKind) :
    IllegalStateException("The ${kind.name.lowercase()} sync backend isn't connected")
