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
import com.fetch.tv.core.lifecycle.AppForeground
import com.fetch.tv.domain.model.DiscoverLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import java.io.IOException
import com.fetch.tv.core.util.runSuspendCatching

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
                runSuspendCatching { DiscoverLocation.valueOf(it) }.getOrNull()
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
    internal companion object {
        const val DATASTORE_FILE_SUFFIX = ".preferences_pb"
        const val SHADOW_COPY_SUFFIX = ".bak"

        /** A shadow copy is written to this file first and then renamed over the old copy. */
        const val SHADOW_COPY_TEMP_SUFFIX = ".tmp"

        /**
         * At most one shadow copy per store in this window. Each copy rewrites the whole file,
         * so without it marking an episode watched rewrote the full watch history twice (the
         * store and its copy), and a burst of edits did so once per edit.
         */
        const val SHADOW_COPY_INTERVAL_MS = 30_000L
    }

    /** Runs the background flush of shadow copies; lives as long as this singleton. */
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Going to the background writes every copy still waiting for its 30 s window, so a
        // process killed in the background (the usual way an app dies on a TV) still leaves a
        // current backup. MainActivity.onStop sets AppForeground.
        flushScope.launch {
            AppForeground.isForeground
                .drop(1)
                .filter { foreground -> !foreground }
                .collect { flushShadowCopies() }
        }
    }

    /** Writes every shadow copy that waits for its debounce window, now. */
    fun flushShadowCopies() {
        val stores = synchronized(lock) { cache.values.toList() }
        stores.forEach { scoped ->
            val shadow = scoped.store as? ShadowCopyDataStore ?: return@forEach
            // On the store's own scope, so a store closed by clearProfile doesn't write again.
            scoped.scope.launch { shadow.flush() }
        }
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
        "device_local_player_prefs",
        "profile_lock_state",
        "profile_restrictions",
        "profile_settings",
        "screen_time",
        "skip_marker_settings",
        "tv_channel_prefs",
        // Updater channel/banner prefs.
        "update_settings"
    )

    /** Set of DataStore file names that were reset due to corruption during this session. */
    val corruptedFileNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The [featureName] store of [profileId]. For a profile removed by [clearProfile] (until
     * [markProfileCreated] reuses the id) this is a [DeletedProfileDataStore]: it reads as empty
     * and refuses writes with a [CancellationException], so sync work still running for the
     * deleted profile stops instead of recreating its files. It never throws here, so callers
     * outside coroutines (and flows keyed on a profile that was just deleted) keep working.
     */
    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p$profileId"
        synchronized(lock) {
            // Checked under the lock: clearProfile marks the id before it collects the cached
            // stores to close, so a store is either refused here or closed there.
            if (profileId in deletedProfileIds) return DeletedProfileDataStore(fileName)
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
            runSuspendCatching { scoped.store.edit { it.clear() } }
            // Cancel the scope so DataStore releases the file from its active-files
            // registry. Wait for completion before any subsequent get() can create
            // a fresh DataStore for the same path.
            scoped.job.cancel()
            scoped.job.join()
            // The cancelled scope may never run the shadow-copy write that would
            // have dropped the .bak, so remove it here.
            synchronized(shadowCopyLock) {
                shadowCopyFile(key).delete()
                shadowCopyTempFile(key).delete()
            }
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
                if (file.name.endsWith(suffix) ||
                    file.name.endsWith("$suffix$SHADOW_COPY_SUFFIX") ||
                    file.name.endsWith("$suffix$SHADOW_COPY_SUFFIX$SHADOW_COPY_TEMP_SUFFIX")
                ) {
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
            runSuspendCatching { scoped.store.edit { it.clear() } }
                .onFailure { failures += "clear $name: ${it.message}" }
        }
        deletedProfileIds.clear()
        corruptedFileNames.clear()

        val cachedFileNames = cachedStores.keys.mapTo(mutableSetOf()) { "$it$DATASTORE_FILE_SUFFIX" }
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            synchronized(shadowCopyLock) {
                dataStoreDir.listFiles()?.forEach { file ->
                    // A temp file left by a copy that was interrupted counts as a shadow copy.
                    val name = file.name.removeSuffix(SHADOW_COPY_TEMP_SUFFIX)
                    val isShadowCopy = name.endsWith(SHADOW_COPY_SUFFIX)
                    val primaryName = name.removeSuffix(SHADOW_COPY_SUFFIX)
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
            produceFile = { prepareStoreFile(fileName) }
        )

        // Wrap the store to keep a shadow copy: once on the first read (app start), so a
        // consistent backup exists before any corruption can occur, then after edits, at most
        // once per SHADOW_COPY_INTERVAL_MS and when the app goes to the background.
        val wrappedStore = ShadowCopyDataStore(store, fileName, scope, this)

        val scoped = ScopedDataStore(wrappedStore, scope, job)
        cache[fileName] = scoped
        return scoped
    }

    /**
     * The store's file, with its directory and an empty file created first. DataStore calls
     * this lazily on the store's IO scope at first access, so these syscalls no longer run on
     * whichever thread (often main) first asks for the store (#454).
     *
     * The directory prevents ENOENT on the first read of a profile-scoped file that does not
     * exist yet. DataStore 1.1.x (okio-based) can throw FileNotFoundException when the file
     * does not exist and a race occurs, so an empty file is pre-created (DataStore overwrites
     * it on the first write).
     */
    private fun prepareStoreFile(fileName: String): File {
        val targetFile = context.preferencesDataStoreFile(fileName)
        targetFile.parentFile?.let { dir -> if (!dir.exists()) dir.mkdirs() }
        if (!targetFile.exists()) {
            try { targetFile.createNewFile() } catch (_: Exception) { }
        }
        return targetFile
    }

    private fun shadowCopyFile(fileName: String): File =
        File(File(context.filesDir, "datastore"), "$fileName$DATASTORE_FILE_SUFFIX$SHADOW_COPY_SUFFIX")

    private fun shadowCopyTempFile(fileName: String): File =
        File(File(context.filesDir, "datastore"), "$fileName$DATASTORE_FILE_SUFFIX$SHADOW_COPY_SUFFIX$SHADOW_COPY_TEMP_SUFFIX")

    internal fun writeShadowCopy(fileName: String, preferences: Preferences) {
        val sourceFile = File(File(context.filesDir, "datastore"), "$fileName$DATASTORE_FILE_SUFFIX")
        val backupFile = shadowCopyFile(fileName)
        val tempFile = shadowCopyTempFile(fileName)
        try {
            synchronized(shadowCopyLock) {
                if (preferences.asMap().isEmpty()) {
                    // The store was cleared (sign-out, Trakt disconnect, profile reset).
                    // Keeping the old copy would let corruption recovery bring the
                    // cleared data back, so drop it.
                    backupFile.delete()
                    tempFile.delete()
                } else if (sourceFile.exists() && sourceFile.length() > 0) {
                    // Copies the file as it is now, so a copy queued before a clear
                    // that only runs after it copies the cleared file. The copy goes to a
                    // temp file that is synced and then renamed over the old copy, so a crash
                    // or power cut mid-write leaves the previous copy whole instead of a
                    // truncated one that corruption recovery could not read.
                    copyAndSync(sourceFile, tempFile)
                    if (!tempFile.renameTo(backupFile)) {
                        tempFile.delete()
                        throw IOException("rename to ${backupFile.name} failed")
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            Log.w("ProfileDataStoreFactory", "Failed to write shadow copy for $fileName: ${e.message}")
        }
    }

    private fun copyAndSync(source: File, target: File) {
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
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
 * Thin wrapper around a DataStore that keeps a shadow copy of the underlying preferences file
 * for corruption recovery: once after the first successful read, then after edits, debounced to
 * one copy per [ProfileDataStoreFactory.SHADOW_COPY_INTERVAL_MS] (the first edit after a quiet
 * window copies at once; later ones wait for the window's end and share one copy). A clear is
 * copied at once, so the old data's backup is gone as soon as the store is empty, and
 * [ProfileDataStoreFactory.flushShadowCopies] writes whatever is waiting when the app goes to the
 * background.
 */
internal class ShadowCopyDataStore(
    private val delegate: DataStore<Preferences>,
    private val fileName: String,
    private val scope: CoroutineScope,
    private val factory: ProfileDataStoreFactory,
    private val intervalMs: Long = ProfileDataStoreFactory.SHADOW_COPY_INTERVAL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DataStore<Preferences> {

    // Set by the first read that starts the initial shadow copy, so later reads don't each launch
    // another one. Reset only when that copy failed, so a later read can try again.
    private val initialShadowStarted = AtomicBoolean(false)

    /** An edit happened that the shadow copy doesn't have yet. */
    private val dirty = AtomicBoolean(false)

    /** A debounced copy is scheduled; later edits ride on it. */
    private val scheduled = AtomicBoolean(false)

    /** Serializes copies of this store, so a flush and a scheduled copy can't both write. */
    private val writeMutex = Mutex()

    /** When the last copy was written; null before the first one. */
    @Volatile private var lastCopyAtMs: Long? = null

    /** How many copies this store has written; for tests and the write-volume benchmark. */
    @Volatile internal var copiesWritten: Int = 0
        private set

    override val data: Flow<Preferences>
        get() = delegate.data.also {
            if (initialShadowStarted.compareAndSet(false, true)) {
                scope.launch(ioDispatcher) {
                    try {
                        val prefs = delegate.data.first()
                        // An empty store has nothing worth backing up; that still counts as done.
                        if (prefs != emptyPreferences()) {
                            dirty.set(true)
                            flush()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        initialShadowStarted.set(false)
                    }
                }
            }
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val result = delegate.updateData(transform)
        // Each edit keeps the copy current, so the initial one is no longer needed.
        initialShadowStarted.set(true)
        dirty.set(true)
        if (result.asMap().isEmpty()) {
            // Cleared: drop the old copy now rather than at the end of the window.
            scope.launch(ioDispatcher) { flush() }
        } else {
            schedule()
        }
        return result
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) {
            val wait = lastCopyAtMs?.let { last -> last + intervalMs - nowMs() } ?: 0L
            if (wait > 0) delay(wait)
            flush()
            scheduled.set(false)
            // An edit that landed while this copy was being written found `scheduled` still set
            // and didn't schedule its own; give it one.
            if (dirty.get()) schedule()
        }
    }

    /** Writes the shadow copy now if an edit is waiting for one. */
    suspend fun flush() {
        writeMutex.withLock {
            if (!dirty.getAndSet(false)) return
            try {
                // Read the current data (not the edit's result): only whether the store is empty
                // matters here, and the copy itself is of the file as it is now.
                factory.writeShadowCopy(fileName, delegate.data.first())
                copiesWritten++
            } catch (e: CancellationException) {
                dirty.set(true)
                throw e
            } catch (_: Exception) {
                // writeShadowCopy logs its own failures; the next window tries again.
                dirty.set(true)
            } finally {
                // A failed attempt also starts a window, so a failing read can't retry in a loop.
                lastCopyAtMs = nowMs()
            }
        }
    }
}

/**
 * What [ProfileDataStoreFactory.get] hands out for a deleted profile until its id is reused.
 * Reads see no data and touch no file; writes are refused with a [CancellationException], which
 * stops the coroutine doing them (an edit for a deleted profile is work that should no longer run).
 */
internal class DeletedProfileDataStore(private val fileName: String) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flowOf(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw CancellationException("DataStore $fileName belongs to a deleted profile")
}
