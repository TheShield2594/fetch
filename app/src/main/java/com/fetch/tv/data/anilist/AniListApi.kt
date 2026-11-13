package com.fetch.tv.data.anilist

import com.fetch.tv.BuildConfig
import com.fetch.tv.core.util.runSuspendCatching
import com.fetch.tv.data.anime.AnimeEntry
import com.fetch.tv.data.anime.AnimeListEntry
import com.fetch.tv.data.anime.AnimeListStatus
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

class AniListApiException(message: String, val unauthorized: Boolean = false) : IOException(message)

/** The AniList GraphQL calls Fetch makes. Signing in needs [clientId]; public id lookups don't. */
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

    /** The MyAnimeList id of AniList anime [mediaId]. A public lookup: no sign-in or client id needed. */
    suspend fun malIdForMedia(mediaId: Long): Long? {
        val data = query(
            null,
            "query (\$id: Int) { Media(id: \$id, type: ANIME) { idMal } }",
            JSONObject().put("id", mediaId),
            notFoundIsNull = true
        ) ?: return null
        return data.optJSONObject("Media")?.optLong("idMal")?.takeIf { it > 0L }
    }

    suspend fun entry(token: String, mediaId: Long): AnimeEntry? {
        val data = query(
            token,
            "query (\$id: Int) { Media(id: \$id, type: ANIME) { id episodes mediaListEntry { status progress } } }",
            JSONObject().put("id", mediaId),
            notFoundIsNull = true
        ) ?: return null
        val media = data.optJSONObject("Media") ?: return null
        val entry = media.optJSONObject("mediaListEntry")
        return AnimeEntry(
            status = entry?.optString("status")?.let(::statusOf),
            progress = entry?.optInt("progress") ?: 0,
            episodes = media.optIntOrNull("episodes")
        )
    }

    suspend fun saveProgress(token: String, mediaId: Long, progress: Int, status: AnimeListStatus) {
        query(
            token,
            "mutation (\$mediaId: Int, \$progress: Int, \$status: MediaListStatus) { " +
                "SaveMediaListEntry(mediaId: \$mediaId, progress: \$progress, status: \$status) { id } }",
            JSONObject().put("mediaId", mediaId).put("progress", progress).put("status", status.name)
        )
    }

    suspend fun animeLists(token: String, userId: Long): List<AnimeListEntry> {
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
        // Public lookups (ids only) work without a client id; signed-in calls need one to get a token.
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

internal fun parseAniListEntries(lists: JSONArray): List<AnimeListEntry> = buildList {
    for (i in 0 until lists.length()) {
        val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
        for (j in 0 until entries.length()) {
            val entry = entries.optJSONObject(j) ?: continue
            val media = entry.optJSONObject("media") ?: continue
            val id = media.optLong("id").takeIf { it > 0L } ?: continue
            add(
                AnimeListEntry(
                    aniListId = id,
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
}.distinctBy { it.aniListId } // An entry can sit in a custom list as well as its status list.

internal fun statusOf(value: String?): AnimeListStatus? =
    AnimeListStatus.entries.firstOrNull { it.name == value }

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (isNull(name) || !has(name)) null else optInt(name).takeIf { it > 0 }
