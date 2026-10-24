package com.fetch.tv.core.streams

import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.Video

internal fun Addon.supportsStreamResource(type: String, videoId: String): Boolean =
    supportsResource("stream", type, videoId)

internal fun Addon.supportsResource(name: String, type: String, id: String): Boolean =
    resources.any { resource ->
        resource.name == name &&
            (resource.types.isEmpty() || resource.types.contains(type)) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { id.startsWith(it) }
            }
    }

internal data class PlaybackAvailability(
    val addons: List<Addon> = emptyList(),
    val isLoaded: Boolean = false,
    private val cachedMeta: (String, String) -> Meta? = { _, _ -> null },
    private val serverStreams: (String, String) -> Boolean = { _, _ -> false }
) {
    fun canStream(
        type: String,
        videoId: String,
        contentId: String = videoId,
        video: Video? = null
    ): Boolean = video?.takeIf { it.id == videoId }?.streams?.isNotEmpty() == true ||
        cachedMeta(type, contentId)?.videos?.any { it.id == videoId && it.streams.isNotEmpty() } == true ||
        addons.any { it.enabled && it.supportsStreamResource(type, videoId) } ||
        serverStreams(type, videoId) ||
        canFetchEmbeddedStreams(type, videoId, contentId)

    // The stream screen also looks for streams inside the parent's metadata (see
    // StreamScreenViewModel.shouldAttemptEmbeddedMetaStreamLookup). That metadata is not cached
    // after a restart, e.g. for a Continue Watching item, so trust an add-on that serves it.
    private fun canFetchEmbeddedStreams(type: String, videoId: String, contentId: String): Boolean {
        if (type.isBlank() || contentId.isBlank()) return false
        val lookupApplies = type.equals("other", ignoreCase = true) ||
            !contentId.equals(videoId.substringBefore(":"), ignoreCase = true)
        return lookupApplies && addons.any { it.enabled && it.supportsResource("meta", type, contentId) }
    }
}
