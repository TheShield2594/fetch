package com.fetch.tv.data.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Base64

/**
 * Reads YouTube's InnerTube responses. The TV client nests its tiles differently on every page
 * and YouTube moves them around often, so this walks the whole tree and keeps any video it can
 * recognise, grouped by the shelf it sits in, instead of following one fixed path.
 */
internal object YouTubeResponseParser {

    private const val MAX_DEPTH = 64

    /** Where a tile or lockup keeps the video's own title. */
    private val VIDEO_METADATA_KEYS = setOf("tileMetadataRenderer", "lockupMetadataViewModel")

    fun parseShelves(root: JsonElement): List<YouTubeShelf> = parsePage(root).shelves

    /**
     * The shelves of one page, and the token for the page after it. A shelf carries its own token
     * when YouTube pages its row separately (the TV home does), and videos outside any shelf go
     * in one untitled shelf that takes the page's token (subscriptions and history do this).
     */
    fun parsePage(root: JsonElement): YouTubePage {
        val shelves = mutableListOf<YouTubeShelf>()
        val loose = Bucket()
        collect(root, shelves, loose, depth = 0)
        val looseVideos = loose.videos.distinctBy { it.videoId }
        val looseShelf = YouTubeShelf(
            title = null,
            videos = looseVideos,
            // A channel's Shorts tab sends its Shorts outside any shelf.
            isShorts = looseVideos.isNotEmpty() && looseVideos.all { it.isShort },
            links = loose.links.distinctBy { it.target }
        )
        return YouTubePage(
            shelves = (listOf(looseShelf) + shelves).filterNot { it.isEmpty() },
            continuation = loose.token
        )
    }

    /**
     * Reads the TV `next` response: the autoplay pick from `autoplay.autoplay.sets[0]` (as
     * SmartTube's WatchNextHelper does) and the related videos in its suggestion shelves.
     */
    fun parseWatchNext(root: JsonElement, currentVideoId: String): YouTubeWatchNext {
        val results = root.path("contents", "singleColumnWatchNextResults")
        val related = parsePage(results.path("pivot") ?: results ?: root).shelves
            .flatMap { it.videos }
            .filter { it.videoId != currentVideoId }
            .distinctBy { it.videoId }
        val set = (results.path("autoplay", "autoplay", "sets") as? JsonArray)?.firstOrNull()
        val autoplay = listOf("autoplayVideoRenderer", "nextVideoRenderer").firstNotNullOfOrNull { key ->
            val renderer = set.path(key) ?: return@firstNotNullOfOrNull null
            val item = renderer.path("maybeHistoryEndpointRenderer")
                ?: renderer.path("autoplayEndpointRenderer")
                ?: renderer.path("autoplayVideoWrapperRenderer", "primaryEndpointRenderer", "autoplayEndpointRenderer")
            parseAutoplayItem(item)?.takeIf { it.videoId != currentVideoId }
        }
        // The related tile has the duration and channel the autoplay button leaves out.
        val next = autoplay?.let { pick -> related.firstOrNull { it.videoId == pick.videoId } ?: pick }
            ?: related.firstOrNull()
        return YouTubeWatchNext(next = next, related = related, actions = parseVideoActions(root))
    }

    /** The like and subscribe buttons of the TV player's controls, and the channel they're for. */
    private fun parseVideoActions(root: JsonElement): YouTubeVideoActions? {
        fun first(key: String): JsonObject? {
            val found = mutableListOf<JsonObject>()
            findObjectsWithKey(root, key, found, depth = 0)
            return found.firstOrNull()?.get(key) as? JsonObject
        }
        val like = first("likeButtonRenderer")
        val subscribe = first("subscribeButtonRenderer")
        val owner = first("videoOwnerRenderer")
        val likeStatus = like?.get("likeStatus").stringOrNull()?.let { status ->
            YouTubeLikeStatus.entries.firstOrNull { it.name == status }
        }
        val channelId = subscribe?.get("channelId").stringOrNull()
            ?: owner.path("navigationEndpoint", "browseEndpoint", "browseId").stringOrNull()
        if (likeStatus == null && channelId == null) return null
        fun params(endpoint: String): String? {
            val found = mutableListOf<JsonObject>()
            findObjectsWithKey(subscribe, endpoint, found, depth = 0)
            return found.firstNotNullOfOrNull { it[endpoint].path("params").stringOrNull() }
        }
        return YouTubeVideoActions(
            likeStatus = likeStatus,
            channelId = channelId,
            channelName = text(owner?.get("title")),
            subscribed = subscribe?.get("subscribed").booleanOrNull(),
            subscribeParams = params("subscribeEndpoint"),
            unsubscribeParams = params("unsubscribeEndpoint")
        )
    }

