package com.fetch.tv.data.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/** What a video's watch page says under the player: its description and numbers, and the first comments. */
data class YouTubeVideoDetails(
    val title: String?,
    val description: String,
    /** "448,566,342 views", as YouTube words it. */
    val viewCount: String?,
    /** "Apr 23, 2005". */
    val published: String?,
    val channelName: String?,
    /** "6.74M subscribers". */
    val subscriberCount: String?,
    /** "10,644,286", from the comments' header; null until they're read, or when they're off. */
    val commentCount: String? = null,
    val comments: List<YouTubeComment> = emptyList(),
    /** The next page of comments. */
    val commentsContinuation: String? = null
)

data class YouTubeComment(
    val id: String,
    val author: String,
    val authorPhotoUrl: String?,
    val text: String,
    /** "6 years ago". */
    val published: String?,
    /** "4.8M"; null when there are none. */
    val likeCount: String?,
    val replyCount: String?,
    val isPinned: Boolean,
    /** Written by the video's channel. */
    val isCreator: Boolean
)

data class YouTubeCommentsPage(
    val comments: List<YouTubeComment>,
    val continuation: String?,
    val count: String?
)

/**
 * Search filters, as YouTube's own Filters menu offers them. [params] is the protobuf YouTube's
 * search endpoint reads them from (`SearchParams`: sort is field 1; the filters are a message in
 * field 2 holding upload date (1), type (2) and duration (3)), base64-encoded.
 */
data class YouTubeSearchFilter(
    val sort: Sort = Sort.RELEVANCE,
    val uploadDate: UploadDate = UploadDate.ANY,
    val type: Type = Type.ANY,
    val duration: Duration = Duration.ANY
) {
    enum class Sort(val value: Int) { RELEVANCE(0), UPLOAD_DATE(2), VIEW_COUNT(3), RATING(1) }
    enum class UploadDate(val value: Int) { ANY(0), LAST_HOUR(1), TODAY(2), THIS_WEEK(3), THIS_MONTH(4), THIS_YEAR(5) }
    enum class Type(val value: Int) { ANY(0), VIDEO(1), CHANNEL(2), PLAYLIST(3), MOVIE(4) }
    enum class Duration(val value: Int) { ANY(0), UNDER_4_MINUTES(1), OVER_20_MINUTES(2), FOUR_TO_20_MINUTES(3) }

    val isDefault: Boolean get() = this == YouTubeSearchFilter()

    /** Null for no filters, so the request is the same one YouTube's search box sends. */
    val params: String?
        get() {
            val filters = buildList {
                if (uploadDate != UploadDate.ANY) addAll(listOf(0x08, uploadDate.value))
                if (type != Type.ANY) addAll(listOf(0x10, type.value))
                if (duration != Duration.ANY) addAll(listOf(0x18, duration.value))
            }
            val bytes = buildList {
                if (sort != Sort.RELEVANCE) addAll(listOf(0x08, sort.value))
                if (filters.isNotEmpty()) {
                    add(0x12)
                    add(filters.size)
                    addAll(filters)
                }
            }
            if (bytes.isEmpty()) return null
            return Base64.getEncoder().encodeToString(ByteArray(bytes.size) { bytes[it].toByte() })
        }
}

/** Reads the web watch page's description and comments. Kept apart from the network so it can be tested. */
object YouTubeDetailsParser {

    /** The description and numbers from a web `next` response; null when it has neither. */
    fun parseVideoDetails(root: JsonElement?): YouTubeVideoDetails? {
        val contents = root.path("contents", "twoColumnWatchNextResults", "results", "results", "contents") as? JsonArray
            ?: return null
        val primary = contents.firstNotNullOfOrNull { it.path("videoPrimaryInfoRenderer") as? JsonObject }
        val secondary = contents.firstNotNullOfOrNull { it.path("videoSecondaryInfoRenderer") as? JsonObject }
        if (primary == null && secondary == null) return null
        val description = secondary.path("attributedDescription", "content").stringOrNull()
            ?: text(secondary.path("description"))
            ?: ""
        return YouTubeVideoDetails(
            title = text(primary.path("title")),
            description = description.trim(),
            viewCount = text(primary.path("viewCount", "videoViewCountRenderer", "viewCount")),
            published = text(primary.path("dateText")),
            channelName = text(secondary.path("owner", "videoOwnerRenderer", "title")),
            subscriberCount = text(secondary.path("owner", "videoOwnerRenderer", "subscriberCountText"))
        )
    }

    /** The token that loads the comments, from a web `next` response; null when comments are off. */
    fun parseCommentsContinuation(root: JsonElement?): String? {
        val contents = root.path("contents", "twoColumnWatchNextResults", "results", "results", "contents") as? JsonArray
            ?: return null
        val section = contents.firstNotNullOfOrNull { item ->
            (item.path("itemSectionRenderer") as? JsonObject)
                ?.takeIf { it["sectionIdentifier"].stringOrNull() == "comment-item-section" }
        } ?: return null
        return (section["contents"] as? JsonArray).orEmpty().firstNotNullOfOrNull { continuationOf(it) }
    }

