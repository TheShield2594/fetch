package com.fetch.desktop.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

internal val syncJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

internal fun JsonElement.toJsonBody(): RequestBody = toString().toRequestBody(JSON_MEDIA_TYPE)

internal class SyncHttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

internal suspend fun OkHttpClient.sendSync(request: Request): SyncHttpResult = withContext(Dispatchers.IO) {
    newCall(request).execute().use { response -> SyncHttpResult(response.code, response.body?.string().orEmpty()) }
}

/** The headers the TV app sends to every endpoint: the anon key, plus the user's JWT when signed in. */
internal fun Request.Builder.syncHeaders(config: SyncServerConfig, accessToken: String?): Request.Builder = apply {
    header("apikey", config.anonKey)
    header("Accept", "application/json")
    if (!accessToken.isNullOrBlank()) header("Authorization", "Bearer $accessToken")
}

/** The server's own error text (GoTrue `error_description`/`msg`, PostgREST `message`), if any. */
internal fun SyncHttpResult.errorText(): String? {
    val obj = runCatching { syncJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    for (field in listOf("error_description", "msg", "message", "error")) {
        val value = runCatching { obj[field]?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

internal fun SyncHttpResult.failure(what: String): SyncException {
    val detail = errorText()?.let { ": $it" }.orEmpty()
    return SyncException("Sync server $what failed (HTTP $code)$detail", code)
}
