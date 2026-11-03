package com.fetch.desktop.playback

import com.fetch.desktop.data.JsonFile
import com.fetch.tv.domain.model.ProxyHeaders
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import kotlinx.serialization.Serializable
import java.io.File

/** The last link that played for one movie or episode ("Reuse Last Link"), as the TV caches it. */
@Serializable
data class CachedLink(
    val url: String,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val addonName: String = "",
    val filename: String? = null,
    val bingeGroup: String? = null,
    val videoSize: Long? = null,
    val infoHash: String? = null,
    val headers: Map<String, String>? = null,
    val savedAtMs: Long
) {
    /** The stream again, enough to play it and to show what it is. */
    fun toStream(): Stream = Stream(
        name = name,
        title = title,
        description = description,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = headers?.let { ProxyHeaders(request = it, response = null) },
            videoSize = videoSize,
            filename = filename
        ),
        addonName = addonName,
        addonLogo = null
    )

    companion object {
        /** Null for what can't be replayed later: your own server's files and links without an http address. */
        fun of(stream: Stream, nowMs: Long): CachedLink? {
            if (stream.serverTarget != null) return null
            val url = stream.getStreamUrl()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
            return CachedLink(
                url = url,
                name = stream.name,
                title = stream.title,
                description = stream.description,
                addonName = stream.addonName,
                filename = stream.behaviorHints?.filename,
                bingeGroup = stream.behaviorHints?.bingeGroup,
                videoSize = stream.behaviorHints?.videoSize,
                infoHash = stream.infoHash,
                headers = stream.behaviorHints?.proxyHeaders?.request,
                savedAtMs = nowMs
            )
        }
    }
}

@Serializable
data class StreamCacheFile(
    /** By video id (a movie's id, or an episode's `tt…:s:e`). */
    val links: Map<String, CachedLink> = emptyMap(),
    /** By title id: the binge group of the episode that played last. */
    val bingeGroups: Map<String, BingeHint> = emptyMap()
)

/**
 * Per profile `stream-cache.json`: the last link per video for Reuse Last Link, and the binge
 * group per show for Reuse Binge Group. At most [MAX_LINKS] links are kept, newest first.
 */
class StreamLinkCache(private val file: JsonFile<StreamCacheFile>, private val now: () -> Long = System::currentTimeMillis) {
    constructor(dir: File) : this(JsonFile(File(dir, "stream-cache.json"), StreamCacheFile.serializer()) { StreamCacheFile() })

    private var data: StreamCacheFile? = null
    private fun current(): StreamCacheFile = data ?: file.load().also { data = it }

    /** The link saved for [videoId] if it's younger than [maxAgeHours]. */
    fun validLink(videoId: String, maxAgeHours: Int): CachedLink? {
        val link = current().links[videoId] ?: return null
        val age = now() - link.savedAtMs
        return link.takeIf { age in 0..maxAgeHours * 3_600_000L }
    }

    fun saveLink(videoId: String, stream: Stream) {
        val link = CachedLink.of(stream, now()) ?: return
        val links = (mapOf(videoId to link) + (current().links - videoId)).entries
            .sortedByDescending { it.value.savedAtMs }.take(MAX_LINKS).associate { it.key to it.value }
        store(current().copy(links = links))
    }

    fun forgetLink(videoId: String) {
        if (videoId !in current().links) return
        store(current().copy(links = current().links - videoId))
    }

    fun bingeGroup(contentId: String): BingeHint? = current().bingeGroups[contentId]

    /** Replaces [contentId]'s binge group with [stream]'s, or forgets it when the stream has none (as on TV). */
    fun saveBingeGroup(contentId: String, stream: Stream) {
        val hint = BingeHint.of(stream)
        val groups = current().bingeGroups
        val updated = if (hint == null) groups - contentId else groups + (contentId to hint)
        if (updated != groups) store(current().copy(bingeGroups = updated))
    }

    private fun store(value: StreamCacheFile) {
        data = value
        runCatching { file.save(value) }
    }

    companion object {
        const val MAX_LINKS = 200
    }
}
