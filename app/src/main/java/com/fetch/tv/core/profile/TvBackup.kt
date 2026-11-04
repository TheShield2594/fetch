package com.fetch.tv.core.profile

import com.fetch.tv.core.parental.ProfilePinManager
import com.fetch.tv.data.local.AddonPreferences
import com.fetch.tv.domain.model.UserProfile
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** What [TvBackup.restore] did, by profile name. */
data class TvBackupRestoreResult(
    /** Profiles this TV already had, by name, that the backup was merged into. */
    val merged: List<String>,
    /** Profiles the backup added. */
    val added: List<String>,
    /** Profiles left out: the TV's one of that name has a PIN, or there was no room for another. */
    val skipped: List<String>,
    /** Entries in the backup that couldn't be read at all, so have no name to show. */
    val unreadable: Int = 0
)

/**
 * A backup of the whole TV without a sync server: every profile [ProfileTransfer] can export, in
 * one file, for after a reset or on a new TV. Like a profile file it carries no sign-ins, API
 * keys, PINs or parental limits; profiles with a PIN are left out, as they are from a profile file.
 * Addon links are in it only when asked for ([export]), since they can hold account keys.
 *
 * Restoring merges the backed-up main profile into this TV's main profile, and each other one into
 * the profile of the same name ([ProfileTransfer.importInto]); any without a match is added. So
 * restoring the same backup twice doesn't make copies, and a TV reset to its one default profile
 * gets that profile back with its name, settings and history.
 */
