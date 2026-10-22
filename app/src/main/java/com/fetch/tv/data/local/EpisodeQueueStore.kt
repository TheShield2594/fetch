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

/** How an item got into the queue. */
enum class QueueItemOrigin {
    /** Put there by next-episode autoplay; replaced when autoplay moves on. */
    AUTOPLAY,
    /** Added by the person, for example from Continue Watching. */
    MANUAL
}

/** One title or episode waiting to be played. */
@Immutable
data class QueueItem(
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val name: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val origin: QueueItemOrigin = QueueItemOrigin.MANUAL,
    val addedAtMs: Long = 0L
)

/**
 * The per-profile episode queue. Stored as one JSON list in the profile's `episode_queue`
 * DataStore, which profile settings sync carries, so the queue follows the profile when
 * self-hosted sync is on.
 */
@Singleton
class EpisodeQueueStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        const val FEATURE = "episode_queue"
        private val ITEMS_KEY = stringPreferencesKey("items")
        internal const val MAX_ITEMS = 100
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<List<QueueItem>> = profileManager.activeProfileId
        .flatMapLatest { profileId -> store(profileId).data }
        .map { prefs -> decodeQueueItems(prefs[ITEMS_KEY]) }
        .distinctUntilChanged()

    /** Adds [item] at the end, unless that video is already queued. */
    suspend fun add(item: QueueItem) = update { queue -> queueWithAdded(queue, item.stamped()) }

    suspend fun remove(videoId: String) = update { queue -> queue.filterNot { it.videoId == videoId } }

    /** Moves the item one place up (towards playing sooner) or down. */
    suspend fun move(videoId: String, up: Boolean) = update { queue -> queueWithMoved(queue, videoId, up) }

    suspend fun clear() = update { emptyList() }

    /** Puts the episode autoplay would play next at the front, replacing its older pick. */
    suspend fun seedFromAutoplay(item: QueueItem) =
        update { queue -> queueWithAutoplaySeed(queue, item.copy(origin = QueueItemOrigin.AUTOPLAY).stamped()) }

    /** A queued video started playing, so it is no longer waiting. */
    suspend fun markStarted(videoId: String) = remove(videoId)

    private fun QueueItem.stamped() = if (addedAtMs > 0L) this else copy(addedAtMs = System.currentTimeMillis())

    private suspend fun update(transform: (List<QueueItem>) -> List<QueueItem>) {
        store().edit { prefs ->
            val current = decodeQueueItems(prefs[ITEMS_KEY])
            val next = transform(current).take(MAX_ITEMS)
            if (next == current) return@edit
            if (next.isEmpty()) prefs.remove(ITEMS_KEY) else prefs[ITEMS_KEY] = encodeQueueItems(next)
        }
    }
}

internal fun queueWithAdded(queue: List<QueueItem>, item: QueueItem): List<QueueItem> {
    val existing = queue.firstOrNull { it.videoId == item.videoId }
    return when {
        existing == null -> queue + item
        // Adding by hand keeps an autoplay pick in its place but stops autoplay replacing it.
        existing.origin == QueueItemOrigin.AUTOPLAY && item.origin == QueueItemOrigin.MANUAL ->
            queue.map { if (it.videoId == item.videoId) it.copy(origin = QueueItemOrigin.MANUAL) else it }
        else -> queue
    }
}

internal fun queueWithAutoplaySeed(queue: List<QueueItem>, item: QueueItem): List<QueueItem> {
    if (queue.any { it.videoId == item.videoId }) return queue
    val withoutStaleSeed = queue.filterNot {
        it.origin == QueueItemOrigin.AUTOPLAY && it.contentId == item.contentId
    }
    return listOf(item) + withoutStaleSeed
}

internal fun queueWithMoved(queue: List<QueueItem>, videoId: String, up: Boolean): List<QueueItem> {
    val index = queue.indexOfFirst { it.videoId == videoId }
    if (index < 0) return queue
    val target = if (up) index - 1 else index + 1
    if (target !in queue.indices) return queue
    return queue.toMutableList().apply { add(target, removeAt(index)) }
}

internal fun encodeQueueItems(items: List<QueueItem>): String = JSONArray().apply {
    items.forEach { item ->
        put(
            JSONObject().apply {
                put("contentId", item.contentId)
                put("contentType", item.contentType)
                put("videoId", item.videoId)
                put("name", item.name)
                item.season?.let { put("season", it) }
                item.episode?.let { put("episode", it) }
                item.episodeTitle?.let { put("episodeTitle", it) }
                item.poster?.let { put("poster", it) }
                item.backdrop?.let { put("backdrop", it) }
                item.logo?.let { put("logo", it) }
                put("origin", item.origin.name)
                put("addedAtMs", item.addedAtMs)
            }
        )
    }
}.toString()

internal fun decodeQueueItems(raw: String?): List<QueueItem> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val contentId = json.optString("contentId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val videoId = json.optString("videoId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            QueueItem(
                contentId = contentId,
                contentType = json.optString("contentType"),
                videoId = videoId,
                name = json.optString("name"),
                season = if (json.has("season")) json.optInt("season") else null,
                episode = if (json.has("episode")) json.optInt("episode") else null,
                episodeTitle = json.optString("episodeTitle").takeIf { it.isNotBlank() },
                poster = json.optString("poster").takeIf { it.isNotBlank() },
                backdrop = json.optString("backdrop").takeIf { it.isNotBlank() },
                logo = json.optString("logo").takeIf { it.isNotBlank() },
                origin = runCatching { QueueItemOrigin.valueOf(json.optString("origin")) }
                    .getOrDefault(QueueItemOrigin.MANUAL),
                addedAtMs = json.optLong("addedAtMs", 0L)
            )
        }.distinctBy { it.videoId }
    }.getOrDefault(emptyList())
}
