package com.fetch.desktop.servers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal val serverJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
}

private val JSON_TYPE = "application/json".toMediaType()

internal class ServerResponse(val code: Int, val body: String, val url: HttpUrl)

/**
 * Sends one request on the IO dispatcher. Throws [ServerAuthException] on 401 and
 * [ServerException] on any other failure; messages never contain the URL (it may hold a token).
 */
internal suspend fun OkHttpClient.send(
    service: String,
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String? = null
): ServerResponse = withContext(Dispatchers.IO) {
    val request = try {
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(method, body?.toRequestBody(JSON_TYPE) ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null)
            .build()
    } catch (_: IllegalArgumentException) {
        throw ServerException("That $service address isn't valid")
    }
    try {
        newCall(request).execute().use { response ->
            if (response.code == 401) throw ServerAuthException("$service rejected the sign-in")
            if (!response.isSuccessful) throw ServerException("$service request failed (HTTP ${response.code})", response.code)
            ServerResponse(response.code, response.body?.string().orEmpty(), response.request.url)
        }
    } catch (_: IOException) {
        throw ServerException("Couldn't reach the $service server")
    }
}

/** [baseUrl] plus [path] (already encoded, starting with '/') and the non-null [query] values. */
internal fun buildUrl(baseUrl: String, path: String, query: Map<String, String?> = emptyMap()): String {
    require(path.startsWith('/'))
    val base = baseUrl.toHttpUrlOrNull() ?: throw ServerException("That server address isn't valid")
    return base.newBuilder()
        .encodedPath(base.encodedPath.trimEnd('/') + path)
        .query(null)
        .fragment(null)
        .apply { query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) } }
        .build()
        .toString()
}

/** [value] encoded as one URL path segment. */
internal fun pathSegment(value: String): String =
    HttpUrl.Builder().scheme("http").host("h").addPathSegment(value).build().encodedPath.removePrefix("/")

/**
 * A typed server address as a base URL: `http://` when no scheme is given, no trailing slash,
 * and without a pasted `/web` page or [apiPath] (Emby's `/emby`). Null when it is not a URL.
 */
internal fun normalizeServerAddress(input: String, apiPath: String = ""): String? {
    var value = input.trim().trimEnd('/')
    if (value.isEmpty()) return null
    if (!value.contains("://")) value = "http://$value"
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.host.isBlank() || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val path = url.encodedPath.trimEnd('/')
        .removeSuffix("/web/index.html")
        .removeSuffix("/web")
        .trimEnd('/')
        .let { if (apiPath.isNotEmpty() && it.endsWith(apiPath, ignoreCase = true)) it.dropLast(apiPath.length) else it }
    return url.newBuilder()
        .encodedPath(path.ifEmpty { "/" })
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/** True when a dotted [version] ("10.9.11", "1.40.2.8395-c67dce28e") is at least [minimum]. */
internal fun isAtLeastVersion(version: String?, minimum: String): Boolean {
    val actual = version?.split('.')?.map { it.takeWhile(Char::isDigit).toIntOrNull() } ?: return false
    if (actual.firstOrNull() == null) return false
    for ((index, required) in minimum.split('.').map(String::toInt).withIndex()) {
        val part = actual.getOrNull(index) ?: 0
        if (part != required) return part > required
    }
    return true
}

/** Drops characters a header value may not hold (quotes, line breaks, non-ASCII). */
internal fun String.headerValue(): String = filter { it in ' '..'~' && it != '"' }

internal fun defaultDeviceName(): String = System.getProperty("os.name")?.takeIf { it.isNotBlank() } ?: "Linux"

internal fun parseInstantMs(value: String?): Long? =
    value?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

internal fun String?.imdbOrNull(): String? = this?.trim()?.takeIf { it.startsWith("tt") && it.length > 2 }

/**
 * IMDb id → item ids for whole libraries, built from paged listings and kept for a few minutes.
 * The fallback when a server cannot look a title up directly.
 */
internal class ImdbIndex(private val ttlMs: Long = 10 * 60_000L) {
    private val cache = ConcurrentHashMap<String, Pair<Long, Map<String, List<String>>>>()

    /** Item ids for [imdb] in the library [key]; [page] returns (imdb, itemId) pairs from [start]. */
    suspend fun lookup(key: String, imdb: String, page: suspend (start: Int, size: Int) -> List<Pair<String?, String>>): List<String> {
        val now = System.currentTimeMillis()
        val entry = cache[key]?.takeIf { now - it.first < ttlMs }
            ?: (now to build(page)).also { cache[key] = it }
        return entry.second[imdb].orEmpty()
    }

    private suspend fun build(page: suspend (start: Int, size: Int) -> List<Pair<String?, String>>): Map<String, List<String>> {
        val entries = mutableListOf<Pair<String?, String>>()
        while (entries.size < MAX_ITEMS) {
            val items = page(entries.size, PAGE_SIZE)
            entries += items
            if (items.size < PAGE_SIZE) break
        }
        return entries.mapNotNull { (imdb, id) -> imdb?.let { it to id } }.groupBy({ it.first }, { it.second })
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val MAX_ITEMS = 50_000
    }
}
