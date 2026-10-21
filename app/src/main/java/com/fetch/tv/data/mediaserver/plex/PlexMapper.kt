package com.fetch.tv.data.mediaserver.plex

import com.fetch.tv.core.tracking.TrackingExternalIds
import com.fetch.tv.core.tracking.parseTrackingExternalIds
import com.fetch.tv.data.mediaserver.ServerCandidate
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.data.mediaserver.ServerMediaKind
import com.fetch.tv.data.mediaserver.ServerPlaybackTarget
import com.fetch.tv.data.mediaserver.ServerTitle
import com.fetch.tv.data.mediaserver.ServerUserState
import com.fetch.tv.data.mediaserver.domainType
import com.fetch.tv.data.mediaserver.mediabrowser.buildUrl
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaCastMember
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.Video
import kotlin.math.roundToInt

internal class PlexMapper(
    private val baseUrl: String,
    private val token: String,
    private val connectionId: String
) {
    fun ref(ratingKey: String): String = ServerItemRef(connectionId, ratingKey).encode()

    fun preview(item: PlexMetadata): MetaPreview? {
        val kind = item.mediaKind() ?: return null
        return MetaPreview(
            id = ref(item.ratingKey),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.title.orEmpty(),
            poster = image(item.thumb, height = 600),
            posterShape = PosterShape.POSTER,
            background = image(item.art, width = 1920),
            logo = item.logo(),
            description = item.summary,
            releaseInfo = item.year?.toString(),
            imdbRating = item.score()?.formatRating(),
            genres = item.genres.mapNotNull { it.tag },
            released = item.originallyAvailableAt?.take(10)
        )
    }

    fun title(item: PlexMetadata): ServerTitle? = preview(item)?.let { ServerTitle(it, item.externalIds()) }

    /** Continue Watching shows the series for an episode, like the Jellyfin and Emby rows do. */
    fun resumeTitle(item: PlexMetadata): ServerTitle? {
        if (item.type != "episode") return title(item)
        val showKey = item.grandparentRatingKey ?: return null
        val preview = MetaPreview(
            id = ref(showKey),
            type = ContentType.SERIES,
            rawType = ServerMediaKind.SERIES.contentType,
            name = item.grandparentTitle ?: item.title.orEmpty(),
            poster = image(item.grandparentThumb, height = 600),
            posterShape = PosterShape.POSTER,
            background = image(item.grandparentArt ?: item.art, width = 1920),
            logo = null,
            description = item.summary,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
        return ServerTitle(preview)
    }

    fun details(item: PlexMetadata, episodes: List<PlexMetadata>): Meta {
        val kind = item.mediaKind() ?: ServerMediaKind.MOVIE
        val cast = item.roles.filter { !it.tag.isNullOrBlank() }
        return Meta(
            id = ref(item.ratingKey),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.title.orEmpty(),
            poster = image(item.thumb, height = 600),
            posterShape = PosterShape.POSTER,
            background = image(item.art, width = 1920),
            logo = item.logo(),
            description = item.summary,
            releaseInfo = item.year?.toString(),
            status = null,
            imdbRating = item.score()?.formatRating(),
            genres = item.genres.mapNotNull { it.tag },
            runtime = item.duration?.takeIf { kind == ServerMediaKind.MOVIE }?.let { "${msToMinutes(it)} min" },
            director = item.directors.mapNotNull { it.tag },
            writer = item.writers.mapNotNull { it.tag },
            cast = cast.mapNotNull { it.tag },
            castMembers = cast.map { role ->
                MetaCastMember(name = role.tag.orEmpty(), character = role.role, photo = image(role.thumb, height = 300))
            },
            videos = episodes.filter { it.type == "episode" }.map(::video),
            ageRating = item.contentRating,
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = item.externalIds().imdb,
            released = item.originallyAvailableAt?.take(10)
        )
    }

    fun userState(item: PlexMetadata): ServerUserState = ServerUserState(
        videoId = ref(item.ratingKey),
        positionMs = item.viewOffset ?: 0L,
        durationMs = item.duration ?: 0L,
        played = (item.viewCount ?: 0) > 0,
        lastPlayedEpochMs = item.lastViewedAt?.times(1_000L),
        season = item.parentIndex.takeIf { item.type == "episode" },
        episode = item.index.takeIf { item.type == "episode" },
        title = item.title
    )

    fun video(item: PlexMetadata): Video = Video(
        id = ref(item.ratingKey),
        title = item.title.orEmpty(),
        released = item.originallyAvailableAt,
        thumbnail = image(item.thumb, width = 640),
        season = item.parentIndex,
        episode = item.index,
        overview = item.summary,
        runtime = item.duration?.let(::msToMinutes),
        rating = item.score(),
        available = item.media.isNotEmpty()
    )

    fun candidates(item: PlexMetadata): List<ServerCandidate> {
        val itemRef = ServerItemRef(connectionId, item.ratingKey)
        return item.media.filter { it.parts.isNotEmpty() }.map { media ->
            val streams = media.parts.flatMap { it.streams }
            val video = streams.firstOrNull { it.isVideo }
            val audio = streams.firstOrNull { it.isAudio && it.selected } ?: streams.firstOrNull { it.isAudio }
            ServerCandidate(
                target = ServerPlaybackTarget(item = itemRef, mediaSourceId = media.id.toString()),
                title = media.resolutionLabel() ?: media.container?.uppercase().orEmpty(),
                description = listOfNotNull(
                    video?.displayTitle ?: media.videoCodec?.uppercase(),
                    audio?.displayTitle ?: media.audioCodec?.uppercase(),
                    media.container?.uppercase()
                ).joinToString(" • ").ifBlank { null },
                filename = media.parts.first().file?.substringAfterLast('/')?.substringAfterLast('\\'),
                sizeBytes = media.parts.mapNotNull { it.size }.takeIf { it.isNotEmpty() }?.sum()
            )
        }
    }

    /**
     * A copy of a server image scaled to fit the box, through the photo transcoder. Absolute URLs (cast photos
     * served from plex.tv) are used as they are.
     */
    fun image(path: String?, width: Int? = null, height: Int? = null): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return buildUrl(
            baseUrl,
            "/photo/:/transcode",
            mapOf(
                "url" to path,
                "width" to (width ?: height?.times(2)?.div(3))?.toString(),
                "height" to (height ?: width?.times(9)?.div(16))?.toString(),
                "upscale" to "0",
                "X-Plex-Token" to token
            )
        )
    }

    private fun PlexMetadata.logo(): String? =
        images.firstOrNull { it.type == "clearLogo" }?.url?.let { image(it, width = 800) }
}

