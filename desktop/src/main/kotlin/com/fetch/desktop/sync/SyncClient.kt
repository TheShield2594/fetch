package com.fetch.desktop.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom

/**
 * Pulls and pushes the data the TV app syncs, over the same wire format. Like the TV app, every
 * read and write goes through `SECURITY DEFINER` RPCs (`POST /rest/v1/rpc/<name>`) that resolve the
 * sync owner server-side (so a linked device reads its owner's data) and dedupe on the server's own
 * keys; the only direct table read is `addons`. Writes send `p_origin_client_id` so realtime echoes
 * of this client's own writes can be told apart.
 *
 * Every call is scoped to a profile: `profile_id` 1 is the primary profile ([RemoteProfile.profileIndex]).
 *
 * The access token is refreshed ahead of expiry, and once more on a 401, then the call is retried
 * once; a refresh the server rejects throws [SyncAuthException]. New sessions are reported through
 * [onSessionChanged] so the caller can persist them (see [SyncStore]).
 */
class SyncClient(
    private val http: OkHttpClient,
    private val auth: SyncAuthClient,
    session: SyncSession,
    /** This install's id, sent as `p_origin_client_id`; see [newOriginClientId]. */
    private val originClientId: String,
    private val onSessionChanged: (SyncSession) -> Unit = {},
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val config = auth.config
    private val refreshMutex = Mutex()

    @Volatile
    var session: SyncSession = session
        private set

    @Volatile
    private var cachedOwner: Pair<String, String>? = null

    // --- Account ---

    /** The user id whose data this account syncs (RPC `get_sync_owner`): the owner, for a linked device. */
    suspend fun syncOwnerId(): String {
        val userId = session.userId
        cachedOwner?.takeIf { it.first == userId }?.let { return it.second }
        val owner = syncJson.parseToJsonElement(rpc("get_sync_owner", null)).jsonPrimitive.content
        cachedOwner = userId to owner
        return owner
    }

    /** RPC `sync_pull_profiles`. */
    suspend fun pullProfiles(): List<RemoteProfile> =
        syncJson.decodeFromString<List<ProfileDto>>(rpc("sync_pull_profiles", null)).map {
            RemoteProfile(
                it.profileIndex, it.name, it.avatarColorHex, it.usesPrimaryAddons, it.avatarId, it.avatarUrl,
                it.profileBackgroundId, it.profileBackgroundUrl,
            )
        }.sortedBy { it.profileIndex }

    /**
     * Replaces the account's profile list (RPC `sync_push_profiles`), as the TV app does after any
     * profile edit: send every profile, profile 1 included. Like the TV app, `avatar_id` is only
     * sent when there is no `avatar_url`, and `uses_primary_plugins` is always false.
     */
    suspend fun pushProfiles(profiles: List<RemoteProfile>) {
        val params = buildJsonObject {
            put("p_client_max_profiles", MAX_PROFILES)
            putJsonArray("p_profiles") {
                profiles.sortedBy { it.profileIndex }.forEach { profile ->
                    addJsonObject {
                        put("profile_index", profile.profileIndex)
                        put("name", profile.name)
                        put("avatar_color_hex", profile.avatarColorHex)
                        put("uses_primary_addons", profile.usesPrimaryAddons)
                        put("uses_primary_plugins", false)
                        put("avatar_id", if (profile.avatarUrl.isNullOrBlank()) profile.avatarId else null)
                        put("avatar_url", profile.avatarUrl?.takeIf { it.isNotBlank() })
                        put("profile_background_id", profile.profileBackgroundId)
                        put("profile_background_url", profile.profileBackgroundUrl?.takeIf { it.isNotBlank() })
                    }
                }
            }
            putOrigin()
        }
        rpc("sync_push_profiles", params)
    }

    /**
     * Deletes everything the account holds for a profile (RPC `sync_delete_profile_data`). The TV
     * app calls this when a profile is deleted, then [pushProfiles] with the profiles left.
     */
    suspend fun deleteProfileData(profileId: Int) {
        require(profileId != PRIMARY_PROFILE_ID) { "The primary profile can't be deleted" }
        rpc("sync_delete_profile_data", buildJsonObject {
            put("p_profile_id", profileId)
            putOrigin()
        })
    }

    // --- Profile PINs ---
    //
    // The server keeps its own hash of each PIN: the TV app sends the PIN itself to these RPCs
    // and never syncs its local `v1:<iterations>:<salt>:<hash>` ProfilePinHasher string. A device
    // checks its local hash first; a PIN only the server has is checked with [verifyProfilePin]
    // and, once it is right, hashed and kept locally.

    /** Which profiles have a PIN on the server (RPC `sync_pull_profile_locks`). */
    suspend fun pullProfileLocks(): List<RemoteProfileLock> =
        syncJson.decodeFromString<List<ProfileLockDto>>(rpc("sync_pull_profile_locks", null))
            .map { RemoteProfileLock(it.profileIndex, it.pinEnabled, it.pinLockedUntil) }
            .sortedBy { it.profileIndex }

    /**
     * Sets a profile's PIN on the server (RPC `set_profile_pin`). A profile that already has one
     * needs [currentPin]; without it, or with a wrong one, the result is [RemotePinSetResult.CURRENT_PIN_REQUIRED].
     */
    suspend fun setProfilePin(profileId: Int, pin: String, currentPin: String? = null): RemotePinSetResult {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_pin", pin)
            if (!currentPin.isNullOrBlank()) put("p_current_pin", currentPin)
        }
        return try {
            rpc("set_profile_pin", params)
            RemotePinSetResult.SUCCESS
        } catch (e: SyncException) {
            if (e !is SyncAuthException && e.message?.contains("Current PIN is required", ignoreCase = true) == true) {
                RemotePinSetResult.CURRENT_PIN_REQUIRED
            } else {
                throw e
            }
        }
    }

    /** Removes a profile's PIN on the server (RPC `clear_profile_pin`). */
    suspend fun clearProfilePin(profileId: Int, currentPin: String? = null) {
        rpc("clear_profile_pin", buildJsonObject {
            put("p_profile_id", profileId)
            if (!currentPin.isNullOrBlank()) put("p_current_pin", currentPin)
        })
    }

    /** Checks a PIN against the server's (RPC `verify_profile_pin`), which also counts failed attempts. */
    suspend fun verifyProfilePin(profileId: Int, pin: String): RemotePinVerify {
        val body = rpc("verify_profile_pin", buildJsonObject {
            put("p_profile_id", profileId)
            put("p_pin", pin)
        })
        val element = runCatching { syncJson.parseToJsonElement(body) }.getOrNull()
        val row = when (element) {
            is JsonArray -> element.firstOrNull()
            is JsonObject -> element
            else -> null
        } ?: return RemotePinVerify(unlocked = false, retryAfterSeconds = 0)
        val dto = syncJson.decodeFromJsonElement(PinVerifyDto.serializer(), row)
        return RemotePinVerify(dto.unlocked, dto.retryAfterSeconds)
    }

    // --- Library ---

    /** The whole library (RPC `sync_pull_library`, paged by `p_limit`/`p_offset`). */
    suspend fun pullLibrary(profileId: Int, pageSize: Int = LIBRARY_PAGE_SIZE): List<RemoteLibraryItem> {
        require(pageSize > 0)
        val items = mutableListOf<RemoteLibraryItem>()
        var offset = 0
        while (true) {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_limit", pageSize)
                put("p_offset", offset)
            }
            val page = syncJson.decodeFromString<List<LibraryDto>>(rpc("sync_pull_library", params))
            items += page.map(LibraryDto::toItem)
            if (page.size < pageSize) return items
            offset += pageSize
        }
    }

    /** The latest library event id (RPC `sync_get_library_delta_cursor`); pull deltas after it. */
    suspend fun libraryDeltaCursor(profileId: Int): Long = cursor("sync_get_library_delta_cursor", profileId)

    /** Library changes after [sinceEventId] (RPC `sync_pull_library_delta`), oldest first. */
    suspend fun pullLibraryDelta(profileId: Int, sinceEventId: Long, limit: Int = LIBRARY_PAGE_SIZE): List<RemoteLibraryEvent> =
        syncJson.decodeFromString<List<LibraryDto>>(rpc("sync_pull_library_delta", deltaParams(profileId, sinceEventId, limit)))
            .map { RemoteLibraryEvent(it.eventId ?: 0L, it.operation.isDelete(), it.toItem()) }

    /** Adds or updates titles (RPC `sync_push_library_items`, batches of 500). */
    suspend fun pushLibrary(profileId: Int, items: Collection<RemoteLibraryItem>) {
        items.chunked(LIBRARY_PAGE_SIZE).forEach { batch ->
            val params = buildJsonObject {
                putJsonArray("p_items") {
                    batch.forEach { item ->
                        addJsonObject {
                            put("content_id", item.id)
                            put("content_type", item.type)
                            put("name", item.name)
                            put("poster", item.poster)
                            put("poster_shape", item.posterShape.trim().uppercase().ifBlank { "POSTER" })
                            put("background", item.background)
                            put("description", item.description)
                            put("release_info", item.releaseInfo)
                            put("imdb_rating", item.imdbRating)
                            putJsonArray("genres") { item.genres.forEach { add(JsonPrimitive(it)) } }
                            put("addon_base_url", item.addonBaseUrl)
                            put("added_at", item.addedAtEpochMs)
                        }
                    }
                }
                put("p_profile_id", profileId)
                putOrigin()
            }
            rpc("sync_push_library_items", params)
        }
    }

    /** Removes titles (RPC `sync_delete_library_items`); the server logs a delete event for other devices. */
    suspend fun deleteLibrary(profileId: Int, keys: Collection<RemoteLibraryKey>) {
        keys.distinct().chunked(LIBRARY_PAGE_SIZE).forEach { batch ->
            val params = buildJsonObject {
                putJsonArray("p_keys") {
                    batch.forEach { key ->
                        addJsonObject {
                            put("content_id", key.contentId)
                            put("content_type", key.contentType)
                        }
                    }
                }
                put("p_profile_id", profileId)
                putOrigin()
            }
            rpc("sync_delete_library_items", params)
        }
    }

    // --- Watch progress ---

    /**
     * Progress entries (RPC `sync_pull_watch_progress`), optionally only those watched after
     * [sinceLastWatchedEpochMs] and at most [limit]. Returned as stored, without the TV app's
     * series-level mirror entries.
     */
    suspend fun pullProgress(profileId: Int, sinceLastWatchedEpochMs: Long? = null, limit: Int? = null): List<RemoteProgress> {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            if (sinceLastWatchedEpochMs != null) put("p_since_last_watched", sinceLastWatchedEpochMs)
            if (limit != null) put("p_limit", limit)
        }
        return syncJson.decodeFromString<List<ProgressDto>>(rpc("sync_pull_watch_progress", params)).map(ProgressDto::toProgress)
    }

    suspend fun progressDeltaCursor(profileId: Int): Long = cursor("sync_get_watch_progress_delta_cursor", profileId)

    /** Progress changes after [sinceEventId] (RPC `sync_pull_watch_progress_delta`). */
    suspend fun pullProgressDelta(profileId: Int, sinceEventId: Long, limit: Int = WATCH_PAGE_SIZE): List<RemoteProgressEvent> =
        syncJson.decodeFromString<List<ProgressDto>>(rpc("sync_pull_watch_progress_delta", deltaParams(profileId, sinceEventId, limit)))
            .map { RemoteProgressEvent(it.eventId ?: 0L, it.operation.isDelete(), it.toProgress()) }

    /**
     * Upserts progress (RPC `sync_push_watch_progress`), keyed by [RemoteProgress.key]. Like the TV
     * app, placeholder entries (position and duration both 1 ms or less) are not sent.
     */
    suspend fun pushProgress(profileId: Int, entries: Collection<RemoteProgress>) {
        val sendable = entries.filterNot { it.positionMs <= 1L && it.durationMs == 1L }
        if (sendable.isEmpty()) return
        val params = buildJsonObject {
            putJsonArray("p_entries") {
                sendable.forEach { p ->
                    addJsonObject {
                        put("content_id", p.contentId)
                        put("content_type", p.contentType)
                        put("video_id", p.videoId)
                        p.season?.let { put("season", it) }
                        p.episode?.let { put("episode", it) }
                        put("position", p.positionMs)
                        put("duration", p.durationMs)
                        put("last_watched", p.lastWatchedEpochMs)
                        put("progress_key", p.key)
                    }
                }
            }
            put("p_profile_id", profileId)
            putOrigin()
        }
        rpc("sync_push_watch_progress", params)
    }

    /** Deletes progress by key (RPC `sync_delete_watch_progress`, `p_keys` is an array of key strings). */
    suspend fun deleteProgress(profileId: Int, keys: Collection<String>) {
        val distinct = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return
        val params = buildJsonObject {
            putJsonArray("p_keys") { distinct.forEach { add(JsonPrimitive(it)) } }
            put("p_profile_id", profileId)
            putOrigin()
        }
        rpc("sync_delete_watch_progress", params)
    }

    // --- Watched history ---

    /** Every watched item (RPC `sync_pull_watched_items`, 1-based `p_page` of `p_page_size`). */
    suspend fun pullWatched(profileId: Int, pageSize: Int = WATCH_PAGE_SIZE): List<RemoteWatchedItem> {
        require(pageSize > 0)
        val items = mutableListOf<RemoteWatchedItem>()
        var page = 1
        while (true) {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_page", page)
                put("p_page_size", pageSize)
            }
            val rows = syncJson.decodeFromString<List<WatchedDto>>(rpc("sync_pull_watched_items", params))
            items += rows.map(WatchedDto::toItem)
            if (rows.size < pageSize) return items
            page++
        }
    }

    suspend fun watchedDeltaCursor(profileId: Int): Long = cursor("sync_get_watched_items_delta_cursor", profileId)

    /** Watched-history changes after [sinceEventId] (RPC `sync_pull_watched_items_delta`). */
    suspend fun pullWatchedDelta(profileId: Int, sinceEventId: Long, limit: Int = WATCH_PAGE_SIZE): List<RemoteWatchedEvent> =
        syncJson.decodeFromString<List<WatchedDto>>(rpc("sync_pull_watched_items_delta", deltaParams(profileId, sinceEventId, limit)))
            .map { RemoteWatchedEvent(it.eventId ?: 0L, it.operation.isDelete(), it.toItem()) }

    /** Upserts watched items (RPC `sync_push_watched_items`); a missing season/episode is sent as null. */
    suspend fun pushWatched(profileId: Int, items: Collection<RemoteWatchedItem>) {
        if (items.isEmpty()) return
        val params = buildJsonObject {
            putJsonArray("p_items") {
                items.forEach { item ->
                    addJsonObject {
                        put("content_id", item.contentId)
                        put("content_type", item.contentType)
                        put("title", item.title)
                        put("season", item.season)
                        put("episode", item.episode)
                        put("watched_at", item.watchedAtEpochMs)
                    }
                }
            }
            put("p_profile_id", profileId)
            putOrigin()
        }
        rpc("sync_push_watched_items", params)
    }

    /** Un-marks watched items (RPC `sync_delete_watched_items`); a missing season/episode is left out. */
    suspend fun deleteWatched(profileId: Int, keys: Collection<RemoteWatchedKey>) {
        val distinct = keys.toSet()
        if (distinct.isEmpty()) return
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            putJsonArray("p_keys") {
                distinct.forEach { key ->
                    addJsonObject {
                        put("content_id", key.contentId)
                        key.season?.let { put("season", it) }
                        key.episode?.let { put("episode", it) }
                    }
                }
            }
            putOrigin()
        }
        rpc("sync_delete_watched_items", params)
    }

    // --- Addons ---

    /**
     * The installed addons, in order (GET `/rest/v1/addons?user_id=eq.<owner>&profile_id=eq.<id>`).
     * For a profile with [RemoteProfile.usesPrimaryAddons], pass 1.
     */
    suspend fun pullAddons(profileId: Int): List<RemoteAddon> {
        val owner = syncOwnerId()
        val url = "${config.baseUrl}/rest/v1/addons".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("user_id", "eq.$owner")
            .addQueryParameter("profile_id", "eq.$profileId")
            .build()
        val result = authorized { token -> Request.Builder().url(url).syncHeaders(config, token).get().build() }
        if (!result.ok) throw result.failure("addons read")
        return syncJson.decodeFromString<List<AddonDto>>(result.body)
            .sortedBy { it.sortOrder }
            .map { RemoteAddon(it.url, it.sortOrder, it.enabled, it.name?.takeIf(String::isNotBlank)) }
    }

    /**
     * Replaces the profile's addon list (RPC `sync_push_addons`). The list is sent in [RemoteAddon.order]
     * order with `sort_order` = position, as the TV app does; addons left out are removed server-side.
     */
    suspend fun pushAddons(profileId: Int, addons: List<RemoteAddon>) {
        val params = buildJsonObject {
            putJsonArray("p_addons") {
                addons.sortedBy { it.order }.forEachIndexed { index, addon ->
                    addJsonObject {
                        put("url", addon.url)
                        put("sort_order", index)
                        put("enabled", addon.enabled)
                        if (!addon.name.isNullOrBlank()) put("name", addon.name)
                    }
                }
            }
            put("p_profile_id", profileId)
            putOrigin()
        }
        rpc("sync_push_addons", params)
    }

    // --- Profile settings ---

    /**
     * A profile's settings blob (RPC `sync_pull_profile_settings_blob`), or null when the account
     * has none yet. [platform] is the TV's by default, so both apps share one blob.
     */
    suspend fun pullSettingsBlob(profileId: Int, platform: String = SyncSettingsBlob.PLATFORM_TV): RemoteSettingsBlob? {
        val body = rpc("sync_pull_profile_settings_blob", buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", platform)
        })
        val element = runCatching { syncJson.parseToJsonElement(body) }.getOrNull()
        val row = when (element) {
            is JsonArray -> element.firstOrNull()
            is JsonObject -> element
            else -> null
        } as? JsonObject ?: return null
        val dto = syncJson.decodeFromJsonElement(SettingsBlobDto.serializer(), row)
        return RemoteSettingsBlob(dto.profileId ?: profileId, dto.settingsJson ?: return null, dto.updatedAt)
    }

    /**
     * Replaces a profile's settings blob (RPC `sync_push_profile_settings_blob`). The TV app replaces
     * every feature in it with what the blob holds, so send a whole blob: change one pulled with
     * [pullSettingsBlob] (see [SyncSettingsBlob]), or use [pushSharedSettings] / [pushParentalLimits].
     */
    suspend fun pushSettingsBlob(profileId: Int, settingsJson: JsonObject, platform: String = SyncSettingsBlob.PLATFORM_TV) {
        rpc("sync_push_profile_settings_blob", buildJsonObject {
            put("p_profile_id", profileId)
            put("p_settings_json", settingsJson)
            put("p_platform", platform)
            putOrigin()
        })
    }

    /** The shared settings in a profile's blob; null when the account has no blob for it. */
    suspend fun pullSharedSettings(profileId: Int): RemoteSharedSettings? =
        pullSettingsBlob(profileId)?.let { SyncSettingsBlob.sharedSettings(it.settingsJson) }

    /**
     * Writes [settings]' non-null fields into the profile's blob: pulls it, changes only those keys
     * and pushes it back, so the TV's other settings (and its parental limits) are kept.
     */
    suspend fun pushSharedSettings(profileId: Int, settings: RemoteSharedSettings) {
        val current = pullSettingsBlob(profileId)?.settingsJson
        pushSettingsBlob(profileId, SyncSettingsBlob.withSharedSettings(current, settings))
    }

    /**
     * A profile's parental limits from its blob; null when it has none, and always for profile 1,
     * whose limits the TV app never syncs. The TV app only ever tightens its own limits with these
     * (see [SyncMerge.parentalLimits]).
     */
    suspend fun pullParentalLimits(profileId: Int): RemoteParentalLimits? {
        if (profileId == PRIMARY_PROFILE_ID) return null
        return SyncSettingsBlob.parentalLimits(pullSettingsBlob(profileId)?.settingsJson)
    }

    /** Sets a profile's parental limits in its blob, keeping its settings (pull, change, push). */
    suspend fun pushParentalLimits(profileId: Int, limits: RemoteParentalLimits) {
        require(profileId != PRIMARY_PROFILE_ID) { "The primary profile has no synced limits" }
        val current = pullSettingsBlob(profileId)?.settingsJson
        pushSettingsBlob(profileId, SyncSettingsBlob.withParentalLimits(current, limits))
    }

    // --- Plumbing ---

    private fun deltaParams(profileId: Int, since: Long, limit: Int) = buildJsonObject {
        put("p_profile_id", profileId)
        put("p_since_event_id", since)
        put("p_limit", limit)
    }

    private suspend fun cursor(rpcName: String, profileId: Int): Long {
        val body = rpc(rpcName, buildJsonObject { put("p_profile_id", profileId) })
        val element = syncJson.parseToJsonElement(body)
        return (element as? JsonPrimitive)?.longOrNull
            ?: (element as? JsonPrimitive)?.content?.toLongOrNull()
            ?: 0L
    }

    private fun JsonObjectBuilder.putOrigin() {
        put("p_origin_client_id", originClientId)
    }

    /** POST /rest/v1/rpc/[name] and returns the response body. */
    private suspend fun rpc(name: String, params: JsonObject?): String {
        val url = "${config.baseUrl}/rest/v1/rpc/$name"
        val body = (params ?: JsonObject(emptyMap())).toJsonBody()
        val result = authorized { token ->
            Request.Builder().url(url).syncHeaders(config, token).post(body).build()
        }
        if (!result.ok) throw result.failure(name)
        return result.body
    }

    /** Sends with a fresh token; on 401 refreshes once and retries once. */
    private suspend fun authorized(build: (accessToken: String) -> Request): SyncHttpResult {
        if (session.expiresSoon(nowEpochSec())) refreshFrom(session.accessToken)
        val used = session.accessToken
        val first = http.sendSync(build(used))
        if (first.code != 401) return first
        refreshFrom(used)
        val retry = http.sendSync(build(session.accessToken))
        if (retry.code == 401) throw SyncAuthException(retry.errorText() ?: "The sync server rejected the session", 401)
        return retry
    }

    /** Refreshes unless another call already replaced the token that was [observedAccessToken]. */
    private suspend fun refreshFrom(observedAccessToken: String) = refreshMutex.withLock {
        val current = session
        if (current.accessToken != observedAccessToken) return@withLock
        val refreshed = auth.refresh(current)
        session = refreshed
        onSessionChanged(refreshed)
    }

    companion object {
        const val PRIMARY_PROFILE_ID = 1

        /** The TV app's profile limit (`ProfileManager.MAX_PROFILES`), sent as `p_client_max_profiles`. */
        const val MAX_PROFILES = 6
        private const val LIBRARY_PAGE_SIZE = 500
        private const val WATCH_PAGE_SIZE = 900
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** A new install id in the TV app's shape (`fetch-tv-` + 32 chars there; `fetch-desktop-` here). */
        fun newOriginClientId(): String {
            val random = SecureRandom()
            return "fetch-desktop-" + String(CharArray(32) { ALPHABET[random.nextInt(ALPHABET.length)] })
        }
    }
}

