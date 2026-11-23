package com.fetch.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.preferencesDataStoreFile
import com.fetch.tv.R
import com.fetch.tv.domain.model.UserProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { ex ->
            android.util.Log.e("ProfileDataStore", "DataStore corrupted: ${ex.message} — recovering the profile list")
            recoverAfterCorruption()
        },
        produceFile = { context.preferencesDataStoreFile(STORE_NAME) }
    )

    /**
     * A copy of this store kept outside it, rewritten on every edit, so a corrupted store doesn't
     * lose every profile and open the TV on the unrestricted main profile (#618).
     */
    private val backup by lazy { context.getSharedPreferences(BACKUP_NAME, Context.MODE_PRIVATE) }

    private val profilesJsonKey = stringPreferencesKey("profiles_json")
    private val activeProfileIdKey = intPreferencesKey("active_profile_id")
    private val hasEverSelectedProfileKey = booleanPreferencesKey("profile_has_ever_selected")
    private val rememberLastProfileEnabledKey = booleanPreferencesKey("remember_last_profile_enabled")
    private val confirmExitEnabledKey = booleanPreferencesKey("confirm_exit_enabled")
    private val startupSplashEnabledKey = booleanPreferencesKey("startup_splash_enabled")

    private val profileListType = Types.newParameterizedType(List::class.java, ProfileJson::class.java)

    private val backupSeeded = java.util.concurrent.atomic.AtomicBoolean(false)

    val profilesList: Flow<List<UserProfile>> = dataStore.data.map { prefs ->
        // Installs from before the backup existed get one on the first read, not only on the next edit.
        if (backupSeeded.compareAndSet(false, true) && prefs[profilesJsonKey] != null &&
            !backup.contains(profilesJsonKey.name)
        ) {
            writeBackup(prefs)
        }
        val json = prefs[profilesJsonKey]
        if (json != null) {
            parseProfiles(json)
        } else {
            listOf(defaultPrimaryProfile())
        }
    }

    val activeProfileId: Flow<Int> = dataStore.data.map { prefs ->
        prefs[activeProfileIdKey] ?: 1
    }

    val hasEverSelectedProfile: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hasEverSelectedProfileKey] ?: false
    }

    val rememberLastProfileEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rememberLastProfileEnabledKey] ?: true
    }

    val confirmExitEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[confirmExitEnabledKey] ?: false
    }

    val startupSplashEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[startupSplashEnabledKey] ?: true
    }

    suspend fun setActiveProfile(id: Int) {
        editAndBackUp { prefs ->
            prefs[activeProfileIdKey] = id
            prefs[hasEverSelectedProfileKey] = true
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        editAndBackUp { prefs ->
            prefs[rememberLastProfileEnabledKey] = enabled
        }
    }

    suspend fun setConfirmExitEnabled(enabled: Boolean) {
        editAndBackUp { prefs ->
            prefs[confirmExitEnabledKey] = enabled
        }
    }

    suspend fun setStartupSplashEnabled(enabled: Boolean) {
        editAndBackUp { prefs ->
            prefs[startupSplashEnabledKey] = enabled
        }
    }

    suspend fun upsertProfile(profile: UserProfile) {
        editAndBackUp { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                current[index] = profile
            } else {
                current.add(profile)
            }
            prefs[profilesJsonKey] = serializeProfiles(current)
        }
    }

    suspend fun deleteProfile(id: Int) {
        if (id == 1) return
        editAndBackUp { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            current.removeAll { it.id == id }
            prefs[profilesJsonKey] = serializeProfiles(current)
            if ((prefs[activeProfileIdKey] ?: 1) == id) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    suspend fun replaceAllProfiles(profiles: List<UserProfile>) {
        editAndBackUp { prefs ->
            val normalizedProfiles = normalizeProfiles(profiles)
            prefs[profilesJsonKey] = serializeProfiles(normalizedProfiles)
            val activeId = prefs[activeProfileIdKey] ?: 1
            if (normalizedProfiles.none { it.id == activeId }) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    suspend fun clearAll() {
        editAndBackUp { prefs ->
            prefs.clear()
        }
    }

    private suspend fun editAndBackUp(transform: suspend (MutablePreferences) -> Unit) {
        val saved = dataStore.edit(transform)
        writeBackup(saved)
    }

    private fun writeBackup(prefs: Preferences) {
        backup.edit()
            .clear()
            .apply {
                prefs[profilesJsonKey]?.let { putString(profilesJsonKey.name, it) }
                prefs[activeProfileIdKey]?.let { putInt(activeProfileIdKey.name, it) }
                prefs[hasEverSelectedProfileKey]?.let { putBoolean(hasEverSelectedProfileKey.name, it) }
                prefs[rememberLastProfileEnabledKey]?.let { putBoolean(rememberLastProfileEnabledKey.name, it) }
                prefs[confirmExitEnabledKey]?.let { putBoolean(confirmExitEnabledKey.name, it) }
                prefs[startupSplashEnabledKey]?.let { putBoolean(startupSplashEnabledKey.name, it) }
            }
            .apply()
    }

    /**
     * What a corrupted store is replaced with: the backup when there is one; otherwise a profile
     * list rebuilt from the slots that still have files on disk, with no remembered choice, so the
     * TV opens on the profile picker (where PINs apply) rather than on the main profile.
     */
    private fun recoverAfterCorruption(): Preferences {
        val profilesJson = backup.getString(profilesJsonKey.name, null)
        if (profilesJson != null) {
            android.util.Log.i("ProfileDataStore", "Profile list restored from its backup")
            return mutablePreferencesOf().apply {
                this[profilesJsonKey] = profilesJson
                if (backup.contains(activeProfileIdKey.name)) {
                    this[activeProfileIdKey] = backup.getInt(activeProfileIdKey.name, 1)
                }
                listOf(
                    hasEverSelectedProfileKey,
                    rememberLastProfileEnabledKey,
                    confirmExitEnabledKey,
                    startupSplashEnabledKey
                ).forEach { key ->
                    if (backup.contains(key.name)) this[key] = backup.getBoolean(key.name, false)
                }
            }
        }
        val slots = profileSlotsOnDisk(java.io.File(context.filesDir, "datastore"))
        android.util.Log.w("ProfileDataStore", "No profile list backup; rebuilt slots $slots from disk")
        val profiles = listOf(defaultPrimaryProfile()) + slots.map { id ->
            UserProfile(id = id, name = context.getString(R.string.profile_default_name, id), avatarColorHex = "#1E88E5")
        }
        return mutablePreferencesOf().apply { this[profilesJsonKey] = serializeProfiles(profiles) }
    }

    private fun defaultPrimaryProfile() = UserProfile(
        id = 1,
        name = context.getString(R.string.profile_default_name, 1),
        avatarColorHex = "#1E88E5"
    )

    private fun parseProfiles(json: String?): List<UserProfile> {
        if (json.isNullOrBlank()) return listOf(defaultPrimaryProfile())
        return try {
            val adapter = moshi.adapter<List<ProfileJson>>(profileListType)
            val parsed = adapter.fromJson(json) ?: return listOf(defaultPrimaryProfile())
            normalizeProfiles(parsed.map { it.toDomain() })
        } catch (e: Exception) {
            listOf(defaultPrimaryProfile())
        }
    }

    private fun normalizeProfiles(profiles: List<UserProfile>): List<UserProfile> {
        val nonEmpty = profiles.ifEmpty { listOf(defaultPrimaryProfile()) }
        if (nonEmpty.any { it.id == 1 }) return nonEmpty
        return listOf(defaultPrimaryProfile()) + nonEmpty
    }

    private fun serializeProfiles(profiles: List<UserProfile>): String {
        val adapter = moshi.adapter<List<ProfileJson>>(profileListType)
        return adapter.toJson(profiles.map { ProfileJson.fromDomain(it) })
    }
}

private const val STORE_NAME = "profile_settings"
private const val BACKUP_NAME = "profile_settings_backup"

private val PROFILE_SLOT_FILE = Regex("_p(\\d+)\\.preferences_pb$")

/** The secondary profile ids that have a profile-scoped store file in [dataStoreDir], ascending. */
internal fun profileSlotsOnDisk(dataStoreDir: java.io.File): List<Int> =
    dataStoreDir.listFiles().orEmpty()
        .mapNotNull { PROFILE_SLOT_FILE.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
        .filter { it > 1 }
        .distinct()
        .sorted()

@com.squareup.moshi.JsonClass(generateAdapter = true)
internal data class ProfileJson(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    val profileBackgroundId: String? = null,
    val profileBackgroundUrl: String? = null
) {
    fun toDomain() = UserProfile(
        id = id,
        name = name,
        avatarColorHex = avatarColorHex,
        usesPrimaryAddons = usesPrimaryAddons,
        avatarId = avatarId,
        avatarUrl = avatarUrl,
        profileBackgroundId = profileBackgroundId,
        profileBackgroundUrl = profileBackgroundUrl
    )

    companion object {
        fun fromDomain(profile: UserProfile) = ProfileJson(
            id = profile.id,
            name = profile.name,
            avatarColorHex = profile.avatarColorHex,
            usesPrimaryAddons = profile.usesPrimaryAddons,
            avatarId = profile.avatarId,
            avatarUrl = profile.avatarUrl,
            profileBackgroundId = profile.profileBackgroundId,
            profileBackgroundUrl = profile.profileBackgroundUrl
        )
    }
}
