package com.fetch.tv.data.mediaserver.plex

import com.fetch.tv.data.mediaserver.ServerException
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.mediabrowser.ApiResponse
import com.fetch.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.fetch.tv.data.mediaserver.mediabrowser.buildUrl
import com.fetch.tv.data.mediaserver.mediabrowser.fetch
import com.fetch.tv.data.mediaserver.mediabrowser.headerValue
import com.fetch.tv.data.mediaserver.mediabrowser.throwForStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** HTTP for plex.tv and Plex Media Server: JSON responses and the `X-Plex-*` client headers. */
internal class PlexClient(
    http: OkHttpClient,
    private val identity: ServerClientIdentity
) {
    private val http = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // Used to find which of a server's addresses answers, so it gives up quickly.
    private val probeHttp = this.http.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    val clientId: String
        get() = identity.deviceId()

    /** Headers every Plex request carries; the token is left out when null. */
    fun headers(token: String?): Map<String, String> = buildMap {
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Version", identity.version.headerValue())
        put("X-Plex-Client-Identifier", clientId.headerValue())
        put("X-Plex-Platform", PLATFORM)
        put("X-Plex-Device", identity.device.headerValue())
        put("X-Plex-Device-Name", identity.device.headerValue())
        if (token != null) put("X-Plex-Token", token.headerValue())
    }

    suspend fun <T> get(
        baseUrl: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        token: String?,
        query: Map<String, String?> = emptyMap()
    ): T = json.decodeFromString(deserializer, execute("GET", baseUrl, path, token, query).body.ifBlank { "{}" })

    suspend fun execute(
        method: String,
        baseUrl: String,
        path: String,
        token: String?,
        query: Map<String, String?> = emptyMap(),
        probe: Boolean = false
    ): ApiResponse {
        val request = try {
            Request.Builder()
                .url(buildUrl(baseUrl, path, query))
                .header("Accept", "application/json")
                .apply { headers(token).forEach { (name, value) -> header(name, value) } }
                .method(method, if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody())
                .build()
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.NOT_FOUND)
        }
        val response = try {
            (if (probe) probeHttp else http).fetch(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        throwForStatus(response.status)
        return response
    }

    companion object {
        const val PRODUCT = "Fetch"
        const val PLATFORM = "Android"
        const val PLEX_TV = "https://plex.tv"
        private const val PROBE_TIMEOUT_S = 4L
    }
}
