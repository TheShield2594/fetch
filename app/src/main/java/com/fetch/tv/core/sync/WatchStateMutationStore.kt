package com.fetch.tv.core.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.fetch.tv.data.local.ProfileDataStoreFactory
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.model.WatchedMutationKey
import com.fetch.tv.domain.model.mutationKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// Persisted with Gson. @Keep and @SerializedName pin the stored JSON keys: without them R8
// renamed the fields per build, so an app update could no longer read the queue.
@Keep
internal data class PendingProgressUpsert(
    @SerializedName("key") val key: String,
    @SerializedName("progress") val progress: WatchProgress
)

@Keep
internal data class PendingWatchedUpsert(
    @SerializedName("key") val key: WatchedMutationKey,
    @SerializedName("item") val item: WatchedItem
)

/**
 * Reads one stored queue entry, including those a minified build wrote under renamed keys.
 * [WatchProgress], [WatchedItem] and [WatchedMutationKey] always kept their own field names, so
 * an old entry is told apart by the shape of its two values.
 */
internal object PendingWatchStateDecoder {
    fun progressUpsert(gson: Gson, json: String): PendingProgressUpsert? = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        // Only an entry with no "key" at all is an old one; a damaged new one is dropped.
        val key = if (root.has("key")) {
            root.stringField("key")
        } else {
            root.entrySet().map { it.value }.singleOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
        val progressJson = root.objectField("progress")
            ?: root.entrySet().map { it.value }.singleOrNull { it.isJsonObject }?.asJsonObject
        if (key.isNullOrBlank() || progressJson == null) return@runCatching null
        val progress = gson.fromJson(progressJson, WatchProgress::class.java) ?: return@runCatching null
        @Suppress("SENSELESS_COMPARISON")
        if (progress.contentId == null) return@runCatching null
        PendingProgressUpsert(key, progress)
    }.getOrNull()

    fun watchedUpsert(gson: Gson, json: String): PendingWatchedUpsert? = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        // The watched item is the object carrying a watchedAt; its key is derived from it.
        val itemJson = root.objectField("item")
            ?: root.entrySet().map { it.value }
                .singleOrNull { it.isJsonObject && it.asJsonObject.has("watchedAt") }?.asJsonObject
            ?: return@runCatching null
        val item = gson.fromJson(itemJson, WatchedItem::class.java) ?: return@runCatching null
        @Suppress("SENSELESS_COMPARISON")
        if (item.contentId == null || item.contentType == null || item.title == null) return@runCatching null
        val key = root.objectField("key")?.let { gson.fromJson(it, WatchedMutationKey::class.java) }
            ?.takeIf {
                @Suppress("SENSELESS_COMPARISON")
                it.contentId != null
            }
            ?: item.mutationKey()
        PendingWatchedUpsert(key, item)
    }.getOrNull()

    private fun JsonObject.stringField(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.objectField(name: String): JsonObject? =
        get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
}

