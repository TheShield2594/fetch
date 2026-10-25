package com.fetch.tv.data.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads YouTube's InnerTube responses. The TV client nests its tiles differently on every page
 * and YouTube moves them around often, so this walks the whole tree and keeps any video it can
 * recognise, grouped by the shelf it sits in, instead of following one fixed path.
 */
internal object YouTubeResponseParser {

    private const val MAX_DEPTH = 64

    fun parseShelves(root: JsonElement): List<YouTubeShelf> {
        val shelves = mutableListOf<YouTubeShelf>()
        val loose = mutableListOf<YouTubeVideo>()
        collect(root, shelves, loose, depth = 0)
        val result = buildList {
            if (loose.isNotEmpty()) add(YouTubeShelf(title = null, videos = loose.distinctBy { it.videoId }))
            addAll(shelves)
        }
        return result.filter { it.videos.isNotEmpty() }
    }

    fun parseAccount(root: JsonElement): YouTubeAccount? {
        val accounts = mutableListOf<JsonObject>()
        findObjectsWithKey(root, "accountName", accounts, depth = 0)
        val account = accounts.firstOrNull { it["isSelected"].booleanOrNull() == true } ?: accounts.firstOrNull()
            ?: return null
        return YouTubeAccount(
            name = text(account["accountName"]),
            handle = text(account["channelHandle"]),
            photoUrl = bestThumbnail(account["accountPhoto"])
        )
    }

