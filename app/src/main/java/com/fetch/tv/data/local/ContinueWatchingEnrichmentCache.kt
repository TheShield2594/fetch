package com.fetch.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.fetch.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null
)

data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null
)

@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "CwEnrichCache"
        private const val THROTTLE_MS = 1_000L
        private const val CACHE_DIR_NAME = "cw_enrichment"

        /**
         * Guards every snapshot file. Shared with [deleteProfileFiles] so a deletion
         * cannot interleave with a write that is already in progress.
         */
        private val mutex = Mutex()

        /**
         * Bumped (under [mutex]) by every profile deletion. A save captures it when it starts and
         * gives up if it changed, so a save that began before a deletion can't write afterwards,
         * not even into a profile that reuses the deleted id.
         */
        private val deletionGeneration = java.util.concurrent.atomic.AtomicLong(0)

        /**
         * Deletes the Continue Watching snapshots (and any half-written `.tmp`
         * files) of [profileId]. Profile ids are reused, so this must run when
         * a profile is deleted.
         */
        suspend fun deleteProfileFiles(filesDir: File, profileId: Int) = mutex.withLock {
            deletionGeneration.incrementAndGet()
            val dir = File(filesDir, CACHE_DIR_NAME)
            if (!dir.exists()) return@withLock
            listOf("inprogress_${profileId}.json", "nextup_${profileId}.json").forEach { name ->
                File(dir, name).delete()
                File(dir, "$name.tmp").delete()
            }
        }
    }

    private val gson = Gson()
    @Volatile private var lastNextUpWriteMs = 0L
    @Volatile private var lastInProgressWriteMs = 0L
    @Volatile private var lastNextUpHash = 0
    @Volatile private var lastInProgressHash = 0

    /** Incremented when cache is cleared; observers can collect to trigger refresh. */
    private val _cacheCleared = kotlinx.coroutines.flow.MutableStateFlow(0)
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = _cacheCleared

    /** Incremented on every successful snapshot write; channel sync observes this. */
    private val _snapshotVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val snapshotVersion: kotlinx.coroutines.flow.StateFlow<Int> = _snapshotVersion

    // --- Next Up snapshot cache ---

    private fun nextUpFile(profileId: Int = profileManager.activeProfileId.value): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        dir.mkdirs()
        return File(dir, "nextup_${profileId}.json")
    }

    suspend fun getNextUpSnapshot(): List<CachedNextUpItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = nextUpFile()
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedNextUpItem>>() {}.type)
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read next-up cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveNextUpSnapshot(items: List<CachedNextUpItem>, force: Boolean = false) {
        // Bind the save to the profile it was made for, before any suspension.
        val origin = SaveOrigin(profileManager.activeProfileId.value, deletionGeneration.get())
        saveNextUpSnapshotFor(origin, items, force)
    }

    private suspend fun saveNextUpSnapshotFor(
        origin: SaveOrigin,
        items: List<CachedNextUpItem>,
        force: Boolean
    ) = withContext(Dispatchers.IO) {
        val contentHash = items.hashCode()
        if (!force) {
            if (contentHash == lastNextUpHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastNextUpWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            // A deleted profile's snapshot must not be recreated by a save that was
            // already running; its id can be reused by a new profile.
            if (!isStillValid(origin)) return@withLock
            try {
                val file = nextUpFile(origin.profileId)
                atomicWrite(file, gson.toJson(items))
                lastNextUpWriteMs = System.currentTimeMillis()
                lastNextUpHash = contentHash
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write next-up cache: ${e.message}")
            }
        }
    }

    // --- In-progress snapshot cache ---

    private fun inProgressFile(profileId: Int = profileManager.activeProfileId.value): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        dir.mkdirs()
        return File(dir, "inprogress_${profileId}.json")
    }

    suspend fun getInProgressSnapshot(): List<CachedInProgressItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = inProgressFile()
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedInProgressItem>>() {}.type)
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read in-progress cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveInProgressSnapshot(items: List<CachedInProgressItem>, force: Boolean = false) {
        // Bind the save to the profile it was made for, before any suspension.
        val origin = SaveOrigin(profileManager.activeProfileId.value, deletionGeneration.get())
        saveInProgressSnapshotFor(origin, items, force)
    }

    private suspend fun saveInProgressSnapshotFor(
        origin: SaveOrigin,
        items: List<CachedInProgressItem>,
        force: Boolean
    ) = withContext(Dispatchers.IO) {
        val contentHash = items.hashCode()
        if (!force) {
            if (contentHash == lastInProgressHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastInProgressWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            // A deleted profile's snapshot must not be recreated by a save that was
            // already running; its id can be reused by a new profile.
            if (!isStillValid(origin)) return@withLock
            try {
                val file = inProgressFile(origin.profileId)
                atomicWrite(file, gson.toJson(items))
                lastInProgressWriteMs = System.currentTimeMillis()
                lastInProgressHash = contentHash
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write in-progress cache: ${e.message}")
            }
        }
    }

    /**
     * Deletes all CW enrichment cache files for the active profile.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                nextUpFile().delete()
                inProgressFile().delete()
                lastNextUpHash = 0
                lastInProgressHash = 0
                Log.d(TAG, "Cleared CW enrichment cache for profile ${profileManager.activeProfileId.value}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear CW enrichment cache: ${e.message}")
            }
        }
        _cacheCleared.value++
    }

    /** The profile a save was made for, and the deletion generation when it started. */
    private data class SaveOrigin(val profileId: Int, val generation: Long)

    /**
     * Under [mutex]: the save's profile is still the active one and still exists, and no profile
     * was deleted since the save started (its data may belong to a profile that is gone).
     */
    private fun isStillValid(origin: SaveOrigin): Boolean = snapshotSaveStillValid(
        originProfileId = origin.profileId,
        originGeneration = origin.generation,
        currentGeneration = deletionGeneration.get(),
        activeProfileId = profileManager.activeProfileId.value,
        profileIds = profileManager.profiles.value.map { it.id }
    )

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

}

/**
 * Whether a Continue Watching snapshot save may still write: no profile was deleted since it
 * started, and the profile it was made for is still the active one and still exists.
 */
internal fun snapshotSaveStillValid(
    originProfileId: Int,
    originGeneration: Long,
    currentGeneration: Long,
    activeProfileId: Int,
    profileIds: List<Int>
): Boolean = originGeneration == currentGeneration &&
    activeProfileId == originProfileId &&
    originProfileId in profileIds
