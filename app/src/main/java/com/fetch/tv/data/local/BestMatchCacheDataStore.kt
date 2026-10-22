package com.fetch.tv.data.local

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** The last stream Best match picked (or that played) for one video, as the Home hero shows it. */
@Immutable
data class CachedBestMatch(
    /** Picture, HDR, sound, size, most important first ("4K", "DV", "Atmos"). */
    val labels: List<String>,
    val isCached: Boolean,
    val savedAtMs: Long
)

/**
 * Remembers the last Best match per video so Home can show it without asking any addon.
 * Written after a detail-page resolve and when a stream starts playing; Home only reads it.
 */
@Singleton
class BestMatchCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "best_match_cache"
        private val ENTRIES_KEY = stringPreferencesKey("entries")
        internal const val MAX_ENTRIES = 200

        /** Same key the stream link cache uses: lowercased type and the video id. */
        fun contentKey(type: String, videoId: String): String = "${type.lowercase()}|$videoId"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    /** Every cached pick for the active profile, keyed by [contentKey]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: Flow<Map<String, CachedBestMatch>> = profileManager.activeProfileId
        .flatMapLatest { profileId -> store(profileId).data }
        .map { prefs -> decodeBestMatchEntries(prefs[ENTRIES_KEY]) }
        .distinctUntilChanged()

    suspend fun save(type: String, videoId: String, labels: List<String>, isCached: Boolean) {
        if (type.isBlank() || videoId.isBlank()) return
        if (labels.isEmpty() && !isCached) return
        val key = contentKey(type, videoId)
        store().edit { prefs ->
            val current = decodeBestMatchEntries(prefs[ENTRIES_KEY])
            val entry = CachedBestMatch(labels = labels, isCached = isCached, savedAtMs = System.currentTimeMillis())
            if (current[key]?.let { it.labels == labels && it.isCached == isCached } == true) return@edit
            prefs[ENTRIES_KEY] = encodeBestMatchEntries(trimBestMatchEntries(current + (key to entry)))
        }
    }
}

internal fun trimBestMatchEntries(
    entries: Map<String, CachedBestMatch>,
    max: Int = BestMatchCacheDataStore.MAX_ENTRIES
): Map<String, CachedBestMatch> {
    if (entries.size <= max) return entries
    return entries.entries
        .sortedByDescending { it.value.savedAtMs }
        .take(max)
        .associate { it.key to it.value }
}

internal fun encodeBestMatchEntries(entries: Map<String, CachedBestMatch>): String {
    val root = JSONObject()
    entries.forEach { (key, entry) ->
        root.put(
            key,
            JSONObject().apply {
                put("labels", JSONArray(entry.labels))
                put("cached", entry.isCached)
                put("at", entry.savedAtMs)
            }
        )
    }
    return root.toString()
}

internal fun decodeBestMatchEntries(raw: String?): Map<String, CachedBestMatch> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        val root = JSONObject(raw)
        buildMap {
            root.keys().forEach { key ->
                val json = root.optJSONObject(key) ?: return@forEach
                val labelsJson = json.optJSONArray("labels")
                val labels = (0 until (labelsJson?.length() ?: 0))
                    .mapNotNull { index -> labelsJson?.optString(index)?.takeIf { it.isNotBlank() } }
                put(
                    key,
                    CachedBestMatch(
                        labels = labels,
                        isCached = json.optBoolean("cached", false),
                        savedAtMs = json.optLong("at", 0L)
                    )
                )
            }
        }
    }.getOrDefault(emptyMap())
}
