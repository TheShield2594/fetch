package com.fetch.tv.data.mal

import com.fetch.tv.BuildConfig
import com.fetch.tv.core.util.runSuspendCatching
import com.fetch.tv.data.anime.AnimeEntry
import com.fetch.tv.data.anime.AnimeListEntry
import com.fetch.tv.data.anime.AnimeListStatus
import java.io.IOException
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class MalTokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)

data class MalUser(val id: Long, val name: String)

class MalApiException(message: String, val unauthorized: Boolean = false) : IOException(message)

/**
 * The MyAnimeList API v2 calls Fetch makes. Sign-in is OAuth 2 with PKCE (MAL only supports the
 * `plain` method) and no client secret: the phone approves on myanimelist.net, lands on Fetch's
 * callback page with a one-time code, and the TV exchanges that code with the verifier it kept.
 */
@Singleton
class MalApi @Inject constructor(
    private val client: OkHttpClient
) {
    val clientId: String = BuildConfig.MAL_CLIENT_ID.trim()
    val redirectUri: String = BuildConfig.MAL_REDIRECT_URI.trim()
    val isConfigured: Boolean get() = clientId.isNotEmpty() && redirectUri.isNotEmpty()

    fun authorizeUrl(verifier: String, state: String): String =
        "https://myanimelist.net/v1/oauth2/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("code_challenge", verifier)
            .addQueryParameter("code_challenge_method", "plain")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .build()
            .toString()

    suspend fun exchangeCode(code: String, verifier: String): MalTokens = token(
        FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .build()
    )

    suspend fun refresh(refreshToken: String): MalTokens = token(
        FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
    )

    suspend fun me(token: String): MalUser {
        val json = JSONObject(call(token, Request.Builder().url("$API/users/@me").get()))
        return MalUser(json.optLong("id"), json.optString("name"))
    }

    /** The user's entry for anime [malId], or null when MAL has no such anime. */
    suspend fun entry(token: String, malId: Long): AnimeEntry? {
        val body = runSuspendCatching {
            call(token, Request.Builder().url("$API/anime/$malId?fields=num_episodes,my_list_status").get())
        }.getOrElse { error -> if (error is MalApiException && error.message == NOT_FOUND) return null else throw error }
        val json = JSONObject(body)
        val status = json.optJSONObject("my_list_status")
        return AnimeEntry(
            status = status?.let { malStatusOf(it.optString("status"), it.optBoolean("is_rewatching")) },
            progress = status?.optInt("num_episodes_watched") ?: 0,
            episodes = json.optInt("num_episodes").takeIf { it > 0 }
        )
    }

    suspend fun saveProgress(token: String, malId: Long, progress: Int, status: AnimeListStatus) {
        val form = FormBody.Builder().add("num_watched_episodes", progress.toString())
        when (status) {
            // A rewatch stays "completed" on MAL, flagged as being rewatched.
            AnimeListStatus.REPEATING -> form.add("is_rewatching", "true")
            AnimeListStatus.COMPLETED -> form.add("status", "completed").add("is_rewatching", "false")
            else -> form.add("status", malStatusName(status))
        }
        call(token, Request.Builder().url("$API/anime/$malId/my_list_status").patch(form.build()))
    }

    suspend fun animeList(token: String): List<AnimeListEntry> {
        val entries = mutableListOf<AnimeListEntry>()
        var url: String? = "$API/users/@me/animelist?fields=list_status,num_episodes,media_type&limit=1000&nsfw=true"
        var pages = 0
        while (url != null && pages++ < MAX_PAGES) {
            val json = JSONObject(call(token, Request.Builder().url(url).get()))
            entries += parseMalAnimeList(json)
            url = json.optJSONObject("paging")?.optString("next")?.takeIf { it.startsWith(API) }
        }
        return entries.distinctBy { it.malId }
    }

    private suspend fun token(form: FormBody): MalTokens = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(TOKEN_URL).post(form).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runSuspendCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful || json == null) {
                throw MalApiException(
                    json?.optString("message")?.takeIf(String::isNotBlank) ?: "MyAnimeList HTTP ${response.code}",
                    unauthorized = response.code in 400..401
                )
            }
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            if (access.isBlank() || refresh.isBlank()) throw MalApiException("MyAnimeList returned no token")
            MalTokens(access, refresh, System.currentTimeMillis() + json.optLong("expires_in", 0L) * 1_000L)
        }
    }

    private suspend fun call(token: String, builder: Request.Builder): String = withContext(Dispatchers.IO) {
        val request = builder.header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> body
                response.code == 404 -> throw MalApiException(NOT_FOUND)
                else -> throw MalApiException("MyAnimeList HTTP ${response.code}", unauthorized = response.code == 401)
            }
        }
    }

    companion object {
        private const val API = "https://api.myanimelist.net/v2"
        private const val TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
        private const val NOT_FOUND = "not_found"
        private const val MAX_PAGES = 50
        private val random = SecureRandom()
        private const val VERIFIER_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** A PKCE code verifier: 128 unreserved characters, the longest RFC 7636 allows. */
        fun newVerifier(length: Int = 128): String =
            buildString(length) { repeat(length) { append(VERIFIER_ALPHABET[random.nextInt(VERIFIER_ALPHABET.length)]) } }
    }
}

internal fun parseMalAnimeList(json: JSONObject): List<AnimeListEntry> {
    val data = json.optJSONArray("data") ?: return emptyList()
    return buildList {
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val node = item.optJSONObject("node") ?: continue
            val id = node.optLong("id").takeIf { it > 0L } ?: continue
            val status = item.optJSONObject("list_status")
            add(
                AnimeListEntry(
                    aniListId = null,
                    malId = id,
                    title = node.optString("title"),
                    isMovie = node.optString("media_type") == "movie",
                    status = status?.let { malStatusOf(it.optString("status"), it.optBoolean("is_rewatching")) },
                    progress = status?.optInt("num_episodes_watched")?.coerceAtLeast(0) ?: 0,
                    updatedAt = parseMalTime(status?.optString("updated_at")),
                    coverUrl = node.optJSONObject("main_picture")?.optString("large")?.takeIf(String::isNotBlank)
                )
            )
        }
    }
}

internal fun malStatusOf(value: String?, rewatching: Boolean): AnimeListStatus? {
    if (rewatching) return AnimeListStatus.REPEATING
    return when (value) {
        "watching" -> AnimeListStatus.CURRENT
        "completed" -> AnimeListStatus.COMPLETED
        "on_hold" -> AnimeListStatus.PAUSED
        "dropped" -> AnimeListStatus.DROPPED
        "plan_to_watch" -> AnimeListStatus.PLANNING
        else -> null
    }
}

internal fun malStatusName(status: AnimeListStatus): String = when (status) {
    AnimeListStatus.CURRENT, AnimeListStatus.REPEATING -> "watching"
    AnimeListStatus.COMPLETED -> "completed"
    AnimeListStatus.PAUSED -> "on_hold"
    AnimeListStatus.DROPPED -> "dropped"
    AnimeListStatus.PLANNING -> "plan_to_watch"
}

/** Epoch seconds of MAL's ISO-8601 time, or 0. */
internal fun parseMalTime(value: String?): Long {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return 0L
    return try {
        java.time.OffsetDateTime.parse(text).toEpochSecond()
    } catch (error: java.time.format.DateTimeParseException) {
        0L
    }
}