    /**
     * A page of comments from a `next` continuation. Today's responses list each thread with a key
     * into `frameworkUpdates` holding the comment itself; older ones carry a `commentRenderer`.
     */
    fun parseComments(root: JsonElement?): YouTubeCommentsPage {
        val items = (root.path("onResponseReceivedEndpoints") as? JsonArray).orEmpty().flatMap { endpoint ->
            val command = endpoint.path("reloadContinuationItemsCommand") ?: endpoint.path("appendContinuationItemsAction")
            (command.path("continuationItems") as? JsonArray).orEmpty()
        }
        val entities = HashMap<String, JsonObject>()
        val mutations = root.path("frameworkUpdates", "entityBatchUpdate", "mutations") as? JsonArray
        mutations.orEmpty().forEach { mutation ->
            val payload = mutation.path("payload", "commentEntityPayload") as? JsonObject ?: return@forEach
            val key = payload["key"].stringOrNull() ?: mutation.path("entityKey").stringOrNull() ?: return@forEach
            entities[key] = payload
        }
        val comments = items.mapNotNull { item ->
            val thread = item.path("commentThreadRenderer") as? JsonObject ?: return@mapNotNull null
            val viewModel = thread.path("commentViewModel", "commentViewModel") as? JsonObject
            if (viewModel != null) {
                val entity = viewModel["commentKey"].stringOrNull()?.let(entities::get) ?: return@mapNotNull null
                entityComment(entity, pinned = viewModel["pinnedText"].stringOrNull() != null)
            } else {
                (thread.path("comment", "commentRenderer") as? JsonObject)?.let(::legacyComment)
            }
        }
        val count = items.firstNotNullOfOrNull { text(it.path("commentsHeaderRenderer", "countText")) }
            ?.filter { it.isDigit() || it == ',' || it == '.' }
            ?.takeIf { it.isNotEmpty() }
        return YouTubeCommentsPage(comments, items.lastOrNull()?.let(::continuationOf), count)
    }

    private fun entityComment(entity: JsonObject, pinned: Boolean): YouTubeComment? {
        val properties = entity["properties"] as? JsonObject ?: return null
        val author = entity["author"] as? JsonObject
        val toolbar = entity["toolbar"] as? JsonObject
        return YouTubeComment(
            id = properties["commentId"].stringOrNull() ?: return null,
            author = author?.get("displayName").stringOrNull() ?: properties["authorButtonA11y"].stringOrNull() ?: "",
            authorPhotoUrl = author?.get("avatarThumbnailUrl").stringOrNull(),
            text = properties.path("content", "content").stringOrNull() ?: return null,
            published = properties["publishedTime"].stringOrNull(),
            likeCount = toolbar?.get("likeCountNotliked").stringOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
            replyCount = toolbar?.get("replyCount").stringOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
            isPinned = pinned,
            isCreator = author?.get("isCreator").booleanOrFalse()
        )
    }

    private fun legacyComment(renderer: JsonObject): YouTubeComment? = YouTubeComment(
        id = renderer["commentId"].stringOrNull() ?: return null,
        author = text(renderer["authorText"]) ?: "",
        authorPhotoUrl = (renderer.path("authorThumbnail", "thumbnails") as? JsonArray)?.lastOrNull().path("url").stringOrNull(),
        text = text(renderer["contentText"]) ?: return null,
        published = text(renderer["publishedTimeText"]),
        likeCount = text(renderer["voteCount"])?.takeIf { it.isNotBlank() },
        replyCount = renderer["replyCount"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it != "0" },
        isPinned = renderer["pinnedCommentBadge"] != null,
        isCreator = renderer["authorIsChannelOwner"].booleanOrFalse()
    )

    private fun continuationOf(item: JsonElement?): String? {
        val renderer = item.path("continuationItemRenderer") ?: return null
        return renderer.path("continuationEndpoint", "continuationCommand", "token").stringOrNull()
            ?: renderer.path("button", "buttonRenderer", "command", "continuationCommand", "token").stringOrNull()
    }

    private fun text(element: JsonElement?): String? = YouTubeResponseParser.text(element)?.takeIf { it.isNotBlank() }

    private fun JsonElement?.path(vararg keys: String): JsonElement? {
        var current = this
        for (key in keys) current = (current as? JsonObject)?.get(key) ?: return null
        return current
    }

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement?.booleanOrFalse(): Boolean = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true
}

/** Choosing between Fetch's own saved place in a video and YouTube's progress for it. */
object YouTubeResume {
    /**
     * Whether YouTube's progress ([watchedPercent], from the video's card) should win over the place
     * Fetch saved: the video was finished elsewhere, or watched clearly further on another device
     * since. Fetch's place stays when the two roughly agree, since it's exact to the second.
     */
    fun youTubeIsAhead(savedPositionMs: Long, savedDurationMs: Long, watchedPercent: Int?): Boolean {
        val percent = watchedPercent ?: return false
        if (percent > 95) return true
        if (savedDurationMs <= 0L) return percent > 0
        val savedPercent = savedPositionMs * 100 / savedDurationMs
        return percent >= savedPercent + 5
    }
}