private fun String?.isDelete(): Boolean = this.equals("delete", ignoreCase = true)

@Serializable
private data class ProfileDto(
    @SerialName("profile_index") val profileIndex: Int,
    val name: String = "",
    @SerialName("avatar_color_hex") val avatarColorHex: String = "#1E88E5",
    @SerialName("uses_primary_addons") val usesPrimaryAddons: Boolean = false,
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("profile_background_id") val profileBackgroundId: String? = null,
    @SerialName("profile_background_url") val profileBackgroundUrl: String? = null,
)

@Serializable
private data class ProfileLockDto(
    @SerialName("profile_index") val profileIndex: Int,
    @SerialName("pin_enabled") val pinEnabled: Boolean = false,
    @SerialName("pin_locked_until") val pinLockedUntil: String? = null,
)

@Serializable
private data class PinVerifyDto(
    val unlocked: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int = 0,
)

@Serializable
private data class SettingsBlobDto(
    @SerialName("profile_id") val profileId: Int? = null,
    @SerialName("settings_json") val settingsJson: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** A library row (snapshot) or library event (delta): the delta adds `event_id` and `operation`. */
@Serializable
private data class LibraryDto(
    @SerialName("event_id") val eventId: Long? = null,
    val operation: String? = null,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val name: String = "",
    val poster: String? = null,
    @SerialName("poster_shape") val posterShape: String = "POSTER",
    val background: String? = null,
    val description: String? = null,
    @SerialName("release_info") val releaseInfo: String? = null,
    @SerialName("imdb_rating") val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    @SerialName("addon_base_url") val addonBaseUrl: String? = null,
    @SerialName("added_at") val addedAt: Long = 0L,
) {
    fun toItem() = RemoteLibraryItem(
        id = contentId,
        type = contentType,
        name = name,
        poster = poster,
        posterShape = posterShape.trim().uppercase().takeIf { it in POSTER_SHAPES } ?: "POSTER",
        background = background,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        addedAtEpochMs = addedAt,
    )
}

private val POSTER_SHAPES = setOf("POSTER", "LANDSCAPE", "SQUARE")

@Serializable
private data class ProgressDto(
    @SerialName("event_id") val eventId: Long? = null,
    val operation: String? = null,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    @SerialName("last_watched") val lastWatched: Long = 0L,
    @SerialName("progress_key") val progressKey: String? = null,
) {
    fun toProgress() = RemoteProgress(contentId, contentType, videoId, season, episode, position, duration, lastWatched, progressKey)
}

@Serializable
private data class WatchedDto(
    @SerialName("event_id") val eventId: Long? = null,
    val operation: String? = null,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String = "",
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("watched_at") val watchedAt: Long = 0L,
) {
    fun toItem() = RemoteWatchedItem(contentId, contentType, title, season, episode, watchedAt)
}

@Serializable
private data class AddonDto(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)