@Singleton
class TvBackup @Inject constructor(
    private val profileManager: ProfileManager,
    private val profilePinManager: ProfilePinManager,
    private val profileTransfer: ProfileTransfer,
    private val addonPreferences: AddonPreferences
) {
    /**
     * [includeAddons] adds each profile's addon links. They're left out unless asked for because
     * a configured addon's link often holds a debrid or other account key.
     */
    suspend fun export(includeAddons: Boolean = false): JsonObject {
        val exportable = profileTransfer.exportableProfiles()
        val left = profileManager.profiles.value.filter { profile -> exportable.none { it.id == profile.id } }
        val documents = exportable.map { profile ->
            val extra = buildMap<String, JsonElement> {
                put(PRIMARY_KEY, JsonPrimitive(profile.isPrimary))
                if (!profile.isPrimary) put(USES_PRIMARY_ADDONS_KEY, JsonPrimitive(profile.usesPrimaryAddons))
                // A profile on the main profile's addons has none of its own.
                if (includeAddons && (profile.isPrimary || !profile.usesPrimaryAddons)) {
                    put(ADDONS_KEY, encodeAddons(addonPreferences.backupFor(profile.id)))
                }
            }
            JsonObject(profileTransfer.export(profile.id) + extra)
        }
        return buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("includesAddons", includeAddons)
            put("profiles", JsonArray(documents))
            put("leftOut", buildJsonArray { left.forEach { add(JsonPrimitive(it.name)) } })
        }
    }

    suspend fun restore(document: JsonObject): TvBackupRestoreResult {
        require(isBackup(document)) { "That isn't a Fetch backup." }
        val version = (document["version"] as? JsonPrimitive)?.intOrNull ?: 0
        require(version in 1..VERSION) { "That backup is from a newer version of Fetch. Update this TV first." }
        val profiles = (document["profiles"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        require(profiles.isNotEmpty()) { "That backup has no profiles in it." }

        val merged = mutableListOf<String>()
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var unreadable = 0
        val locked = profilePinManager.pinEnabled.first()
        // Each of this TV's profiles takes at most one backed-up profile.
        val used = mutableSetOf<Int>()
        for (profileDocument in profiles) {
            val name = runCatching { profileTransfer.profileName(profileDocument) }.getOrNull()
            if (name == null) {
                unreadable++
                continue
            }
            val isPrimary = (profileDocument[PRIMARY_KEY] as? JsonPrimitive)?.booleanOrNull == true
            val existing = matchProfile(name, isPrimary, profileManager.profiles.value.filter { it.id !in used })
            existing?.let { used += it.id }
            try {
                when {
                    existing != null && locked[existing.id] == true -> skipped += name
                    existing != null -> {
                        profileTransfer.importInto(existing.id, profileDocument)
                        restoreAddons(existing.id, profileDocument)
                        merged += name
                    }
                    else -> {
                        val profile = profileTransfer.import(profileDocument)
                        used += profile.id
                        restoreAddons(profile.id, profileDocument)
                        added += profile.name
                    }
                }
            } catch (error: IllegalArgumentException) {
                // Out of room, or a profile the file can't describe: the rest still restore.
                skipped += name
            }
        }
        return TvBackupRestoreResult(merged = merged, added = added, skipped = skipped, unreadable = unreadable)
    }

    /**
     * Whether the profile shares the main profile's addons, then its own addon links if the backup
     * has them. A failure here leaves the profile's other restored data, and the rest of the
     * backup, in place.
     */
    private suspend fun restoreAddons(profileId: Int, profileDocument: JsonObject) {
        try {
            var profile = profileManager.profiles.value.firstOrNull { it.id == profileId } ?: return
            val sharesPrimary = (profileDocument[USES_PRIMARY_ADDONS_KEY] as? JsonPrimitive)?.booleanOrNull
            if (!profile.isPrimary && sharesPrimary != null && sharesPrimary != profile.usesPrimaryAddons) {
                profile = profile.copy(usesPrimaryAddons = sharesPrimary)
                profileManager.updateProfile(profile)
            }
            val addons = decodeAddons(profileDocument[ADDONS_KEY] as? JsonObject ?: return) ?: return
            if (!profile.isPrimary && profile.usesPrimaryAddons) return
            addonPreferences.restoreFor(profileId, addons)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Couldn't restore the addons of profile $profileId", error)
        }
    }

    companion object {
        const val FORMAT = "fetch-backup"
        const val VERSION = 1
        // Marks the TV's main profile in a backup; the main profile restores into this TV's main one.
        private const val PRIMARY_KEY = "primary"
        private const val ADDONS_KEY = "addons"
        private const val USES_PRIMARY_ADDONS_KEY = "usesPrimaryAddons"
        private const val TAG = "TvBackup"

        internal fun encodeAddons(backup: AddonPreferences.Backup): JsonObject = buildJsonObject {
            put("urls", buildJsonArray { backup.urls.forEach { add(JsonPrimitive(it)) } })
            put("enabled", JsonObject(backup.enabled.mapValues { JsonPrimitive(it.value) }))
            put("names", JsonObject(backup.names.mapValues { JsonPrimitive(it.value) }))
        }

        /** Null when there's no list of links to restore, so a damaged entry leaves the profile's addons alone. */
        internal fun decodeAddons(element: JsonObject): AddonPreferences.Backup? {
            val urls = (element["urls"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
                ?: return null
            val enabled = (element["enabled"] as? JsonObject).orEmpty()
                .mapNotNull { (url, value) -> (value as? JsonPrimitive)?.booleanOrNull?.let { url to it } }
                .toMap()
            val names = (element["names"] as? JsonObject).orEmpty()
                .mapNotNull { (url, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { url to it } }
                .toMap()
            return AddonPreferences.Backup(urls = urls, enabled = enabled, names = names)
        }

        fun isBackup(document: JsonObject): Boolean =
            (document["format"] as? JsonPrimitive)?.contentOrNull == FORMAT

        /**
         * The profile a backed-up one belongs to: the main profile goes to this TV's main profile
         * whatever either is called (a reset TV's main profile has the default name), any other to
         * the profile of the same name, ignoring case and spaces at the ends. Null to add it.
         */
        internal fun matchProfile(name: String, isPrimary: Boolean, profiles: List<UserProfile>): UserProfile? {
            if (isPrimary) profiles.firstOrNull { it.isPrimary }?.let { return it }
            val key = name.trim()
            if (key.isEmpty()) return null
            return profiles.firstOrNull { it.name.trim().equals(key, ignoreCase = true) }
        }
    }
}
