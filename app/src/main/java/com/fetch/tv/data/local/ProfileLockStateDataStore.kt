package com.fetch.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileLockStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_lock_state",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/** Failed-attempt bookkeeping for one profile's PIN. */
data class PinAttemptState(
    val failures: Int = 0,
    val lockedUntilMs: Long = 0L
)

/**
 * Profile lock state, kept on the device.
 *
 * - `pin_enabled_map` mirrors which profiles have a PIN on the sync server (only filled when
 *   sync is configured and signed in).
 * - `pin_hash_map` holds the local salted PIN hash per profile; it works without any server.
 * - `pin_attempts_map` holds failed attempts and lockout per profile.
 */
@Singleton
class ProfileLockStateDataStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.profileLockStateDataStore)

    private val pinEnabledMapKey = stringPreferencesKey("pin_enabled_map")
    private val pinHashMapKey = stringPreferencesKey("pin_hash_map")
    private val pinAttemptsMapKey = stringPreferencesKey("pin_attempts_map")

    /** Profiles the sync server reports as PIN-locked. */
    val remotePinEnabled: Flow<Map<Int, Boolean>> = dataStore.data.map { prefs ->
        decodeBooleans(prefs[pinEnabledMapKey])
    }

    /** Local PIN hashes by profile id. */
    val localPinHashes: Flow<Map<Int, String>> = dataStore.data.map { prefs ->
        decodeStrings(prefs[pinHashMapKey])
    }

    /** Profiles that require a PIN, from either the local hash or the sync server. */
    val pinEnabled: Flow<Map<Int, Boolean>> = dataStore.data.map { prefs ->
        val remote = decodeBooleans(prefs[pinEnabledMapKey])
        val local = decodeStrings(prefs[pinHashMapKey]).keys.associateWith { true }
        remote.filterValues { it } + local
    }

    suspend fun replaceAll(states: Map<Int, Boolean>) {
        dataStore.edit { prefs ->
            prefs[pinEnabledMapKey] = encodeBooleans(states)
        }
    }

    suspend fun setPinEnabled(profileId: Int, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = decodeBooleans(prefs[pinEnabledMapKey]).toMutableMap()
            current[profileId] = enabled
            prefs[pinEnabledMapKey] = encodeBooleans(current)
        }
    }

    suspend fun localPinHash(profileId: Int): String? = localPinHashes.first()[profileId]

    suspend fun setLocalPinHash(profileId: Int, hash: String?) {
        dataStore.edit { prefs ->
            val current = decodeStrings(prefs[pinHashMapKey]).toMutableMap()
            if (hash == null) current.remove(profileId) else current[profileId] = hash
            prefs[pinHashMapKey] = encodeStrings(current)
        }
    }

    suspend fun attemptState(profileId: Int): PinAttemptState =
        decodeAttempts(dataStore.data.first()[pinAttemptsMapKey])[profileId] ?: PinAttemptState()

    suspend fun setAttemptState(profileId: Int, state: PinAttemptState) {
        dataStore.edit { prefs ->
            val current = decodeAttempts(prefs[pinAttemptsMapKey]).toMutableMap()
            if (state == PinAttemptState()) current.remove(profileId) else current[profileId] = state
            prefs[pinAttemptsMapKey] = current.entries.joinToString(separator = ",") {
                "${it.key}:${it.value.failures}:${it.value.lockedUntilMs}"
            }
        }
    }

    /** Removes everything stored for a deleted profile. */
    suspend fun removeProfile(profileId: Int) {
        setLocalPinHash(profileId, null)
        setAttemptState(profileId, PinAttemptState())
        dataStore.edit { prefs ->
            val current = decodeBooleans(prefs[pinEnabledMapKey]).toMutableMap()
            current.remove(profileId)
            prefs[pinEnabledMapKey] = encodeBooleans(current)
        }
    }

    /** Clears the sync-server mirror only; local PINs survive sign-out. */
    suspend fun clearRemoteStates() {
        dataStore.edit { prefs ->
            prefs.remove(pinEnabledMapKey)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun encodeBooleans(map: Map<Int, Boolean>): String =
        map.entries.joinToString(separator = ",") { "${it.key}:${it.value}" }

    private fun decodeBooleans(raw: String?): Map<Int, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val enabled = parts[1].toBooleanStrictOrNull() ?: return@mapNotNull null
            id to enabled
        }.toMap()
    }

    // Hashes contain ':' themselves, so entries are "<id>=<hash>" separated by ';'.
    private fun encodeStrings(map: Map<Int, String>): String =
        map.entries.joinToString(separator = ";") { "${it.key}=${it.value}" }

    private fun decodeStrings(raw: String?): Map<Int, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val id = entry.substring(0, separator).toIntOrNull() ?: return@mapNotNull null
            val value = entry.substring(separator + 1).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to value
        }.toMap()
    }

    private fun decodeAttempts(raw: String?): Map<Int, PinAttemptState> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val failures = parts[1].toIntOrNull() ?: return@mapNotNull null
            val lockedUntil = parts[2].toLongOrNull() ?: return@mapNotNull null
            id to PinAttemptState(failures, lockedUntil)
        }.toMap()
    }
}
