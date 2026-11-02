package com.fetch.desktop.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.OffsetDateTime

internal val trackingJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

internal fun JsonElement.toJsonBody(): RequestBody = toString().toRequestBody(JSON_MEDIA_TYPE)

internal class HttpResult(val code: Int, val body: String, val headers: Headers) {
    val ok: Boolean get() = code in 200..299
}

/** Runs [request] on the IO dispatcher and reads the whole body. */
internal suspend fun OkHttpClient.send(request: Request): HttpResult = withContext(Dispatchers.IO) {
    newCall(request).execute().use { response ->
        HttpResult(response.code, response.body?.string().orEmpty(), response.headers)
    }
}

/** Throws [TrackingAuthException] on 401 and [TrackingException] on any other failure. */
internal fun HttpResult.requireOk(service: String, what: String): HttpResult {
    if (code == 401) throw TrackingAuthException("$service rejected the sign-in")
    if (!ok) throw TrackingException("$service $what failed (HTTP $code)", code)
    return this
}

internal inline fun <reified T> HttpResult.decode(): T = trackingJson.decodeFromString(body)

internal fun parseIsoEpochMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
}

internal fun String?.imdbOrNull(): String? = this?.trim()?.takeIf { it.startsWith("tt") }
