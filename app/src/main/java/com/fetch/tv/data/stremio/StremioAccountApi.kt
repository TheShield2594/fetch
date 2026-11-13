package com.fetch.tv.data.stremio

import com.fetch.tv.core.tracking.StremioApiException
import com.fetch.tv.core.tracking.StremioLibraryItem
import com.fetch.tv.core.tracking.parseStremioLibrary
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class StremioLinkCode(val code: String, val link: String)

/**
 * The few Stremio account calls the one-off history import needs. Sign-in uses Stremio's link
 * flow (a short code approved on stremio.com from a phone), so no password is typed on the TV
 * and accounts made with Google, Facebook or Apple work too. The auth key is held in memory for
 * the import only, and the session is signed out afterwards.
 */
@Singleton
class StremioAccountApi @Inject constructor(
    private val client: OkHttpClient
) {
    suspend fun createLinkCode(): StremioLinkCode {
        val url = LINK_API.toHttpUrl().newBuilder()
            .addPathSegments("api/v2/create")
            .addQueryParameter("type", "Create")
            .build()
        val result = resultOf(get(url.toString()))
        val code = result.optString("code").trim()
        val link = result.optString("link").trim()
        if (code.isEmpty() || link.isEmpty()) throw IOException("Stremio returned no link code")
        return StremioLinkCode(code, link)
    }

    /** The auth key once the code has been approved, or null while it is still waiting. */
    suspend fun readLinkCode(code: String): String? {
        val url = LINK_API.toHttpUrl().newBuilder()
            .addPathSegments("api/v2/read")
            .addQueryParameter("type", "Read")
            .addQueryParameter("code", code)
            .build()
        val body = JSONObject(get(url.toString()))
        // Stremio answers "invalid or expired" until the code is approved.
        if (body.has("error")) return null
        return body.optJSONObject("result")?.optString("authKey")?.trim()?.takeIf(String::isNotEmpty)
    }

    suspend fun library(authKey: String): List<StremioLibraryItem> {
        val body = JSONObject()
            .put("authKey", authKey)
            .put("collection", "libraryItem")
            .put("ids", JSONArray())
            .put("all", true)
        return parseStremioLibrary(post("datastoreGet", body))
    }

    suspend fun logout(authKey: String) {
        post("logout", JSONObject().put("type", "Logout").put("authKey", authKey))
    }

    private fun resultOf(body: String): JSONObject {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw StremioApiException(it.optInt("code"), it.optString("message")) }
        return json.optJSONObject("result") ?: throw IOException("Stremio returned no result")
    }

    private suspend fun get(url: String): String = execute(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).get().build())

    private suspend fun post(method: String, body: JSONObject): String = execute(
        Request.Builder()
            .url("$API/api/$method")
            .post(body.toString().toRequestBody(JSON))
            .build()
    )

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            // Stremio reports most errors as a 200 with an `error` object; anything else is transport.
            if (!response.isSuccessful && body.isBlank()) throw IOException("Stremio HTTP ${response.code}")
            body
        }
    }

    private companion object {
        const val API = "https://api.strem.io"
        const val LINK_API = "https://link.stremio.com"
        val JSON = "application/json".toMediaType()
    }
}