    private fun parseAutoplayItem(item: JsonElement?): YouTubeVideo? {
        val videoId = item.path("endpoint", "watchEndpoint", "videoId").stringOrNull() ?: return null
        val preview = item.path("item", "previewButtonRenderer")
        return YouTubeVideo(
            videoId = videoId,
            title = text(preview.path("title")) ?: return null,
            channelName = text(preview.path("byline")),
            thumbnailUrl = bestThumbnail(preview.path("thumbnail")) ?: defaultThumbnail(videoId)
        )
    }

    /** Every channel the signed-in Google account can act as: the account itself and its brand accounts. */
    fun parseAccounts(root: JsonElement): List<YouTubeAccount> {
        val accounts = mutableListOf<JsonObject>()
        findObjectsWithKey(root, "accountName", accounts, depth = 0)
        return accounts
            .filter { it["isDisabled"].booleanOrNull() != true }
            .map { account ->
                val tokens = account.path("serviceEndpoint", "selectActiveIdentityEndpoint", "supportedTokens") as? JsonArray
                YouTubeAccount(
                    name = text(account["accountName"]),
                    handle = text(account["channelHandle"]),
                    photoUrl = bestThumbnail(account["accountPhoto"]),
                    email = text(account["accountByline"]),
                    pageId = tokens.orEmpty().firstNotNullOfOrNull { it.path("pageIdToken", "pageId").stringOrNull() },
                    isSelected = account["isSelected"].booleanOrNull() == true
                )
            }
            .distinctBy { it.pageId to it.name }
    }

    fun parseAccount(root: JsonElement): YouTubeAccount? {
        val accounts = parseAccounts(root)
        return accounts.firstOrNull { it.isSelected } ?: accounts.firstOrNull()
    }

    /** Where videos and links are being gathered, with the first page token met along the way. */
    private class Bucket {
        val videos = mutableListOf<YouTubeVideo>()
        val links = mutableListOf<YouTubeLink>()
        var token: String? = null
    }

