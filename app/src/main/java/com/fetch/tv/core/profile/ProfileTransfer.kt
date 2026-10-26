package com.fetch.tv.core.profile

import android.util.Log
import com.fetch.tv.core.parental.ProfilePinManager
import com.fetch.tv.core.sync.ProfileSettingsSyncService
import com.fetch.tv.core.sync.ProfileSyncService
import com.fetch.tv.data.local.LibraryPreferences
import com.fetch.tv.data.local.WatchProgressPreferences
import com.fetch.tv.data.local.WatchedItemsPreferences
import com.fetch.tv.domain.model.SavedLibraryItem
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.domain.model.WatchedItem
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileTransfer"

/**
 * Moves a profile to another TV without a sync server: [export] writes its look, settings, library,
 * watch history and progress to one JSON document, and [import] adds that document as a new
 * profile. Credentials, PINs and parental limits are never included, and settings that only fit
 * the TV they were made on stay behind.
 */
@Singleton
class ProfileTransfer @Inject constructor(
    private val profileManager: ProfileManager,
    private val profilePinManager: ProfilePinManager,
    private val profileSyncService: ProfileSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val libraryPreferences: LibraryPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchProgressPreferences: WatchProgressPreferences
) {
    private val gson = Gson()

    /** Profiles that can be exported: a profile with a PIN has to be unlocked on the TV instead. */
    suspend fun exportableProfiles(): List<UserProfile> {
        val pins = profilePinManager.pinEnabled.first()
        return profileManager.profiles.value.filter { pins[it.id] != true }
    }

    suspend fun export(profileId: Int): JsonObject {
        val profile = exportableProfiles().firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("That profile can't be exported.")
        val library = libraryPreferences.getAllItems(profileId)
        val watched = watchedItemsPreferences.getAllItems(profileId)
        val progress = watchProgressPreferences.getAllRawEntries(profileId)
        return buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("profile", buildJsonObject {
                put("name", profile.name)
                put("avatarColorHex", profile.avatarColorHex)
                profile.avatarId?.let { put("avatarId", it) }
                profile.avatarUrl?.let { put("avatarUrl", it) }
                profile.profileBackgroundId?.let { put("profileBackgroundId", it) }
                profile.profileBackgroundUrl?.let { put("profileBackgroundUrl", it) }
            })
            put("settings", profileSettingsSyncService.exportLocalSettings(profileId))
            put("library", buildJsonArray { library.forEach { add(Json.parseToJsonElement(gson.toJson(it))) } })
            put("watched", buildJsonArray { watched.forEach { add(Json.parseToJsonElement(gson.toJson(it))) } })
            put("progress", Json.parseToJsonElement(watchProgressPreferences.encodeEntries(progress)))
        }
    }

    /** Adds [document], as [export] wrote it, as a new profile on this TV. */
    suspend fun import(document: JsonObject): UserProfile {
        require(document.text("format") == FORMAT) { "That isn't a Fetch profile file." }
        val version = (document["version"] as? JsonPrimitive)?.intOrNull ?: 0
        require(version in 1..VERSION) { "That profile file is from a newer version of Fetch. Update this TV first." }
        val details = document["profile"] as? JsonObject ?: throw IllegalArgumentException("The profile is missing.")
        val name = details.text("name")?.trim()?.take(MAX_NAME_LENGTH).orEmpty()
        val colorHex = details.text("avatarColorHex")?.takeIf { COLOR_HEX.matches(it) } ?: DEFAULT_COLOR
        val avatarId = details.text("avatarId")

        val created = profileManager.createProfile(name = name, avatarColorHex = colorHex, avatarId = avatarId)
            ?: throw IllegalArgumentException("This TV already has ${ProfileManager.MAX_PROFILES} profiles. Delete one first.")
        val profile = created.copy(
            avatarUrl = details.text("avatarUrl")?.takeIf { it.isHttpUrl() },
            profileBackgroundId = details.text("profileBackgroundId"),
            profileBackgroundUrl = details.text("profileBackgroundUrl")?.takeIf { it.isHttpUrl() }
        )
        if (profile != created) profileManager.updateProfile(profile)
        runCatchingNonCancellation("profiles push") { profileSyncService.pushToRemote() }

        (document["settings"] as? JsonObject)?.let { settings ->
            runCatchingNonCancellation("settings") {
                profileSettingsSyncService.importLocalSettings(profile.id, settings)
            }
        }
        (document["library"] as? JsonArray)?.let { items ->
            runCatchingNonCancellation("library") {
                decodeEach(
                    items,
                    SavedLibraryItem::class.java,
                    required = listOf("id", "type", "name", "posterShape"),
                    defaults = mapOf("genres" to JsonArray(emptyList()))
                )
                    .forEach { libraryPreferences.addItem(it, profile.id) }
            }
        }
        (document["watched"] as? JsonArray)?.let { items ->
            runCatchingNonCancellation("watched") {
                val watched = decodeEach(items, WatchedItem::class.java, required = listOf("contentId", "contentType", "title"))
                watchedItemsPreferences.mergeRemoteItems(watched, profile.id)
            }
        }
        (document["progress"] as? JsonObject)?.let { entries ->
            runCatchingNonCancellation("progress") {
                watchProgressPreferences.mergeRemoteEntries(
                    remoteEntries = watchProgressPreferences.decodeEntries(entries.toString()),
                    profileId = profile.id,
                    removeMissingRemoteEntries = false
                )
            }
        }
        return profile
    }

    // Gson leaves a missing field null even where Kotlin says it can't be, so an item missing one
    // of the [required] text fields is skipped, and other missing fields take their [defaults].
    private fun <T> decodeEach(
        items: JsonArray,
        type: Class<T>,
        required: List<String>,
        defaults: Map<String, JsonElement> = emptyMap()
    ): List<T> = items.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (required.any { item.text(it) == null }) return@mapNotNull null
        val filled = JsonObject(defaults + item.filterValues { it !is JsonNull })
        runCatching { gson.fromJson(filled.toString(), type) }.getOrNull()
    }

    // One part that can't be read shouldn't lose the rest of the profile.
    private suspend fun runCatchingNonCancellation(part: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Couldn't import the profile's $part", error)
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun String.isHttpUrl(): Boolean = startsWith("https://") || startsWith("http://")

    companion object {
        const val FORMAT = "fetch-profile"
        const val VERSION = 1
        private const val MAX_NAME_LENGTH = 20
        private const val DEFAULT_COLOR = "#1E88E5"
        private val COLOR_HEX = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    }
}
