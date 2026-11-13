package com.fetch.tv.data.anilist

import com.fetch.tv.BuildConfig
import com.fetch.tv.core.util.runSuspendCatching
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class AniListViewer(val id: Long, val name: String)

/** A user's list entry for one anime, with what Fetch needs to move it forward. */
data class AniListEntry(
    val mediaId: Long,
    val status: AniListStatus?,
    val progress: Int,
    /** The anime's episode count; null while it is still airing with no count announced. */
    val episodes: Int?
)

enum class AniListStatus { CURRENT, PLANNING, COMPLETED, DROPPED, PAUSED, REPEATING }

/** One anime on a user's lists, for the history import. */
data class AniListListEntry(
    val mediaId: Long,
    val malId: Long?,
    val title: String,
    val isMovie: Boolean,
    val status: AniListStatus?,
    val progress: Int,
    /** Epoch seconds of the last change; 0 when unknown. */
    val updatedAt: Long,
    val coverUrl: String?
)

class AniListApiException(message: String, val unauthorized: Boolean = false) : IOException(message)

/** The AniList GraphQL calls Fetch makes. Every call needs the client id to be configured. */
@Singleton
class AniListApi @Inject constructor(
    private val client: OkHttpClient
) {
    val clientId: String = BuildConfig.ANILIST_CLIENT_ID.trim()
    val isConfigured: Boolean get() = clientId.isNotEmpty()

    /** Where the phone signs in; AniList then shows the token on its own pin page. */
    val authorizeUrl: String
        get() = "https://anilist.co/api/v2/oauth/authorize?client_id=$clientId&response_type=token"

    suspend fun viewer(token: String): AniListViewer {
        val data = query(token, "query { Viewer { id name } }") ?: throw AniListApiException("No data")
        val viewer = data.optJSONObject("Viewer") ?: throw AniListApiException("No viewer", unauthorized = true)
        return AniListViewer(viewer.optLong("id"), viewer.optString("name"))
    }

    /** The AniList id of the anime with MyAnimeList id [malId], or null when AniList has none. */
    suspend fun mediaIdForMal(token: String?, malId: Long): Long? {
        val data = query(
            token,
            "query (\$idMal: Int) { Media(idMal: \$idMal, type: ANIME) { id } }",
            JSONObject().put("idMal", malId),
            notFoundIsNull = true
        ) ?: return null
        return data.optJSONObject("Media")?.optLong("id")?.takeIf { it > 0L }
    }

    suspend fun entry(token: String, mediaId: Long): AniListEntry? {
        val data = query(
            token,
            "query (\$id: Int) { Media(id: \$id, type: ANIME) { id episodes mediaListEntry { status progress } } }",
            JSONObject().put("id", mediaId),
            notFoundIsNull = true
        ) ?: return null
        val media = data.optJSONObject("Media") ?: return null
        val entry = media.optJSONObject("mediaListEntry")
        return AniListEntry(
            mediaId = media.optLong("id"),
            status = entry?.optString("status")?.let(::statusOf),
            progress = entry?.optInt("progress") ?: 0,
            episodes = media.optIntOrNull("episodes")
        )
    }

    suspend fun saveProgress(token: String, mediaId: Long, progress: Int, status: AniListStatus) {
        query(
            token,
            "mutation (\$mediaId: Int, \$progress: Int, \$status: MediaListStatus) { " +
                "SaveMediaListEntry(mediaId: \$mediaId, progress: \$progress, status: \$status) { id } }",
            JSONObject().put("mediaId", mediaId).put("progress", progress).put("status", status.name)
        )
    }

    suspend fun animeLists(token: String, userId: Long): List<AniListListEntry> {
        val data = query(
            token,
            "query (\$userId: Int) { MediaListCollection(userId: \$userId, type: ANIME) { lists { entries { " +
                "status progress updatedAt media { id idMal format title { userPreferred } coverImage { large } } } } } }",
            JSONObject().put("userId", userId)
        ) ?: throw AniListApiException("No data")
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: JSONArray()
        return parseAniListEntries(lists)
    }

    private suspend fun query(
        token: String?,
        query: String,
        variables: JSONObject = JSONObject(),
        notFoundIsNull: Boolean = false
    ): JSONObject? = withContext(Dispatchers.IO) {
        if (!isConfigured) throw AniListApiException("AniList isn't configured in this build")
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Accept", "application/json")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .post(JSONObject().put("query", query).put("variables", variables).toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runSuspendCatching { JSONObject(body) }.getOrNull()
                ?: throw AniListApiException("AniList HTTP ${response.code}", unauthorized = response.code == 401)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val first = errors.optJSONObject(0)
                val status = first?.optInt("status") ?: response.code
                if (notFoundIsNull && status == 404) return@use null
                throw AniListApiException(
                    first?.optString("message").orEmpty().ifBlank { "AniList error $status" },
                    unauthorized = status == 401 || response.code == 401
                )
            }
            if (!response.isSuccessful) {
                throw AniListApiException("AniList HTTP ${response.code}", unauthorized = response.code == 401)
            }
            json.optJSONObject("data") ?: throw AniListApiException("AniList returned no data")
        }
    }

    private companion object {
        const val ENDPOINT = "https://graphql.anilist.co"
        val JSON = "application/json".toMediaType()
    }
}

internal fun parseAniListEntries(lists: JSONArray): List<AniListListEntry> = buildList {
    for (i in 0 until lists.length()) {
        val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
        for (j in 0 until entries.length()) {
            val entry = entries.optJSONObject(j) ?: continue
            val media = entry.optJSONObject("media") ?: continue
            val id = media.optLong("id").takeIf { it > 0L } ?: continue
            add(
                AniListListEntry(
                    mediaId = id,
                    malId = media.optLong("idMal").takeIf { it > 0L },
                    title = media.optJSONObject("title")?.optString("userPreferred").orEmpty(),
                    isMovie = media.optString("format") == "MOVIE",
                    status = statusOf(entry.optString("status")),
                    progress = entry.optInt("progress").coerceAtLeast(0),
                    updatedAt = entry.optLong("updatedAt").coerceAtLeast(0L),
                    coverUrl = media.optJSONObject("coverImage")?.optString("large")?.takeIf(String::isNotBlank)
                )
            )
        }
    }
}.distinctBy { it.mediaId } // An entry can sit in a custom list as well as its status list.

internal fun statusOf(value: String?): AniListStatus? =
    AniListStatus.entries.firstOrNull { it.name == value }

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (isNull(name) || !has(name)) null else optInt(name).takeIf { it > 0 }
