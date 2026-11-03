package com.fetch.desktop.mdblist

import com.fetch.desktop.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * How a request to MDBList is signed: an API key (the `apikey` query parameter, as the TV's
 * ratings use) or an OAuth access token from the device sign-in (the TV's account).
 */
sealed interface MdbListAuth {
    data class ApiKey(val key: String) : MdbListAuth {
        override fun toString(): String = "ApiKey()"
    }

    data class Bearer(val accessToken: String) : MdbListAuth {
        override fun toString(): String = "Bearer()"
    }
}

/** A failed MDBList request. [retryAtEpochMs] is set when MDBList's rate limit was hit. */
class MdbListException(
    val status: Int? = null,
    val code: String? = null,
    val retryAtEpochMs: Long? = null,
    message: String = "MDBList request failed" + (status?.let { " (HTTP $it)" } ?: "")
) : Exception(message) {
    val unauthorized: Boolean get() = status == 401 || status == 403
}

class MdbListResponse(val status: Int, val body: String, val headers: Headers = Headers.headersOf()) {
    fun header(name: String): String? = headers[name]

    /** The `error` field of an error body (`authorization_pending`, `invalid_grant`, …). */
    fun errorCode(): String? = runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }
}

/**
 * MDBList's REST API (api.mdblist.com), as the TV's `MdbListHttpClient`: JSON in and out, the
 * `User-Agent` Fetch sends, and its rate limit honoured. Once MDBList answers 429, or says no
 * requests remain, every request fails fast until the limit resets instead of spending more.
 */
class MdbListHttp(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appVersion: String = AppInfo.version,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Volatile
    private var blockedUntil = 0L

    /** When the rate limit lets requests through again, or null when it isn't blocking. */
    val rateLimitedUntil: Long? get() = blockedUntil.takeIf { it > clock() }

    suspend fun get(path: String, auth: MdbListAuth?, query: Map<String, String> = emptyMap()): MdbListResponse =
        execute("GET", path, query, null, auth)

    suspend fun post(path: String, body: JsonElement, auth: MdbListAuth?, query: Map<String, String> = emptyMap()): MdbListResponse =
        execute("POST", path, query, body.toString().toRequestBody(JSON), auth)

    suspend fun put(path: String, body: JsonElement, auth: MdbListAuth?): MdbListResponse =
        execute("PUT", path, emptyMap(), body.toString().toRequestBody(JSON), auth)

    suspend fun delete(path: String, auth: MdbListAuth?): MdbListResponse = execute("DELETE", path, emptyMap(), null, auth)

    /** A form POST (the OAuth endpoints). Any status is returned: the caller reads the error. */
    suspend fun postForm(path: String, fields: Map<String, String>): MdbListResponse =
        execute("POST", path, emptyMap(), FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build(), null, anyStatus = true)

    private suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String>,
        body: RequestBody?,
        auth: MdbListAuth?,
        anyStatus: Boolean = false
    ): MdbListResponse {
        require(path.startsWith('/') && !path.startsWith("//") && '?' !in path) { "Invalid MDBList path" }
        rateLimitedUntil?.let { throw MdbListException(429, "rate_limited", it, "MDBList's request limit was reached; try again later") }
        val url = baseUrl.toHttpUrl().newBuilder().encodedPath(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
            if (auth is MdbListAuth.ApiKey) addQueryParameter("apikey", auth.key)
        }.build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Fetch/$appVersion")
            .apply { if (auth is MdbListAuth.Bearer) header("Authorization", "Bearer ${auth.accessToken}") }
            .method(method, body)
            .build()
        val response = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { MdbListResponse(it.code, it.body?.string().orEmpty(), it.headers) }
        }
        noteRateLimit(response)
        if (response.status == 429) throw MdbListException(429, "rate_limited", blockedUntil, "MDBList's request limit was reached; try again later")
        if (!anyStatus && response.status !in 200..299) throw MdbListException(response.status, response.errorCode())
        return response
    }

    private fun noteRateLimit(response: MdbListResponse) {
        val remaining = response.header("X-RateLimit-Remaining")?.trim()?.toLongOrNull()
        if (response.status != 429 && remaining != 0L) return
        val now = clock()
        val reset = response.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()
            ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
        val retryAt = response.header("Retry-After")?.let { retryAfterEpochMs(it, now) }
        blockedUntil = maxOf(now + 1_000L, retryAt ?: reset ?: (now + 60_000L))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.mdblist.com"
        private val JSON = "application/json".toMediaType()
    }
}

/** A `Retry-After` header (seconds, or an HTTP date) as an epoch time. */
internal fun retryAfterEpochMs(value: String, now: Long): Long? {
    value.trim().toLongOrNull()?.let { seconds ->
        return seconds.takeIf { it in 0..(Long.MAX_VALUE - now) / 1000 }?.let { now + it * 1000 }
    }
    return runCatching { ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
}