    private fun collect(
        element: JsonElement?,
        shelves: MutableList<YouTubeShelf>,
        loose: MutableList<YouTubeVideo>,
        depth: Int
    ) {
        if (element == null || depth > MAX_DEPTH) return
        when (element) {
            is JsonArray -> element.forEach { collect(it, shelves, loose, depth + 1) }
            is JsonObject -> {
                for ((key, value) in element) {
                    if (value !is JsonObject) {
                        collect(value, shelves, loose, depth + 1)
                        continue
                    }
                    when (key) {
                        "shelfRenderer", "richShelfRenderer", "reelShelfRenderer" -> {
                            val videos = mutableListOf<YouTubeVideo>()
                            collectVideos(value, videos, depth + 1)
                            if (videos.isNotEmpty()) {
                                shelves += YouTubeShelf(
                                    title = shelfTitle(value),
                                    videos = videos.distinctBy { it.videoId }
                                )
                            }
                        }
                        else -> parseVideo(key, value)?.let { loose += it }
                            ?: collect(value, shelves, loose, depth + 1)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun collectVideos(element: JsonElement?, out: MutableList<YouTubeVideo>, depth: Int) {
        if (element == null || depth > MAX_DEPTH) return
        when (element) {
            is JsonArray -> element.forEach { collectVideos(it, out, depth + 1) }
            is JsonObject -> for ((key, value) in element) {
                val video = if (value is JsonObject) parseVideo(key, value) else null
                if (video != null) out += video else collectVideos(value, out, depth + 1)
            }
            else -> Unit
        }
    }

    private fun parseVideo(key: String, renderer: JsonObject): YouTubeVideo? = when (key) {
        "tileRenderer" -> parseTile(renderer)
        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" ->
            parseWebVideo(renderer)
        "lockupViewModel" -> parseLockup(renderer)
        else -> null
    }

    /**
     * YouTube's newer item layout, which it has been moving feeds to (SmartTube's LockupItem in
     * common/models/gen/CommonItems.kt). Playlists and channels are lockups too; they carry no video id.
     */
    private fun parseLockup(lockup: JsonObject): YouTubeVideo? {
        val contentType = lockup["contentType"].stringOrNull()
        if (contentType != null && contentType != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val command = lockup.path("rendererContext", "commandContext", "onTap", "innertubeCommand")
        val videoId = command.path("watchEndpoint", "videoId").stringOrNull() ?: return null
        val metadata = lockup.path("metadata", "lockupMetadataViewModel") as? JsonObject
        val title = text(metadata?.get("title")) ?: return null
        val rows = (metadata.path("metadata", "contentMetadataViewModel", "metadataRows") as? JsonArray).orEmpty()
            .mapNotNull { row ->
                (row.path("metadataParts") as? JsonArray)?.mapNotNull { text(it.path("text")) }
                    ?.joinToString(" • ")
                    ?.takeIf { it.isNotBlank() }
            }
        val thumbnail = lockup.path("contentImage", "thumbnailViewModel")
            ?: lockup.path("contentImage", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel")
        val overlays = (thumbnail.path("overlays") as? JsonArray).orEmpty()
        val badges = overlays.flatMap { overlay ->
            ((overlay.path("thumbnailOverlayBadgeViewModel", "thumbnailBadges") as? JsonArray).orEmpty() +
                (overlay.path("thumbnailBottomOverlayViewModel", "badges") as? JsonArray).orEmpty())
        }
        return YouTubeVideo(
            videoId = videoId,
            title = title,
            channelName = rows.getOrNull(0),
            detail = rows.getOrNull(1),
            thumbnailUrl = bestThumbnail(thumbnail.path("image")) ?: defaultThumbnail(videoId),
            duration = badges.firstNotNullOfOrNull { text(it.path("thumbnailBadgeViewModel", "text")) },
            watchedPercent = overlays.firstNotNullOfOrNull {
                it.path("thumbnailBottomOverlayViewModel", "progressBar", "thumbnailOverlayProgressBarViewModel", "startPercent")
                    .intOrNull()
            }
        )
    }

    /** The TV client's tile. Channels and playlists are tiles too; only videos are kept. */
    private fun parseTile(tile: JsonObject): YouTubeVideo? {
        val contentType = tile["contentType"].stringOrNull()
        if (contentType != null && contentType != "TILE_CONTENT_TYPE_VIDEO") return null
        val videoId = tile.path("onSelectCommand", "watchEndpoint", "videoId").stringOrNull()
            ?: return null
        val metadata = tile.path("metadata", "tileMetadataRenderer") as? JsonObject
        val title = text(metadata?.get("title")) ?: return null
        val lines = (metadata?.get("lines") as? JsonArray).orEmpty().mapNotNull { line ->
            val items = line.path("lineRenderer", "items") as? JsonArray
            items?.mapNotNull { text(it.path("lineItemRenderer", "text")) }
                ?.filter { it.isNotBlank() && it != "•" }
                ?.joinToString(" • ")
                ?.takeIf { it.isNotBlank() }
        }
        val header = tile.path("header", "tileHeaderRenderer") as? JsonObject
        val overlays = (header?.get("thumbnailOverlays") as? JsonArray).orEmpty()
        return YouTubeVideo(
            videoId = videoId,
            title = title,
            channelName = lines.getOrNull(0),
            detail = lines.getOrNull(1),
            thumbnailUrl = bestThumbnail(header?.get("thumbnail")) ?: defaultThumbnail(videoId),
            duration = overlays.firstNotNullOfOrNull {
                text(it.path("thumbnailOverlayTimeStatusRenderer", "text"))
            },
            watchedPercent = overlays.firstNotNullOfOrNull {
                it.path("thumbnailOverlayResumePlaybackRenderer", "percentDurationWatched").intOrNull()
            }
        )
    }

    /** The web clients' video renderers, which some TV pages still embed. */
    private fun parseWebVideo(renderer: JsonObject): YouTubeVideo? {
        val videoId = renderer["videoId"].stringOrNull() ?: return null
        val title = text(renderer["title"]) ?: text(renderer["headline"]) ?: return null
        val channel = text(renderer["ownerText"]) ?: text(renderer["shortBylineText"])
            ?: text(renderer["longBylineText"])
        val detail = listOfNotNull(text(renderer["shortViewCountText"]), text(renderer["publishedTimeText"]))
            .joinToString(" • ")
            .ifBlank { null }
        val overlays = (renderer["thumbnailOverlays"] as? JsonArray).orEmpty()
        return YouTubeVideo(
            videoId = videoId,
            title = title,
            channelName = channel,
            detail = detail,
            thumbnailUrl = bestThumbnail(renderer["thumbnail"]) ?: defaultThumbnail(videoId),
            duration = text(renderer["lengthText"]) ?: overlays.firstNotNullOfOrNull {
                text(it.path("thumbnailOverlayTimeStatusRenderer", "text"))
            },
            watchedPercent = overlays.firstNotNullOfOrNull {
                it.path("thumbnailOverlayResumePlaybackRenderer", "percentDurationWatched").intOrNull()
            }
        )
    }

    private fun shelfTitle(shelf: JsonObject): String? =
        text(shelf.path("headerRenderer", "shelfHeaderRenderer", "title"))
            ?: text(shelf["title"])
            ?: text(shelf.path("header", "shelfHeaderRenderer", "title"))

    private fun findObjectsWithKey(element: JsonElement?, key: String, out: MutableList<JsonObject>, depth: Int) {
        if (element == null || depth > MAX_DEPTH) return
        when (element) {
            is JsonArray -> element.forEach { findObjectsWithKey(it, key, out, depth + 1) }
            is JsonObject -> {
                if (key in element) out += element
                element.values.forEach { findObjectsWithKey(it, key, out, depth + 1) }
            }
            else -> Unit
        }
    }

    /** YouTube text is either `{simpleText}` or `{runs: [{text}]}`. */
    internal fun text(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return element.stringOrNull()?.takeIf { it.isNotBlank() }
        obj["simpleText"].stringOrNull()?.let { return it.takeIf(String::isNotBlank) }
        obj["content"].stringOrNull()?.let { return it.takeIf(String::isNotBlank) }
        val runs = obj["runs"] as? JsonArray ?: return null
        return runs.joinToString("") { run -> run.path("text").stringOrNull().orEmpty() }
            .takeIf { it.isNotBlank() }
    }

    private fun bestThumbnail(element: JsonElement?): String? {
        // Older renderers list "thumbnails"; view models list "sources".
        val thumbnails = (element.path("thumbnails") ?: element.path("sources")) as? JsonArray ?: return null
        val best = thumbnails.mapNotNull { it as? JsonObject }
            .maxByOrNull { it["width"].intOrNull() ?: 0 }
            ?: return null
        val url = best["url"].stringOrNull() ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun defaultThumbnail(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    private fun JsonElement?.path(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) current = (current as? JsonObject)?.get(key) ?: return null
        return current
    }

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun JsonElement?.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
