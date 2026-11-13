package com.fetch.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fetch.tv.core.parental.PARENT_PROFILE_ID
import com.fetch.tv.core.parental.PinAttemptPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Set when the file was unreadable and replaced. The PIN hashes went with it, so until the main
 * profile's PIN is known again it is treated as locked (see [ProfileLockStateDataStore.lostToCorruption])
 * rather than as having no PIN at all.
 */
private val lockStateCorruptionResetKey = booleanPreferencesKey("corruption_reset")

private val Context.profileLockStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_lock_state",
    // Fail closed: a corrupt file must not read as "no PINs".
    corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(lockStateCorruptionResetKey to true) }
)

/**
 * Failed-attempt bookkeeping for one profile's PIN.
 *
 * A lockout is kept as its length plus when it started, on both clocks: [lockedAtElapsedMs] is
 * `SystemClock.elapsedRealtime()`, which the user can't change, and [lockedAtWallMs] the wall
 * clock at that moment, only used to tell whether the device has rebooted since (the two then
 * no longer agree on when the device booted). A negative [lockedAtElapsedMs] means unknown.
 */
data class PinAttemptState(
    val failures: Int = 0,
    val lockoutMs: Long = 0L,
    val lockedAtElapsedMs: Long = 0L,
    val lockedAtWallMs: Long = 0L
)

/**
 * Profile lock state, kept on the device.
 *
 * - `pin_enabled_map` mirrors which profiles have a PIN on the sync server. It is only written
 *   while sync is configured and signed in, and is kept after sign-out, so a PIN the server
 *   reported stays enforced until the server says otherwise or the profile is removed.
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
    private val corruptionResetKey = lockStateCorruptionResetKey

    /**
     * True after the file was found corrupt and replaced: which profiles had a PIN, and the PINs
     * themselves, are unknown. Cleared once the main profile's PIN is known again (a hash for it
     * is stored, or a sync server or website reports its state) or everything is cleared.
     */
    val lostToCorruption: Flow<Boolean> = dataStore.data.map { prefs -> prefs[corruptionResetKey] == true }

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
            // The remote says whether the main profile has a PIN; a PIN it has is checked there.
            if (PARENT_PROFILE_ID in states) prefs.remove(corruptionResetKey)
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
            if (profileId == PARENT_PROFILE_ID && hash != null) prefs.remove(corruptionResetKey)
        }
    }

    suspend fun attemptState(profileId: Int): PinAttemptState =
        decodeAttempts(dataStore.data.first()[pinAttemptsMapKey])[profileId] ?: PinAttemptState()

    suspend fun setAttemptState(profileId: Int, state: PinAttemptState) {
        dataStore.edit { prefs ->
            val current = decodeAttempts(prefs[pinAttemptsMapKey]).toMutableMap()
            if (state == PinAttemptState()) current.remove(profileId) else current[profileId] = state
            prefs[pinAttemptsMapKey] = current.entries.joinToString(separator = ",") { (id, value) ->
                "$id:${value.failures}:${value.lockoutMs}:${value.lockedAtElapsedMs}:${value.lockedAtWallMs}"
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

    // "<id>:<failures>:<lockoutMs>:<lockedAtElapsedMs>:<lockedAtWallMs>" entries separated by ','.
    // Older builds wrote "<id>:<failures>:<lockedUntilWallMs>"; such a lockout is read as starting
    // at an unknown time, which restarts it in full on the next check.
    private fun decodeAttempts(raw: String?): Map<Int, PinAttemptState> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val failures = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            when (parts.size) {
                3 -> {
                    val lockedUntil = parts[2].toLongOrNull() ?: return@mapNotNull null
                    val lockout = if (lockedUntil > 0L) PinAttemptPolicy.lockoutAfterFailures(failures) else 0L
                    id to PinAttemptState(failures, lockout, lockedAtElapsedMs = if (lockout > 0L) -1L else 0L)
                }
                5 -> {
                    val lockout = parts[2].toLongOrNull() ?: return@mapNotNull null
                    val atElapsed = parts[3].toLongOrNull() ?: return@mapNotNull null
                    val atWall = parts[4].toLongOrNull() ?: return@mapNotNull null
                    id to PinAttemptState(failures, lockout, atElapsed, atWall)
                }
                else -> null
            }
        }.toMap()
    }
}