@Singleton
class WatchStateMutationStore @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        private const val FEATURE = "watch_state_pending_mutations"
    }

    private val gson = Gson()
    private val progressUpsertsKey = stringSetPreferencesKey("progress_upserts")
    private val progressDeletesKey = stringSetPreferencesKey("progress_deletes")
    private val watchedUpsertsKey = stringSetPreferencesKey("watched_upserts")
    private val watchedDeletesKey = stringSetPreferencesKey("watched_deletes")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    suspend fun queueProgressUpserts(
        entries: Map<String, WatchProgress>,
        profileId: Int
    ) {
        val entries = entries.filterValues { !ServerItemRef.isServerId(it.contentId) }
        if (entries.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseProgressUpserts(preferences[progressUpsertsKey]).toMutableMap()
            entries.forEach { (key, progress) ->
                pending[key] = progress
            }
            preferences[progressUpsertsKey] = pending.map { (key, progress) ->
                gson.toJson(PendingProgressUpsert(key, progress))
            }.toSet()
            preferences[progressDeletesKey] = preferences[progressDeletesKey].orEmpty() - entries.keys
        }
    }

    suspend fun queueProgressDeletes(keys: Collection<String>, profileId: Int) {
        val normalized = keys.map(String::trim).filter { it.isNotEmpty() && !ServerItemRef.isServerId(it) }.toSet()
        if (normalized.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseProgressUpserts(preferences[progressUpsertsKey]) - normalized
            preferences[progressUpsertsKey] = pending.map { (key, progress) ->
                gson.toJson(PendingProgressUpsert(key, progress))
            }.toSet()
            preferences[progressDeletesKey] = preferences[progressDeletesKey].orEmpty() + normalized
        }
    }

    suspend fun pendingProgressUpserts(profileId: Int): Map<String, WatchProgress> {
        val preferences = store(profileId).data.first()
        return parseProgressUpserts(preferences[progressUpsertsKey])
    }

    suspend fun pendingProgressDeletes(profileId: Int): Set<String> {
        return store(profileId).data.first()[progressDeletesKey].orEmpty()
    }

    suspend fun acknowledgeProgressUpserts(
        entries: Map<String, WatchProgress>,
        profileId: Int
    ) {
        if (entries.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseProgressUpserts(preferences[progressUpsertsKey]).toMutableMap()
            entries.forEach { (key, progress) ->
                if (pending[key] == progress) pending.remove(key)
            }
            preferences[progressUpsertsKey] = pending.map { (key, progress) ->
                gson.toJson(PendingProgressUpsert(key, progress))
            }.toSet()
        }
    }

    suspend fun acknowledgeProgressDeletes(keys: Collection<String>, profileId: Int) {
        if (keys.isEmpty()) return
        store(profileId).edit { preferences ->
            preferences[progressDeletesKey] = preferences[progressDeletesKey].orEmpty() - keys.toSet()
        }
    }

    suspend fun queueWatchedUpserts(items: Collection<WatchedItem>, profileId: Int) {
        val items = items.filterNot { ServerItemRef.isServerId(it.contentId) }
        if (items.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseWatchedUpserts(preferences[watchedUpsertsKey]).toMutableMap()
            items.forEach { item ->
                pending[item.mutationKey()] = item
            }
            preferences[watchedUpsertsKey] = pending.map { (key, item) ->
                gson.toJson(PendingWatchedUpsert(key, item))
            }.toSet()
            val upsertKeys = items.mapTo(mutableSetOf(), WatchedItem::mutationKey)
            preferences[watchedDeletesKey] = parseWatchedDeletes(preferences[watchedDeletesKey])
                .minus(upsertKeys)
                .map(gson::toJson)
                .toSet()
        }
    }

    suspend fun queueWatchedDeletes(keys: Collection<WatchedMutationKey>, profileId: Int) {
        val normalized = keys.filterNot { ServerItemRef.isServerId(it.contentId) }.toSet()
        if (normalized.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseWatchedUpserts(preferences[watchedUpsertsKey]) - normalized
            preferences[watchedUpsertsKey] = pending.map { (key, item) ->
                gson.toJson(PendingWatchedUpsert(key, item))
            }.toSet()
            preferences[watchedDeletesKey] = (parseWatchedDeletes(preferences[watchedDeletesKey]) + normalized)
                .map(gson::toJson)
                .toSet()
        }
    }

    suspend fun pendingWatchedUpserts(profileId: Int): Map<WatchedMutationKey, WatchedItem> {
        val preferences = store(profileId).data.first()
        return parseWatchedUpserts(preferences[watchedUpsertsKey])
    }

    suspend fun pendingWatchedDeletes(profileId: Int): Set<WatchedMutationKey> {
        return parseWatchedDeletes(store(profileId).data.first()[watchedDeletesKey])
    }

    suspend fun acknowledgeWatchedUpserts(items: Collection<WatchedItem>, profileId: Int) {
        if (items.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseWatchedUpserts(preferences[watchedUpsertsKey]).toMutableMap()
            items.forEach { item ->
                val key = item.mutationKey()
                if (pending[key] == item) pending.remove(key)
            }
            preferences[watchedUpsertsKey] = pending.map { (key, item) ->
                gson.toJson(PendingWatchedUpsert(key, item))
            }.toSet()
        }
    }

    suspend fun acknowledgeWatchedDeletes(
        keys: Collection<WatchedMutationKey>,
        profileId: Int
    ) {
        if (keys.isEmpty()) return
        store(profileId).edit { preferences ->
            preferences[watchedDeletesKey] = parseWatchedDeletes(preferences[watchedDeletesKey])
                .minus(keys.toSet())
                .map(gson::toJson)
                .toSet()
        }
    }

    suspend fun hasPendingProgressMutations(profileId: Int): Boolean {
        val preferences = store(profileId).data.first()
        return !preferences[progressUpsertsKey].isNullOrEmpty() ||
            !preferences[progressDeletesKey].isNullOrEmpty()
    }

    suspend fun hasPendingWatchedMutations(profileId: Int): Boolean {
        val preferences = store(profileId).data.first()
        return !preferences[watchedUpsertsKey].isNullOrEmpty() ||
            !preferences[watchedDeletesKey].isNullOrEmpty()
    }

    private fun parseProgressUpserts(raw: Set<String>?): Map<String, WatchProgress> {
        return raw.orEmpty().mapNotNull { json ->
            PendingWatchStateDecoder.progressUpsert(gson, json)
        }.associate { it.key to it.progress }
    }

    private fun parseWatchedUpserts(raw: Set<String>?): Map<WatchedMutationKey, WatchedItem> {
        return raw.orEmpty().mapNotNull { json ->
            PendingWatchStateDecoder.watchedUpsert(gson, json)
        }.associate { it.key to it.item }
    }

    private fun parseWatchedDeletes(raw: Set<String>?): Set<WatchedMutationKey> {
        return raw.orEmpty().mapNotNullTo(mutableSetOf()) { json ->
            runCatching { gson.fromJson(json, WatchedMutationKey::class.java) }.getOrNull()
        }
    }
}
