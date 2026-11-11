package com.fetch.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.domain.model.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watch progress per profile, one entry per movie or episode (keyed by [createKey]). The entries
 * live in [entries] (SQLite), so saving the position during playback writes that one entry
 * however long the history is (audit G5). The push and delta bookkeeping stays in DataStore.
 *
 * Older versions kept every entry in one JSON map in DataStore, split into a "recent" file of
 * the 200 latest and an "archive" file of the rest (and before that, one map in the metadata
 * file). A profile's maps are moved into [entries] the first time its progress is used.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    @WatchProgressEntries private val entries: KeyedEntryStore
) {
    companion object {
        private const val TAG = "WatchProgressPrefs"

        /** The [KeyedEntry.kind] of a progress entry; the table holds nothing else. */
        internal const val KIND = "progress"
    }

    private fun metadataStore(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, WATCH_PROGRESS_METADATA_FEATURE)

    private fun recentStore(profileId: Int) = factory.get(profileId, WATCH_PROGRESS_RECENT_FEATURE)

    private fun archiveStore(profileId: Int) = factory.get(profileId, WATCH_PROGRESS_ARCHIVE_FEATURE)

    private val gson = Gson()
    private val lastSuccessfulPushMsKey = longPreferencesKey("last_successful_push_ms")
    private val deltaCursorKey = longPreferencesKey("watch_progress_delta_cursor")
    private val deltaInitializedKey = booleanPreferencesKey("watch_progress_delta_initialized")
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val migratedProfiles: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val migrationMutex = Mutex()

    // Decoded entries per profile, keyed by their stored JSON: a save re-parses only the entry it
    // changed, not the whole history. Entries that fail to parse are cached as null.
    private val decodeCaches = ConcurrentHashMap<Int, AtomicReference<Map<String, WatchProgress?>>>()

    private val hotProgressSnapshots =
        ConcurrentHashMap<Int, kotlinx.coroutines.flow.StateFlow<Map<String, WatchProgress>?>>()

    /** Every entry of [profileId] by key, decoded once per change and shared by all observers. */
    private fun progressSnapshots(profileId: Int): Flow<Map<String, WatchProgress>> {
        return hotProgressSnapshots.computeIfAbsent(profileId) {
            flow {
                if (isDeleted(profileId)) {
                    emit(emptyMap())
                    return@flow
                }
                ensureMigrated(profileId)
                emitAll(entries.observe(profileId).map { rows -> decode(profileId, rows) })
            }
                .flowOn(Dispatchers.Default)
                .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
        }.mapNotNull { it }
    }

    private fun isDeleted(profileId: Int) = metadataStore(profileId) is DeletedProfileDataStore

    /**
     * Moves [profileId]'s progress out of the legacy DataStore maps, once per process. Entries are
     * inserted before the maps are removed, and an entry already moved wins, so a move cut short
     * finishes on the next start without losing anything.
     */
    private suspend fun ensureMigrated(profileId: Int) {
        if (profileId in migratedProfiles) return
        migrationMutex.withLock {
            if (profileId in migratedProfiles) return
            if (isDeleted(profileId)) return
            val metadata = metadataStore(profileId)
            val legacyStores = listOf(metadata, archiveStore(profileId), recentStore(profileId))
            // Oldest layout first, so the newer buckets win for a key in more than one.
            val legacyJson = legacyStores.map { store -> store to store.data.first()[watchProgressEntriesKey] }
            if (legacyJson.any { it.second != null }) {
                val legacy = LinkedHashMap<String, WatchProgress>()
                legacyJson.forEach { (_, json) -> json?.let { legacy.putAll(parseProgressMap(it)) } }
                val normalized = normalizeWatchProgressEntries(legacy)
                entries.insertMissing(profileId, normalized.map { (key, progress) -> progress.toEntry(key) })
                legacyJson.forEach { (store, json) ->
                    if (json != null) store.edit { it.remove(watchProgressEntriesKey) }
                }
                Log.i(TAG, "Moved ${normalized.size} progress entries of profile $profileId to the database")
            }
            migratedProfiles += profileId
        }
    }

    /** Refuses writes for a deleted profile, as its DataStore does, then makes sure it's moved. */
    private suspend fun <T> write(profileId: Int, block: suspend () -> T): T {
        if (isDeleted(profileId)) throw CancellationException("Profile $profileId was deleted")
        ensureMigrated(profileId)
        return block()
    }

    private fun WatchProgress.toEntry(key: String = createKey(this)) =
        KeyedEntry(kind = KIND, key = key, contentId = contentId, sortTime = lastWatched, json = gson.toJson(this))

    private fun Map<String, WatchProgress>.toEntries(): Map<String, KeyedEntry> =
        mapValues { (key, progress) -> progress.toEntry(key) }

    private fun decode(profileId: Int, rows: List<KeyedEntry>): Map<String, WatchProgress> {
        val cache = decodeCaches.getOrPut(profileId) { AtomicReference(emptyMap()) }
        val previous = cache.get()
        val decoded = HashMap<String, WatchProgress?>(rows.size * 2)
        val result = LinkedHashMap<String, WatchProgress>(rows.size * 2)
        rows.forEach { row ->
            val progress = if (previous.containsKey(row.json)) previous[row.json] else parseEntry(row.json)
            decoded[row.json] = progress
            if (progress != null) result[row.key] = progress
        }
        cache.set(decoded)
        return result
    }

    private fun parseEntry(json: String): WatchProgress? = try {
        parseWatchProgressFromJson(gson.fromJson(json, JsonElement::class.java))
    } catch (e: com.google.gson.JsonParseException) {
        Log.w(TAG, "Skipping malformed watch progress entry")
        null
    }

    /** The stored entries of [profileId] among [keys], by key. */
    private suspend fun storedEntries(profileId: Int, keys: Collection<String>): Map<String, KeyedEntry> =
        entries.get(profileId, KIND, keys).associateBy { it.key }

    private fun Map<String, KeyedEntry>.decoded(profileId: Int): Map<String, WatchProgress> = decode(profileId, values.toList())

    /** Persisted timestamp of the last successful push to remote. */
    suspend fun getLastSuccessfulPushMs(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = metadataStore(profileId).data.first()
        return prefs[lastSuccessfulPushMsKey] ?: 0L
    }

    /**
     * Advances the stored push point, never lowering it. The comparison happens inside
     * the edit, so two pushes finishing out of order cannot leave the older one on disk.
     * Nothing needs to lower it: deleting a profile removes the whole store.
     */
    suspend fun advanceLastSuccessfulPushMs(timestampMs: Long, profileId: Int = profileManager.activeProfileId.value) {
        metadataStore(profileId).edit { prefs ->
            val stored = prefs[lastSuccessfulPushMsKey] ?: 0L
            prefs[lastSuccessfulPushMsKey] = maxOf(stored, timestampMs)
        }
    }

    suspend fun getDeltaCursor(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = metadataStore(profileId).data.first()
        return prefs[deltaCursorKey] ?: 0L
    }

    suspend fun isDeltaInitialized(profileId: Int = profileManager.activeProfileId.value): Boolean {
        val prefs = metadataStore(profileId).data.first()
        return prefs[deltaInitializedKey] ?: false
    }

    suspend fun setDeltaState(cursor: Long, initialized: Boolean = true, profileId: Int = profileManager.activeProfileId.value) {
        metadataStore(profileId).edit { prefs ->
            prefs[deltaCursorKey] = cursor.coerceAtLeast(0L)
            prefs[deltaInitializedKey] = initialized
        }
        Log.d(TAG, "setDeltaState: profile=$profileId cursor=${cursor.coerceAtLeast(0L)} initialized=$initialized")
    }

    /**
     * All watch progress, latest first, one entry per title: for a series its most recently
     * watched episode (on a tie, the highest season and episode), so Continue Watching shows
     * each title once. Grouping and sorting run off the main thread, once per change.
     */
    val allProgress: Flow<List<WatchProgress>> = profileManager.activeProfileId.flatMapLatest { pid ->
        observeAllProgress(pid)
    }

    fun observeAllProgress(profileId: Int): Flow<List<WatchProgress>> {
        return progressSnapshots(profileId).map { all ->
            all.values
                .groupBy { it.contentId }
                .mapNotNull { (_, items) ->
                    items.maxWithOrNull(
                        compareBy<WatchProgress> { it.lastWatched }
                            .thenBy { it.season ?: 0 }
                            .thenBy { it.episode ?: 0 }
                    )
                }
                .sortedByDescending { it.lastWatched }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    val allRawProgress: Flow<List<WatchProgress>> = profileManager.activeProfileId.flatMapLatest { pid ->
        observeAllRawProgress(pid)
    }

    fun observeAllRawProgress(profileId: Int): Flow<List<WatchProgress>> {
        return progressSnapshots(profileId).map { all ->
            all.values.sortedByDescending { it.lastWatched }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    /**
     * Get items that are in progress (not completed)
     */
    val continueWatching: Flow<List<WatchProgress>> = allProgress.map { list ->
        list.filter { it.isInProgress() }
    }

    /** One title's entries by key, from its own query rather than the whole history. */
    private fun observeContentEntries(contentId: String, profileId: Int): Flow<Map<String, WatchProgress>> = flow {
        if (isDeleted(profileId)) {
            emit(emptyMap())
            return@flow
        }
        ensureMigrated(profileId)
        emitAll(entries.observeContent(profileId, contentId).map { rows -> decode(profileId, rows) })
    }

    /**
     * Get watch progress for a specific content item
     */
    fun getProgress(contentId: String, profileId: Int = profileManager.activeProfileId.value): Flow<WatchProgress?> {
        return observeContentEntries(contentId, profileId).map { map ->
            // Try direct key first (movies), then find latest episode entry (series).
            map[contentId] ?: map.values
                .filter { it.contentId == contentId }
                .maxByOrNull { it.lastWatched }
        }
    }

    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int, profileId: Int = profileManager.activeProfileId.value): Flow<WatchProgress?> {
        val key = "${contentId}_s${season}e${episode}"
        return observeContentEntries(contentId, profileId).map { map -> map[key] }
    }

    /**
     * Get all episode progress for a series
     */
    fun getAllEpisodeProgress(contentId: String, profileId: Int = profileManager.activeProfileId.value): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return observeContentEntries(contentId, profileId).map { map ->
            map.values
                .filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associateBy { (it.season!! to it.episode!!) }
        }
    }

    /**
     * Save or update watch progress. Writes the one entry (and removes the series-level entry
     * an episode replaces), whatever the size of the history.
     */
    suspend fun saveProgress(
        progress: WatchProgress,
        profileId: Int = profileManager.activeProfileId.value
    ) = saveProgressBatch(listOf(progress), profileId)

    suspend fun saveProgressBatch(
        progressList: List<WatchProgress>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (progressList.isEmpty()) return
        write(profileId) {
            entries.transaction {
                // The entries the save reads: each key, and the series-level key an episode replaces.
                val keys = progressList.flatMap { listOf(createKey(it), it.contentId) }
                val stored = storedEntries(profileId, keys)
                val updated = stored.decoded(profileId).toMutableMap()
                upsertProgressEntries(updated, progressList)
                entries.writeChanges(profileId, KIND, stored, updated.toEntries())
            }
        }
    }

    /**
     * Remove watch progress for a specific item
     */
    suspend fun removeProgress(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        write(profileId) {
            entries.transaction {
                val keys = if (season != null && episode != null) {
                    // The episode and the series-level entry, so the title leaves Continue Watching.
                    listOf("${contentId}_s${season}e${episode}", contentId)
                } else {
                    // Every entry of the title.
                    entries.getContent(profileId, contentId)
                        .map { it.key }
                        .filter { key -> key == contentId || key.startsWith("${contentId}_s") } + contentId
                }
                Log.d(TAG, "removeProgress contentId=$contentId season=$season episode=$episode keys=${keys.joinToString()}")
                entries.delete(profileId, KIND, keys)
            }
        }
    }

    /**
     * Remove watch progress for multiple episodes in one transaction.
     */
    suspend fun removeProgressBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (episodes.isEmpty()) return
        val keys = episodes.map { (season, episode) -> "${contentId}_s${season}e${episode}" } + contentId
        write(profileId) { entries.delete(profileId, KIND, keys) }
        Log.d(TAG, "removeProgressBatch contentId=$contentId removed=${episodes.size} episodes")
    }

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(
        progress: WatchProgress,
        profileId: Int = profileManager.activeProfileId.value
    ) = markAsCompletedBatch(listOf(progress), profileId)

    /**
     * Mark multiple items as completed in one transaction.
     */
    suspend fun markAsCompletedBatch(
        progressList: List<WatchProgress>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (progressList.isEmpty()) return
        // If the incoming duration is a dummy sentinel (≤ 1ms), use the duration of an existing
        // local entry from prior playback, so the completed entry syncs correctly cross-device.
        val existing = write(profileId) { storedEntries(profileId, progressList.map(::createKey)).decoded(profileId) }
        val now = System.currentTimeMillis()
        val completed = progressList.map { progress ->
            val effectiveDuration = if (progress.duration <= 1L) {
                existing[createKey(progress)]?.duration?.takeIf { it > 1L } ?: progress.duration
            } else {
                progress.duration
            }
            progress.copy(
                position = effectiveDuration,
                duration = effectiveDuration,
                lastWatched = now
            )
        }
        saveProgressBatch(completed, profileId = profileId)
    }

    /**
     * Returns the raw key→WatchProgress map (for sync push).
     *
     * @param profileId Explicit profile to read from. Prevents race conditions
     *   when the active profile changes between scheduling and execution of a sync.
     */
    suspend fun getAllRawEntries(profileId: Int = profileManager.activeProfileId.value): Map<String, WatchProgress> {
        if (isDeleted(profileId)) return emptyMap()
        ensureMigrated(profileId)
        return decode(profileId, entries.all(profileId))
    }

    /**
     * Turns every stored entry into what [transform] returns from them, in one transaction,
     * writing only the entries that change. For the whole-history operations (a sync pull).
     */
    private suspend fun transformAll(
        profileId: Int,
        transform: (MutableMap<String, WatchProgress>) -> Unit
    ) {
        write(profileId) {
            entries.transaction {
                val stored = entries.all(profileId).associateBy { it.key }
                val local = LinkedHashMap(stored.decoded(profileId))
                transform(local)
                entries.writeChanges(profileId, KIND, stored, normalizeWatchProgressEntries(local).toEntries())
            }
        }
    }

    /**
     * Merges remote entries into local storage. Newer lastWatched wins per key.
     *
     * @param profileId Explicit profile to write to. Prevents race conditions
     *   when the active profile changes between pull and merge operations.
     */
    suspend fun mergeRemoteEntries(
        remoteEntries: Map<String, WatchProgress>,
        pendingUpsertKeys: Set<String> = emptySet(),
        pendingDeleteKeys: Set<String> = emptySet(),
        lastSuccessfulPushMs: Long? = null,
        profileId: Int = profileManager.activeProfileId.value,
        removeMissingRemoteEntries: Boolean = true,
        isNonTraktId: ((String) -> Boolean)? = null
    ): Boolean {
        var preservedLocalItems = false
        Log.d(TAG, "mergeRemoteEntries: ${remoteEntries.size} remote entries, profile=$profileId, removeMissing=$removeMissingRemoteEntries")
        transformAll(profileId) { local ->
            Log.d(TAG, "mergeRemoteEntries: ${local.size} existing local entries")
            if (removeMissingRemoteEntries) {
                val removedKeys = local.keys - remoteEntries.keys
                removedKeys.forEach { key ->
                    val localEntry = local[key]
                    if (localEntry != null && ServerItemRef.isServerId(localEntry.contentId)) {
                        return@forEach
                    }
                    if (localEntry != null && isNonTraktId != null && isNonTraktId(localEntry.contentId)) {
                        Log.d(TAG, "  preserved key=$key (non-Trakt ID: ${localEntry.contentId})")
                        preservedLocalItems = true
                    } else if (key in pendingUpsertKeys) {
                        Log.d(TAG, "  preserved pending key=$key")
                        preservedLocalItems = true
                    } else if (localEntry != null && lastSuccessfulPushMs != null && localEntry.lastWatched > lastSuccessfulPushMs) {
                        preservedLocalItems = true
                    } else {
                        local.remove(key)
                        Log.d(TAG, "  removed key=$key (not in remote)")
                    }
                }
            }

            for ((key, remote) in remoteEntries) {
                val existing = local[key]
                when {
                    key in pendingDeleteKeys -> local.remove(key)
                    key in pendingUpsertKeys && existing != null -> preservedLocalItems = true
                    existing != null &&
                        lastSuccessfulPushMs != null &&
                        remote.lastWatched <= existing.lastWatched -> {
                        if (existing.lastWatched > remote.lastWatched && existing.lastWatched > lastSuccessfulPushMs) {
                            preservedLocalItems = true
                        }
                    }
                    else -> local[key] = mergeDisplayMetadata(remote, existing)
                }
            }
            Log.d(TAG, "mergeRemoteEntries: ${local.size} entries after merge")
        }
        return preservedLocalItems
    }

    suspend fun applyRemoteChanges(
        upserts: Map<String, WatchProgress>,
        deletes: Collection<String>,
        pendingUpsertKeys: Set<String> = emptySet(),
        pendingDeleteKeys: Set<String> = emptySet(),
        lastSuccessfulPushMs: Long? = null,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean {
        if (upserts.isEmpty() && deletes.isEmpty()) {
            Log.d(TAG, "applyRemoteChanges: no changes for profile $profileId")
            return false
        }
        var preservedLocalItems = false
        var beforeCount = 0
        var afterCount = 0
        transformAll(profileId) { local ->
            beforeCount = local.size
            deletes.forEach { key ->
                if (key in pendingUpsertKeys) {
                    preservedLocalItems = true
                } else {
                    local.remove(key)
                }
            }

            upserts.forEach { (key, remote) ->
                val existing = local[key]
                when {
                    key in pendingDeleteKeys -> local.remove(key)
                    key in pendingUpsertKeys && existing != null -> preservedLocalItems = true
                    existing != null &&
                        lastSuccessfulPushMs != null &&
                        remote.lastWatched <= existing.lastWatched -> {
                        if (existing.lastWatched > remote.lastWatched && existing.lastWatched > lastSuccessfulPushMs) {
                            preservedLocalItems = true
                        }
                    }
                    else -> local[key] = mergeDisplayMetadata(remote, existing)
                }
            }
            afterCount = local.size
        }
        Log.d(TAG, "applyRemoteChanges: profile=$profileId before=$beforeCount after=$afterCount upserts=${upserts.size} deletes=${deletes.size} preservedLocal=$preservedLocalItems")
        return preservedLocalItems
    }

    suspend fun replaceWithRemoteEntries(
        remoteEntries: Map<String, WatchProgress>,
        pendingUpsertKeys: Set<String> = emptySet(),
        pendingDeleteKeys: Set<String> = emptySet(),
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean = mergeRemoteEntries(
        remoteEntries = remoteEntries,
        pendingUpsertKeys = pendingUpsertKeys,
        pendingDeleteKeys = pendingDeleteKeys,
        profileId = profileId,
        removeMissingRemoteEntries = true
    )

    /**
     * Clear all watch progress
     */
    suspend fun clearAll(profileId: Int = profileManager.activeProfileId.value) {
        write(profileId) { entries.clearKind(profileId, KIND) }
        clearSyncState(profileId)
    }

    /**
     * Clear all watch progress entries EXCEPT those with non-Trakt-compatible IDs
     */
    suspend fun clearAllPreservingNonTraktIds(
        profileId: Int = profileManager.activeProfileId.value,
        isNonTraktId: (String) -> Boolean
    ) {
        var preserved = 0
        transformAll(profileId) { local ->
            local.entries.removeAll { (_, progress) -> !isNonTraktId(progress.contentId) }
            preserved = local.size
        }
        if (preserved > 0) Log.d(TAG, "clearAllPreservingNonTraktIds: preserved $preserved non-Trakt entries")
        clearSyncState(profileId)
    }

    private suspend fun clearSyncState(profileId: Int) {
        metadataStore(profileId).edit { preferences ->
            preferences.remove(watchProgressEntriesKey)
            preferences.remove(deltaCursorKey)
            preferences.remove(deltaInitializedKey)
        }
    }

    private fun createKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private fun upsertProgressEntries(
        map: MutableMap<String, WatchProgress>,
        progressList: List<WatchProgress>
    ) {
        progressList.forEach { progress ->
            val key = createKey(progress)
            val existing = map[key]
            // Preserve display metadata (poster, backdrop, logo, name) from the existing
            // entry when the incoming save has null values — prevents a mid-playback
            // position update from wiping artwork that was saved on first play.
            map[key] = if (existing != null) {
                progress.copy(
                    name = progress.name.takeIf { it.isNotBlank() } ?: existing.name,
                    poster = progress.poster ?: existing.poster,
                    backdrop = progress.backdrop ?: existing.backdrop,
                    logo = progress.logo ?: existing.logo,
                    episodeTitle = progress.episodeTitle ?: existing.episodeTitle,
                )
            } else {
                progress
            }

            // Remove legacy series-level mirror key if this is an episode entry.
            // Mirror keys caused race conditions with stale progress data.
            if (progress.season != null && progress.episode != null) {
                val seriesKey = progress.contentId
                if (seriesKey != key && map.containsKey(seriesKey)) {
                    map.remove(seriesKey)
                }
            }
        }
    }

    private fun mergeDisplayMetadata(remote: WatchProgress, existing: WatchProgress?): WatchProgress {
        if (existing == null) return remote
        return remote.copy(
            name = existing.name.takeIf { it.isNotBlank() } ?: remote.name.takeIf { it.isNotBlank() } ?: existing.name,
            poster = existing.poster ?: remote.poster,
            backdrop = existing.backdrop ?: remote.backdrop,
            logo = existing.logo ?: remote.logo,
            episodeTitle = existing.episodeTitle ?: remote.episodeTitle,
            addonBaseUrl = remote.addonBaseUrl ?: existing.addonBaseUrl
        )
    }

    /** [entries] in the form this store writes them; [decodeEntries] reads them back. */
    fun encodeEntries(entries: Map<String, WatchProgress>): String = gson.toJson(entries)

    /** Entries [encodeEntries] wrote, skipping any that are malformed. */
    fun decodeEntries(json: String): Map<String, WatchProgress> = parseProgressMap(json)

    private fun parseProgressMap(json: String): Map<String, WatchProgress> {
        return try {
            // Parse entry-by-entry so one malformed value doesn't wipe the entire map.
            val root = gson.fromJson(json, JsonObject::class.java) ?: return emptyMap()
            val parsed = mutableMapOf<String, WatchProgress>()
            root.entrySet().forEach { (key, value) ->
                runCatching {
                    parseWatchProgressFromJson(value)
                }.onSuccess { watchProgress ->
                    if (watchProgress != null) parsed[key] = watchProgress
                }.onFailure {
                    Log.w(TAG, "Skipping malformed watch progress entry for key=$key")
                }
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse progress data", e)
            // Backward compatibility with previously stored direct WatchProgress payloads.
            runCatching {
                val fallbackType = object : TypeToken<Map<String, WatchProgress>>() {}.type
                gson.fromJson<Map<String, WatchProgress>>(json, fallbackType) ?: emptyMap()
            }.getOrElse { emptyMap() }
        }
    }

    private fun parseWatchProgressFromJson(value: JsonElement): WatchProgress? {
        val obj = when {
            value.isJsonObject -> value.asJsonObject
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                runCatching { gson.fromJson(value.asString, JsonObject::class.java) }.getOrNull()
            }
            else -> null
        } ?: return null
        val contentId = obj.getString("contentId", "content_id")?.takeIf { it.isNotBlank() } ?: return null
        val contentType = obj.getString("contentType", "content_type")?.takeIf { it.isNotBlank() } ?: return null
        val videoId = obj.getString("videoId", "video_id")?.takeIf { it.isNotBlank() } ?: contentId
        val lastWatched = obj.getLong("lastWatched", "last_watched") ?: return null

        return WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = obj.getString("name").orEmpty(),
            poster = obj.getString("poster"),
            backdrop = obj.getString("backdrop"),
            logo = obj.getString("logo"),
            videoId = videoId,
            season = obj.getInt("season"),
            episode = obj.getInt("episode"),
            episodeTitle = obj.getString("episodeTitle", "episode_title"),
            position = obj.getLong("position") ?: 0L,
            duration = obj.getLong("duration") ?: 0L,
            lastWatched = lastWatched,
            addonBaseUrl = obj.getString("addonBaseUrl", "addon_base_url"),
            progressPercent = obj.getFloat("progressPercent", "progress_percent"),
            source = obj.getString("source")?.takeIf { it.isNotBlank() } ?: WatchProgress.SOURCE_LOCAL,
            traktPlaybackId = obj.getLong("traktPlaybackId", "trakt_playback_id"),
            traktMovieId = obj.getInt("traktMovieId", "trakt_movie_id"),
            traktShowId = obj.getInt("traktShowId", "trakt_show_id"),
            traktEpisodeId = obj.getInt("traktEpisodeId", "trakt_episode_id")
        )
    }

    private fun JsonObject.getString(vararg keys: String): String? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            return runCatching { value.asString }.getOrNull()
        }
        return null
    }

    private fun JsonObject.getLong(vararg keys: String): Long? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asLong }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toLong() }.getOrNull()?.let { return it }
            runCatching { value.asString.toLong() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getInt(vararg keys: String): Int? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asInt }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toInt() }.getOrNull()?.let { return it }
            runCatching { value.asString.toInt() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getFloat(vararg keys: String): Float? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asFloat }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toFloat() }.getOrNull()?.let { return it }
            runCatching { value.asString.toFloat() }.getOrNull()?.let { return it }
        }
        return null
    }

}
