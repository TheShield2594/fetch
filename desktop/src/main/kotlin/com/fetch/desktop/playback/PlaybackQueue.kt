package com.fetch.desktop.playback

import com.fetch.desktop.data.episodeTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** How an item got into the queue (the TV's `QueueItemOrigin`). */
@Serializable
enum class QueueItemOrigin {
    /** Put there by next-episode autoplay; replaced when autoplay moves on. */
    AUTOPLAY,
    /** Added by hand. */
    MANUAL
}

/**
 * One title or episode waiting to play: the TV's `QueueItem`, in the same JSON so the queue
 * syncs between them (`episode_queue.items` in the settings blob).
 */
@Serializable
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
) {
    /** "S2 E5 · The Title" for an episode, null for a movie. */
    val subtitle: String?
        get() = if (season == null && episode == null) null
        else listOfNotNull(episodeTag(season, episode), episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }
}

/** The TV's queue rules (`EpisodeQueueStore`), as pure functions over the list. */
object PlaybackQueue {
    const val MAX_ITEMS = 100

    /** Adds [item] at the end unless that video is queued; adding by hand keeps an autoplay pick but stops it being replaced. */
    fun added(queue: List<QueueItem>, item: QueueItem): List<QueueItem> {
        val existing = queue.firstOrNull { it.videoId == item.videoId }
        return when {
            existing == null -> (queue + item).take(MAX_ITEMS)
            existing.origin == QueueItemOrigin.AUTOPLAY && item.origin == QueueItemOrigin.MANUAL ->
                queue.map { if (it.videoId == item.videoId) it.copy(origin = QueueItemOrigin.MANUAL) else it }
            else -> queue
        }
    }

    fun removed(queue: List<QueueItem>, videoId: String): List<QueueItem> = queue.filterNot { it.videoId == videoId }

    /** One place up (sooner) or down. */
    fun moved(queue: List<QueueItem>, videoId: String, up: Boolean): List<QueueItem> {
        val index = queue.indexOfFirst { it.videoId == videoId }
        if (index < 0) return queue
        val target = if (up) index - 1 else index + 1
        if (target !in queue.indices) return queue
        return queue.toMutableList().apply { add(target, removeAt(index)) }
    }

    /** Undo: [item] back where it was. */
    fun restored(queue: List<QueueItem>, item: QueueItem, index: Int): List<QueueItem> {
        if (queue.any { it.videoId == item.videoId }) return queue
        return queue.toMutableList().apply { add(index.coerceIn(0, size), item) }.take(MAX_ITEMS)
    }

    /** The episode autoplay will play next goes first, replacing its older pick for the same show. */
    fun seeded(queue: List<QueueItem>, item: QueueItem): List<QueueItem> {
        if (queue.any { it.videoId == item.videoId }) return queue
        val seed = item.copy(origin = QueueItemOrigin.AUTOPLAY)
        return (listOf(seed) + queue.filterNot { it.origin == QueueItemOrigin.AUTOPLAY && it.contentId == item.contentId }).take(MAX_ITEMS)
    }

    /** What plays after the video on now. */
    fun upcoming(queue: List<QueueItem>, currentVideoId: String?): List<QueueItem> = queue.filterNot { it.videoId == currentVideoId }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val listSerializer = ListSerializer(QueueItem.serializer())

    /** The TV's stored form: one JSON array. */
    fun encode(items: List<QueueItem>): String = json.encodeToString(listSerializer, items)

    /** Reads the TV's JSON; entries without an id are dropped, and each video appears once. */
    fun decode(raw: String?): List<QueueItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching { json.decodeFromString(ListSerializer(LenientItem.serializer()), raw) }.getOrNull() ?: return emptyList()
        return parsed.mapNotNull { it.toItem() }.distinctBy { it.videoId }
    }

    /** Every field optional, so one odd entry doesn't lose the rest. */
    @Serializable
    private data class LenientItem(
        val contentId: String? = null,
        val contentType: String? = null,
        val videoId: String? = null,
        val name: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val episodeTitle: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        val origin: String? = null,
        val addedAtMs: Long? = null
    ) {
        fun toItem(): QueueItem? {
            val id = contentId?.takeIf { it.isNotBlank() } ?: return null
            val video = videoId?.takeIf { it.isNotBlank() } ?: return null
            return QueueItem(
                contentId = id,
                contentType = contentType.orEmpty(),
                videoId = video,
                name = name.orEmpty(),
                season = season,
                episode = episode,
                episodeTitle = episodeTitle?.takeIf { it.isNotBlank() },
                poster = poster?.takeIf { it.isNotBlank() },
                backdrop = backdrop?.takeIf { it.isNotBlank() },
                logo = logo?.takeIf { it.isNotBlank() },
                origin = QueueItemOrigin.entries.firstOrNull { it.name == origin } ?: QueueItemOrigin.MANUAL,
                addedAtMs = addedAtMs ?: 0L
            )
        }
    }
}