internal fun PlexMetadata.mediaKind(): ServerMediaKind? = when (type) {
    "movie" -> ServerMediaKind.MOVIE
    "show" -> ServerMediaKind.SERIES
    "collection" -> ServerMediaKind.COLLECTION
    else -> null
}

internal fun PlexMetadata.externalIds(): TrackingExternalIds {
    val ids = (guids.map { it.id } + listOfNotNull(guid)).fold(TrackingExternalIds()) { ids, value ->
        ids.mergeMissing(plexGuidIds(value))
    }
    return ids.copy(imdb = ids.imdb?.takeIf { it.startsWith("tt") })
}

/**
 * IDs from one Plex GUID. The current agents use `imdb://tt0133093`, `tmdb://603` and
 * `tvdb://81189`; libraries still on the legacy agents use
 * `com.plexapp.agents.imdb://tt0133093?lang=en`, `com.plexapp.agents.thetvdb://81189/1/2?lang=en`
 * and the HAMA anime agent's `com.plexapp.agents.hama://anidb-123`. `plex://` GUIDs are Plex's
 * own and match nothing.
 */
internal fun plexGuidIds(guid: String): TrackingExternalIds {
    val scheme = guid.substringBefore("://", "").substringAfterLast('.').lowercase()
    var value = guid.substringAfter("://", "").substringBefore('?').substringBefore('/').trim()
    if (value.isEmpty()) return TrackingExternalIds()
    val namespace = when (scheme) {
        "imdb" -> "imdb"
        "tmdb", "themoviedb" -> "tmdb"
        "tvdb", "thetvdb" -> "tvdb"
        "hama" -> {
            val prefix = value.substringBefore('-', "")
            value = value.substringAfter('-', "")
            when (prefix) {
                "anidb", "tvdb", "tmdb", "imdb" -> prefix
                else -> return TrackingExternalIds()
            }
        }
        else -> return TrackingExternalIds()
    }
    if (value.isEmpty()) return TrackingExternalIds()
    return parseTrackingExternalIds("$namespace:$value")
}

internal fun libraryKind(type: String?): ServerMediaKind? = when (type) {
    "movie" -> ServerMediaKind.MOVIE
    "show" -> ServerMediaKind.SERIES
    else -> null
}

/** Plex's numeric type for a library listing: 1 movie, 2 show, 18 collection. */
internal fun ServerMediaKind.plexType(): String? = when (this) {
    ServerMediaKind.MOVIE -> "1"
    ServerMediaKind.SERIES -> "2"
    ServerMediaKind.COLLECTION -> "18"
    ServerMediaKind.MIXED -> null
}

private fun PlexMetadata.score(): Double? = audienceRating ?: rating

private fun msToMinutes(ms: Long): Int = (ms / 60_000L).toInt()

private fun Double.formatRating(): Float = ((this * 10).roundToInt() / 10.0).toFloat()

private fun PlexMedia.resolutionLabel(): String? = when (videoResolution?.lowercase()) {
    "4k", "2160" -> "4K"
    "1080" -> "1080p"
    "720" -> "720p"
    "480", "576", "sd" -> height?.let { "${it}p" } ?: "SD"
    null, "" -> height?.let { "${it}p" }
    else -> videoResolution
}
