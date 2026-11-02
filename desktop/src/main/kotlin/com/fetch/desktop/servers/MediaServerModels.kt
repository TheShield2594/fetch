package com.fetch.desktop.servers

import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.data.mediaserver.ServerPlaybackTarget
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import kotlinx.serialization.Serializable
import java.util.Locale

/** The kinds of media server Fetch can sign in to. */
@Serializable
enum class ServerKind(val label: String) { JELLYFIN("Jellyfin"), EMBY("Emby"), PLEX("Plex") }

/** A movie or TV library on a server; only [selected] ones are searched and shown. */
@Serializable
data class ServerLib(
    val id: String,
    val name: String,
    /** "movie" or "series". */
    val type: String,
    val selected: Boolean = true
)

/** One signed-in server user. [token] is a secret and never appears in [toString]. */
@Serializable
data class ServerAccount(
    /** A random UUID: the stable local id. */
    val id: String,
    val kind: ServerKind,
    /** The server's name. */
    val name: String,
    /** Base URL without a trailing slash (and without Emby's `/emby` prefix). */
    val address: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val token: String,
    val libraries: List<ServerLib> = emptyList(),
    val enabled: Boolean = true
) {
    override fun toString(): String =
        "ServerAccount(id=$id, kind=$kind, name=$name, address=$address, userName=$userName, libraries=${libraries.size}, enabled=$enabled)"
}

/** One version of a title on a server, playable directly by mpv. */
data class ServerStream(
    val accountId: String,
    val itemId: String,
    val mediaSourceId: String?,
    /** Direct-play URL with the token included. */
    val url: String,
    val fileName: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val audioCodec: String?,
    val audioChannels: Int?,
    val bitrate: Long?,
    val durationMs: Long?,
    val resumePositionMs: Long?,
    /** The server's external subtitle files for this version (embedded tracks are in the file itself). */
    val subtitles: List<ServerSubtitle> = emptyList()
) {
    override fun toString(): String = "ServerStream(accountId=$accountId, itemId=$itemId, mediaSourceId=$mediaSourceId)"
}

/** An external subtitle file of a [ServerStream]; [url] includes the token. */
data class ServerSubtitle(
    val url: String,
    /** The language code as the server gives it ("eng", "en"), when known. */
    val language: String?,
    val title: String?,
    val forced: Boolean,
    val default: Boolean
) {
    override fun toString(): String = "ServerSubtitle(language=$language, title=$title, forced=$forced, default=$default)"
}

/** One page of a library listing; [total] is the library's item count as the server reports it. */
data class ServerPageResult(val items: List<MetaPreview>, val total: Int)

/** Orders a library listing. */
enum class ServerSort(val label: String) {
    ADDED("Recently added"),
    TITLE("Title A–Z"),
    YEAR("Newest"),
}

/** A server's continue-watching entry, keyed by IMDb id (the series id for an episode). */
data class ServerResume(
    val imdbId: String,
    /** "movie" or "series". */
    val type: String,
    val name: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedEpochMs: Long,
    val poster: String?,
    val backdrop: String?
)

/** A server request failed; [status] is the HTTP status when there was one. */
open class ServerException(message: String, val status: Int? = null) : Exception(message)

/** The server rejected the credentials or token (HTTP 401): sign in again. */
class ServerAuthException(message: String) : ServerException(message, 401)

/**
 * This stream as an addon-style [Stream] labelled [label]. The title line spells out resolution,
 * codecs, channels and size in the tokens StreamTitleParser understands ("2160p HEVC TrueHD 7.1").
 */
fun ServerStream.toStream(label: String): Stream {
    val quality = listOfNotNull(
        resolutionLabel(width, height),
        videoCodecLabel(videoCodec),
        audioCodecLabel(audioCodec),
        channelsLabel(audioChannels)
    ).joinToString(" ")
    val title = listOfNotNull(quality.ifBlank { null }, sizeBytes?.takeIf { it > 0 }?.let(::sizeLabel))
        .joinToString(" • ")
        .ifBlank { null }
    return Stream(
        name = label,
        title = title,
        description = fileName,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = sizeBytes?.takeIf { it > 0 },
            filename = fileName
        ),
        addonName = label,
        addonLogo = null,
        serverTarget = ServerPlaybackTarget(ServerItemRef(accountId, itemId), mediaSourceId)
    )
}

internal fun resolutionLabel(width: Int?, height: Int?): String? {
    val w = width ?: 0
    val h = height ?: 0
    return when {
        w >= 3200 || h >= 2000 -> "2160p"
        w >= 2200 || h >= 1300 -> "1440p"
        w >= 1800 || h >= 1000 -> "1080p"
        w >= 1200 || h >= 700 -> "720p"
        h > 0 -> "${h}p"
        else -> null
    }
}

private fun videoCodecLabel(codec: String?): String? = when (val c = codec?.lowercase()?.trim()) {
    null, "" -> null
    "hevc", "h265", "x265" -> "HEVC"
    "h264", "avc", "x264" -> "H264"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "vc1" -> "VC1"
    "mpeg2video", "mpeg2" -> "MPEG2"
    else -> c.uppercase(Locale.ROOT)
}

private fun audioCodecLabel(codec: String?): String? = when (val c = codec?.lowercase()?.trim()) {
    null, "" -> null
    "truehd" -> "TrueHD"
    "eac3" -> "EAC3"
    "ac3" -> "AC3"
    "dca", "dts" -> "DTS"
    "dts-hd ma", "dtshd" -> "DTS-HD MA"
    "aac" -> "AAC"
    "flac" -> "FLAC"
    "opus" -> "Opus"
    "mp3" -> "MP3"
    else -> c.uppercase(Locale.ROOT)
}

private fun channelsLabel(channels: Int?): String? = when (channels) {
    null, 0 -> null
    1 -> "1.0"
    2 -> "2.0"
    3 -> "2.1"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "${channels}ch"
}

private fun sizeLabel(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1) String.format(Locale.ROOT, "%.1f GB", gb) else String.format(Locale.ROOT, "%.0f MB", bytes / 1_048_576.0)
}