    private fun collect(
        element: JsonElement?,
        shelves: MutableList<YouTubeShelf>,
        loose: Bucket,
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
                            val bucket = Bucket()
                            collectVideos(value, bucket, depth + 1)
                            if (bucket.videos.isNotEmpty() || bucket.links.isNotEmpty()) {
                                // Some rows, like the TV home's "wormholes", ask to be shown untitled.
                                val title = if (value["hideHeader"].booleanOrNull() == true) null else shelfTitle(value)
                                shelves += YouTubeShelf(
                                    title = title,
                                    videos = bucket.videos.distinctBy { it.videoId },
                                    continuation = bucket.token,
                                    isShorts = key == "reelShelfRenderer" ||
                                        value["tvhtml5ShelfRendererType"].stringOrNull() == "TVHTML5_SHELF_RENDERER_TYPE_SHORTS",
                                    viewAll = browseTarget(value.path("endpoint"), title),
                                    links = bucket.links.distinctBy { it.target }
                                )
                            }
                        }
                        else -> {
                            val token = continuationToken(key, value)
                            val video = if (token == null) parseVideo(key, value) else null
                            val link = if (token == null && video == null) parseLink(key, value) else null
                            when {
                                token != null -> if (loose.token == null) loose.token = token
                                video != null -> loose.videos += video
                                link != null -> loose.links += link
                                else -> collect(value, shelves, loose, depth + 1)
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun collectVideos(element: JsonElement?, out: Bucket, depth: Int) {
        if (element == null || depth > MAX_DEPTH) return
        when (element) {
            is JsonArray -> element.forEach { collectVideos(it, out, depth + 1) }
            is JsonObject -> for ((key, value) in element) {
                val token = if (value is JsonObject) continuationToken(key, value) else null
                val video = if (value is JsonObject && token == null) parseVideo(key, value) else null
                val link = if (value is JsonObject && token == null && video == null) parseLink(key, value) else null
                when {
                    token != null -> if (out.token == null) out.token = token
                    video != null -> out.videos += video
                    link != null -> out.links += link
                    else -> collectVideos(value, out, depth + 1)
                }
            }
            else -> Unit
        }
    }

    /** The TV client's `nextContinuationData`, or the newer `continuationItemRenderer`. */
    private fun continuationToken(key: String, value: JsonObject): String? = when (key) {
        "nextContinuationData" -> value["continuation"].stringOrNull()
        "continuationItemRenderer" -> {
            val endpoint = value["continuationEndpoint"]
            endpoint.path("continuationCommand", "token").stringOrNull()
                ?: (endpoint.path("commandExecutorCommand", "commands") as? JsonArray).orEmpty()
                    .firstNotNullOfOrNull { it.path("continuationCommand", "token").stringOrNull() }
        }
        else -> null
    }

    private fun parseVideo(key: String, renderer: JsonObject): YouTubeVideo? = when (key) {
        "tileRenderer" -> parseTile(renderer)
        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" ->
            parseWebVideo(renderer)
        "lockupViewModel" -> parseLockup(renderer)
        else -> null
    }

    private fun parseLink(key: String, renderer: JsonObject): YouTubeLink? = when (key) {
        "tileRenderer" -> parseTileLink(renderer)
        "lockupViewModel" -> parseLockupLink(renderer)
        else -> null
    }

    /**
     * A channel or playlist tile. A channel page's untitled row of these ("wormholes") opens its
     * tabs: Videos, Shorts, Live, Playlists; their titles sit over the thumbnail they don't have.
     */
    private fun parseTileLink(tile: JsonObject): YouTubeLink? {
        val kind = when (tile["contentType"].stringOrNull()) {
            "TILE_CONTENT_TYPE_CHANNEL" -> YouTubeLinkKind.CHANNEL
            "TILE_CONTENT_TYPE_PLAYLIST" -> YouTubeLinkKind.PLAYLIST
            else -> return null
        }
        val header = tile.path("header", "tileHeaderRenderer")
        val metadata = tile.path("metadata", "tileMetadataRenderer")
            ?: (header.path("thumbnailOverlays") as? JsonArray).orEmpty()
                .firstNotNullOfOrNull { it.path("tileMetadataRenderer") }
        val title = text(metadata.path("title")) ?: return null
        val target = browseTarget(tile.path("onSelectCommand"), title) ?: return null
        val subtitle = (metadata.path("lines") as? JsonArray).orEmpty().firstNotNullOfOrNull { line ->
            (line.path("lineRenderer", "items") as? JsonArray)?.mapNotNull { text(it.path("lineItemRenderer", "text")) }
                ?.joinToString(" • ")
                ?.takeIf { it.isNotBlank() }
        }
        val tab = if (kind == YouTubeLinkKind.CHANNEL) channelTab(target, tile) else null
        return YouTubeLink(target, kind, title, subtitle, bestThumbnail(header.path("thumbnail")), tab)
    }

    /**
     * The channel tab [target] opens. Its browse params are a protobuf whose second field names
     * the tab ("videos", "shorts", "streams"…), as on the web; failing that, the shortcut's line
     * icon (MY_VIDEOS, …) says which it is.
     */
    private fun channelTab(target: YouTubeBrowseTarget, tile: JsonElement): YouTubeChannelTab? {
        val name = target.params?.let(::paramsTabName)
        TAB_NAMES[name]?.let { return it }
        val icons = mutableListOf<JsonObject>()
        findObjectsWithKey(tile, "iconType", icons, depth = 0)
        return icons.firstNotNullOfOrNull { icon ->
            val type = icon["iconType"].stringOrNull() ?: return@firstNotNullOfOrNull null
            when {
                "SHORTS" in type -> YouTubeChannelTab.SHORTS
                "VIDEOS" in type -> YouTubeChannelTab.VIDEOS
                "LIVE" in type -> YouTubeChannelTab.LIVE
                "PLAYLIST" in type -> YouTubeChannelTab.PLAYLISTS
                "ALBUM" in type || "RELEASE" in type -> YouTubeChannelTab.RELEASES
                "PODCAST" in type -> YouTubeChannelTab.PODCASTS
                else -> null
            }
        }
    }

    private val TAB_NAMES = mapOf(
        "videos" to YouTubeChannelTab.VIDEOS,
        "shorts" to YouTubeChannelTab.SHORTS,
        "streams" to YouTubeChannelTab.LIVE,
        "playlists" to YouTubeChannelTab.PLAYLISTS,
        "releases" to YouTubeChannelTab.RELEASES,
        "podcasts" to YouTubeChannelTab.PODCASTS,
        "community" to YouTubeChannelTab.COMMUNITY,
        "posts" to YouTubeChannelTab.COMMUNITY
    )

    /** The tab name in a channel's browse params: field 2 (tag 0x12) of the protobuf they encode. */
    internal fun paramsTabName(params: String): String? {
        val bytes = runCatching {
            // Sent URL-encoded ("%3D" padding), in either base64 alphabet.
            val base64 = params.replace("%3D", "", ignoreCase = true).replace('+', '-').replace('/', '_').trimEnd('=')
            Base64.getUrlDecoder().decode(base64)
        }.getOrNull() ?: return null
        if (bytes.size < 2 || bytes[0] != 0x12.toByte()) return null
        val length = bytes[1].toInt()
        if (length <= 0 || 2 + length > bytes.size) return null
        return String(bytes, 2, length, Charsets.UTF_8)
    }

    private fun parseLockupLink(lockup: JsonObject): YouTubeLink? {
        val kind = when (lockup["contentType"].stringOrNull()) {
            "LOCKUP_CONTENT_TYPE_CHANNEL" -> YouTubeLinkKind.CHANNEL
            "LOCKUP_CONTENT_TYPE_PLAYLIST" -> YouTubeLinkKind.PLAYLIST
            else -> return null
        }
        val metadata = lockup.path("metadata", "lockupMetadataViewModel")
        val title = text(metadata.path("title")) ?: return null
        val command = lockup.path("rendererContext", "commandContext", "onTap", "innertubeCommand")
        val target = browseTarget(command, title) ?: return null
        val subtitle = (metadata.path("metadata", "contentMetadataViewModel", "metadataRows") as? JsonArray).orEmpty()
            .firstNotNullOfOrNull { row ->
                (row.path("metadataParts") as? JsonArray)?.mapNotNull { text(it.path("text")) }
                    ?.joinToString(" • ")
                    ?.takeIf { it.isNotBlank() }
            }
        val image = lockup.path("contentImage", "thumbnailViewModel", "image")
            ?: lockup.path("contentImage", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel", "image")
        val tab = if (kind == YouTubeLinkKind.CHANNEL) channelTab(target, lockup) else null
        return YouTubeLink(target, kind, title, subtitle, bestThumbnail(image), tab)
    }

    /**
     * YouTube's newer item layout, which it has been moving feeds to (SmartTube's LockupItem in
     * common/models/gen/CommonItems.kt). Playlists and channels are lockups too; they carry no video id.
     */
    private fun parseLockup(lockup: JsonObject): YouTubeVideo? {
        val contentType = lockup["contentType"].stringOrNull()
        val isShort = contentType == "LOCKUP_CONTENT_TYPE_SHORT"
        if (contentType != null && contentType != "LOCKUP_CONTENT_TYPE_VIDEO" && !isShort) return null
        val command = lockup.path("rendererContext", "commandContext", "onTap", "innertubeCommand")
        val videoId = watchVideoId(command) ?: return null
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
            },
            isShort = isShort,
            isLive = badges.any {
                it.path("thumbnailBadgeViewModel", "badgeStyle").stringOrNull() == "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"
            },
            // In the long-press menu's "Go to channel".
            channelId = channelId(lockup["rendererContext"], depth = 0),
            channels = channelRefs(lockup["metadata"], lockup["rendererContext"], lockup)
        )
    }

    /** The TV client's tile. Channels and playlists are tiles too; only videos and Shorts are kept. */
    private fun parseTile(tile: JsonObject): YouTubeVideo? {
        val contentType = tile["contentType"].stringOrNull()
        val isShort = tile["style"].stringOrNull() == "TILE_STYLE_YTLR_SHORTS" ||
            contentType == "TILE_CONTENT_TYPE_SHORTS"
        if (contentType != null && contentType != "TILE_CONTENT_TYPE_VIDEO" && !isShort) return null
        val videoId = watchVideoId(tile.path("onSelectCommand")) ?: return null
        val metadata = tile.path("metadata", "tileMetadataRenderer") as? JsonObject
        val header = tile.path("header", "tileHeaderRenderer") as? JsonObject
        val overlays = (header?.get("thumbnailOverlays") as? JsonArray).orEmpty()
        // A Short's tile has no metadata; its title is overlaid on the thumbnail.
        val title = text(metadata?.get("title"))
            ?: overlays.firstNotNullOfOrNull { text(it.path("tileMetadataRenderer", "title")) }
            ?: return null
        val lines = (metadata?.get("lines") as? JsonArray).orEmpty().mapNotNull { line ->
            val items = line.path("lineRenderer", "items") as? JsonArray
            items?.mapNotNull { text(it.path("lineItemRenderer", "text")) }
                ?.filter { it.isNotBlank() && it != "•" }
                ?.joinToString(" • ")
                ?.takeIf { it.isNotBlank() }
        }
        val timeStatus = overlays.firstNotNullOfOrNull { it.path("thumbnailOverlayTimeStatusRenderer") }
        return YouTubeVideo(
            videoId = videoId,
            title = title,
            channelName = lines.getOrNull(0),
            detail = lines.getOrNull(1),
            thumbnailUrl = bestThumbnail(header?.get("thumbnail")) ?: defaultThumbnail(videoId),
            duration = text(timeStatus.path("text")),
            watchedPercent = overlays.firstNotNullOfOrNull {
                it.path("thumbnailOverlayResumePlaybackRenderer", "percentDurationWatched").intOrNull()
            },
            isShort = isShort,
            isLive = timeStatus.path("style").stringOrNull() == "LIVE",
            // On the channel name's line.
            channelId = channelId(metadata, depth = 0),
            // A collaboration's byline or long-press menu lists its channels.
            channels = channelRefs(metadata, tile)
        )
    }

    /** A video's id from a regular watch command, or a Short's reel one. */
    private fun watchVideoId(command: JsonElement?): String? =
        command.path("watchEndpoint", "videoId").stringOrNull()
            ?: command.path("reelWatchEndpoint", "videoId").stringOrNull()

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
            },
            channelId = channelId(renderer["ownerText"] ?: renderer["shortBylineText"] ?: renderer["longBylineText"], depth = 0),
            channels = channelRefs(renderer["ownerText"], renderer["shortBylineText"], renderer["longBylineText"], renderer)
        )
    }

    private fun browseTarget(endpoint: JsonElement?, title: String?): YouTubeBrowseTarget? {
        val browse = endpoint.path("browseEndpoint") ?: return null
        val browseId = browse.path("browseId").stringOrNull() ?: return null
        return YouTubeBrowseTarget(browseId = browseId, params = browse.path("params").stringOrNull(), title = title)
    }

    /** The first channel (a UC… browse id) linked anywhere under [element]. */
    private fun channelId(element: JsonElement?, depth: Int): String? {
        if (element == null || depth > MAX_DEPTH) return null
        return when (element) {
            is JsonArray -> element.firstNotNullOfOrNull { channelId(it, depth + 1) }
            is JsonObject -> element.path("browseEndpoint", "browseId").stringOrNull()?.takeIf { it.startsWith("UC") }
                ?: element.values.firstNotNullOfOrNull { channelId(it, depth + 1) }
            else -> null
        }
    }

    /**
     * Every channel linked under [elements], in the order met, each named by the run or list item
     * that links it. Menus and the video's own title name nothing: they'd name the menu item
     * ("Go to channel") or the video.
     */
    private fun channelRefs(vararg elements: JsonElement?): List<YouTubeChannelRef> {
        val found = LinkedHashMap<String, String?>()
        fun walk(element: JsonElement?, name: String?, names: Boolean, inMenu: Boolean, depth: Int) {
            if (element == null || depth > MAX_DEPTH) return
            when (element) {
                is JsonArray -> element.forEach { walk(it, name, names, inMenu, depth + 1) }
                is JsonObject -> {
                    val own = if (names && !inMenu) text(element["title"]) ?: text(element["text"]) else null
                    val here = own ?: name
                    element.path("browseEndpoint", "browseId").stringOrNull()?.takeIf { it.startsWith("UC") }?.let { id ->
                        if (found[id] == null) found[id] = here
                    }
                    for ((key, value) in element) {
                        val menu = inMenu || key.contains("menu", ignoreCase = true)
                        walk(value, here, names = key !in VIDEO_METADATA_KEYS, inMenu = menu, depth = depth + 1)
                    }
                }
                else -> Unit
            }
        }
        // The renderers themselves are titled with the video.
        elements.forEach { walk(it, name = null, names = false, inMenu = false, depth = 0) }
        return found.map { (id, name) -> YouTubeChannelRef(id, name) }
    }

    private fun shelfTitle(shelf: JsonObject): String? =
        // The TV client now puts the title in the header's avatar lockup.
        text(shelf.path("headerRenderer", "shelfHeaderRenderer", "avatarLockup", "avatarLockupRenderer", "title"))
            ?: text(shelf.path("headerRenderer", "shelfHeaderRenderer", "title"))
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
