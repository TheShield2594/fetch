package com.fetch.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.fetch.tv.domain.model.DiscoverLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import java.io.IOException

private class ScopedDataStore(
    val store: DataStore<Preferences>,
    val scope: CoroutineScope,
    val job: Job
)

internal val discoverLocationMigration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        legacySearchDiscoverEnabledKey in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        val legacy = mutable[legacySearchDiscoverEnabledKey]
        if (legacy != null && mutable[discoverLocationKey] == null) {
            val rememberedLocation = mutable[lastNonOffDiscoverLocationKey]?.let {
                runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
            }?.takeIf { it != DiscoverLocation.OFF }
            val resolved = if (legacy && rememberedLocation != null) {
                rememberedLocation
            } else {
                DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy)
            }
            mutable[discoverLocationKey] = resolved.name
        }
        mutable.remove(legacySearchDiscoverEnabledKey)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, ScopedDataStore>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()

    /**
     * Serializes shadow-copy writes with every deletion of a shadow copy. Without it,
     * a copy that opened the primary file before a clear could finish after the clear
     * deleted the backup and recreate it with pre-clear data (Trakt credentials
     * included), which corruption recovery would later restore. A copy that runs
     * after the clear copies the cleared file instead.
     */
    private val shadowCopyLock = Any()
    private companion object {
        const val DATASTORE_FILE_SUFFIX = ".preferences_pb"
        const val SHADOW_COPY_SUFFIX = ".bak"
    }

    /**
     * Device-wide stores that live outside this factory and must survive sign-out. Every other
     * `.preferences_pb` in the datastore directory is treated as profile-scoped and deleted by
     * [clearProfileScopedData], so a new standalone store has to be added here or it will be
     * wiped from under its live DataStore.
     */
    private val retainedStandaloneDataStoreNames = setOf(
        "app_onboarding",
        "auth_session_notice_store",
        "debug_settings",
        "device_local_player_prefs",
        "profile_lock_state",
        "profile_restrictions",
        "profile_settings",
        "screen_time",
        "skip_marker_settings",
        "tv_channel_prefs"
    )

    /** Set of DataStore file names that were reset due to corruption during this session. */
    val corruptedFileNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p$profileId"
        synchronized(lock) {
            cache[fileName]?.let { return it.store }
            return createAndCache(fileName).store
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        deletedProfileIds.add(profileId)
        val suffix = "_p$profileId"
        val keysToRemove = synchronized(lock) {
            cache.keys.filter { it.endsWith(suffix) }
        }
        for (key in keysToRemove) {
            val scoped = synchronized(lock) { cache.remove(key) } ?: continue
            runCatching { scoped.store.edit { it.clear() } }
            // Cancel the scope so DataStore releases the file from its active-files
            // registry. Wait for completion before any subsequent get() can create
            // a fresh DataStore for the same path.
            scoped.job.cancel()
            scoped.job.join()
            // The cancelled scope may never run the shadow-copy write that would
            // have dropped the .bak, so remove it here.
            synchronized(shadowCopyLock) { shadowCopyFile(key).delete() }
        }
    }

    /**
     * Deletes every DataStore file of [profileId] from disk, including the
     * `.bak` shadow copies, so a profile that later reuses the id starts empty.
     * Call [clearProfile] first so no live DataStore still holds these files.
     */
    fun deleteProfileFiles(profileId: Int) {
        if (profileId == 1) return
        val suffix = "_p$profileId$DATASTORE_FILE_SUFFIX"
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) return
        synchronized(shadowCopyLock) {
            dataStoreDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(suffix) || file.name.endsWith("$suffix$SHADOW_COPY_SUFFIX")) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Clears every profile-scoped store. Every step runs even if an earlier one fails, but a
     * failed store edit or file deletion is reported by throwing at the end, so the caller
     * doesn't treat the old account's data as gone when some of it is still on disk.
     */
    suspend fun clearProfileScopedData() = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        val cachedStores = synchronized(lock) { cache.toMap() }
        cachedStores.forEach { (name, scoped) ->
            runCatching { scoped.store.edit { it.clear() } }
                .onFailure { failures += "clear $name: ${it.message}" }
        }
        deletedProfileIds.clear()
        corruptedFileNames.clear()

        val cachedFileNames = cachedStores.keys.mapTo(mutableSetOf()) { "$it$DATASTORE_FILE_SUFFIX" }
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            synchronized(shadowCopyLock) {
                dataStoreDir.listFiles()?.forEach { file ->
                    val isShadowCopy = file.name.endsWith(SHADOW_COPY_SUFFIX)
                    val primaryName = file.name.removeSuffix(SHADOW_COPY_SUFFIX)
                    if (!isProfileScopedDataStoreFile(primaryName)) return@forEach
                    // Live stores were cleared above and keep their (now empty) file, but
                    // their shadow copies still hold the old data and must go too.
                    if (isShadowCopy || primaryName !in cachedFileNames) {
                        if (!file.delete() && file.exists()) failures += "delete ${file.name}"
                    }
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw IOException("Profile-scoped data not fully cleared: ${failures.joinToString()}")
        }
    }

    fun isProfileDeleted(profileId: Int): Boolean = profileId in deletedProfileIds

    fun markProfileCreated(profileId: Int) {
        deletedProfileIds.remove(profileId)
    }

    private fun isProfileScopedDataStoreFile(fileName: String): Boolean {
        if (!fileName.endsWith(DATASTORE_FILE_SUFFIX)) return false
        val dataStoreName = fileName.removeSuffix(DATASTORE_FILE_SUFFIX)
        return dataStoreName !in retainedStandaloneDataStoreNames
    }

    private fun createAndCache(fileName: String): ScopedDataStore {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val migrations = if (fileName == "layout_settings" || fileName.startsWith("layout_settings_p")) {
            listOf(discoverLocationMigration)
        } else {
            emptyList()
        }
        // Ensure the datastore directory exists — prevents ENOENT on first read
        // when a profile-scoped file hasn't been created yet.
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) {
            dataStoreDir.mkdirs()
        }
        // DataStore 1.1.x (okio-based) can throw FileNotFoundException if the file
        // doesn't exist yet and a race condition occurs. Pre-create an empty file
        // to avoid this edge case (DataStore will overwrite on first write).
        val targetFile = File(dataStoreDir, "$fileName.preferences_pb")
        if (!targetFile.exists()) {
            try { targetFile.createNewFile() } catch (_: Exception) { }
        }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { ex ->
                Log.e("ProfileDataStoreFactory", "DataStore corrupted ($fileName): ${ex.message} — attempting shadow copy recovery")
                val recovered = recoverFromShadowCopy(fileName)
                if (recovered != null) {
                    Log.i("ProfileDataStoreFactory", "DataStore recovered from shadow copy ($fileName)")
                    recovered
                } else {
                    Log.e("ProfileDataStoreFactory", "DataStore shadow copy unavailable ($fileName) — resetting to empty preferences")
                    corruptedFileNames.add(fileName)
                    emptyPreferences()
                }
            },
            scope = scope,
            migrations = migrations,
            produceFile = { context.preferencesDataStoreFile(fileName) }
        )

        // Wrap store to persist a shadow copy after each successful read.
        // The shadow copy is written once on first data emission (app start),
        // ensuring a consistent backup exists before any corruption can occur.
        val wrappedStore = ShadowCopyDataStore(store, fileName, scope, this)

        val scoped = ScopedDataStore(wrappedStore, scope, job)
        cache[fileName] = scoped
        return scoped
    }

    private fun shadowCopyFile(fileName: String): File =
        File(File(context.filesDir, "datastore"), "$fileName$DATASTORE_FILE_SUFFIX$SHADOW_COPY_SUFFIX")

    internal fun writeShadowCopy(fileName: String, preferences: Preferences) {
        val sourceFile = File(File(context.filesDir, "datastore"), "$fileName$DATASTORE_FILE_SUFFIX")
        val backupFile = shadowCopyFile(fileName)
        try {
            synchronized(shadowCopyLock) {
                if (preferences.asMap().isEmpty()) {
                    // The store was cleared (sign-out, Trakt disconnect, profile reset).
                    // Keeping the old copy would let corruption recovery bring the
                    // cleared data back, so drop it.
                    backupFile.delete()
                } else if (sourceFile.exists() && sourceFile.length() > 0) {
                    // Copies the file as it is now, so a copy queued before a clear
                    // that only runs after it copies the cleared file.
                    sourceFile.copyTo(backupFile, overwrite = true)
                }
            }
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Failed to write shadow copy for $fileName: ${e.message}")
        }
    }

    private fun recoverFromShadowCopy(fileName: String): Preferences? {
        val backupFile = shadowCopyFile(fileName)
        if (!backupFile.exists() || backupFile.length() == 0L) return null
        return try {
            val source = backupFile.inputStream().use { input ->
                okio.Buffer().apply { readFrom(input) }
            }
            val preferences = kotlinx.coroutines.runBlocking {
                androidx.datastore.preferences.core.PreferencesSerializer.readFrom(source)
            }
            Log.i("ProfileDataStoreFactory", "Parsed shadow copy for $fileName (${preferences.asMap().size} keys)")
            preferences
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Shadow copy recovery failed for $fileName: ${e.message}")
            null
        }
    }
}


/**
 * Thin wrapper around a DataStore that writes a shadow copy of the underlying
 * preferences file after the first successful read and after each edit.
 * This ensures a known-good backup exists for corruption recovery.
 */
private class ShadowCopyDataStore(
    private val delegate: DataStore<Preferences>,
    private val fileName: String,
    private val scope: CoroutineScope,
    private val factory: ProfileDataStoreFactory
) : DataStore<Preferences> {

    @Volatile
    private var shadowWritten = false

    override val data: kotlinx.coroutines.flow.Flow<Preferences>
        get() = delegate.data.also {
            if (!shadowWritten) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val prefs = delegate.data.first()
                        if (prefs != emptyPreferences()) {
                            factory.writeShadowCopy(fileName, prefs)
                            shadowWritten = true
                        }
                    } catch (_: Exception) { }
                }
            }
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val result = delegate.updateData(transform)
        scope.launch(Dispatchers.IO) {
            try {
                factory.writeShadowCopy(fileName, result)
                shadowWritten = true
            } catch (_: Exception) { }
        }
        return result
    }
}
