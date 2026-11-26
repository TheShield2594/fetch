@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.fetch.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.LocalNetworkTls
import com.fetch.tv.core.network.PlaybackSocketFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal object PlayerPlaybackNetworking {
    private const val LOOPBACK_READ_TIMEOUT_SECONDS = 65L

    /**
     * Settings both playback clients share, including one connection pool and dispatcher
     * kept apart from the rest of the app (see SharedHttpClient).
     */
    private val playbackBaseClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .socketFactory(PlaybackSocketFactory)
            .eventListenerFactory(PlaybackConnectionEvents)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Fallback OkHttpClient without certificate checks, for self-signed local media servers
     * (e.g. WebDAV / Plex / Jellyfin on the LAN). Only reached for local-network hosts.
     */
    internal val trustAllPlaybackHttpClient: OkHttpClient by lazy {
        LocalNetworkTls.unverifiedClient(playbackBaseClient)
    }

    /**
     * Primary OkHttpClient using standard system SSL certificates and full SNI support.
     * Falls back to [trustAllPlaybackHttpClient] after a TLS failure only for local-network
     * hosts; a public host with a bad certificate fails instead of streaming unverified.
     */
    internal val playbackHttpClient: OkHttpClient by lazy {
        playbackBaseClient.newBuilder()
            .addInterceptor(LocalNetworkTls.localFallbackInterceptor { trustAllPlaybackHttpClient })
            .build()
    }

    /**
     * [originUrl] is the stream URL the headers were given for. Credential headers (see
     * [StreamHeaderScope.isOriginOnly]) are sent only to its host; with no origin, only to the host
     * each request was first made to, so a redirect to another host never carries them.
     */
    fun createHttpClient(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        originUrl: String? = null
    ): OkHttpClient {
        val builder = scopedPlaybackClientBuilder(defaultHeaders, originUrl)
        if (useLongReadTimeout) {
            builder.readTimeout(LOOPBACK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        return builder
            .let { FetchExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
    }

    /**
     * [playbackHttpClient] with [StreamHeaderScope] applied to [streamHeaders]. The local-network TLS
     * fallback reruns the request on its own client, which follows redirects by itself, so it gets
     * the same scoping.
     */
    fun scopedPlaybackClientBuilder(
        streamHeaders: Map<String, String>,
        originUrl: String?
    ): OkHttpClient.Builder {
        if (!StreamHeaderScope.hasScopedHeaders(streamHeaders)) return playbackHttpClient.newBuilder()
        val unverified = StreamHeaderScope.scopedClient(trustAllPlaybackHttpClient, streamHeaders, originUrl)
        return playbackBaseClient.newBuilder()
            .addInterceptor(LocalNetworkTls.localFallbackInterceptor { unverified })
            .also { StreamHeaderScope.addInterceptor(it, streamHeaders, originUrl) }
    }

    @UnstableApi
    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        originUrl: String? = null
    ): DataSource.Factory {
        val requestHeaders = defaultHeaders.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty() || key.equals("Range", ignoreCase = true)) {
                null
            } else {
                key to value
            }
        }.toMap()
        val client = createHttpClient(requestHeaders, useLongReadTimeout, originUrl)
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(requestHeaders)
            if (requestHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
        return LoggingDataSourceFactory(httpFactory, "HTTP")
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        originUrl: String? = null
    ): DataSource.Factory {
        return DefaultDataSource.Factory(
            context,
            createHttpDataSourceFactory(defaultHeaders, originUrl = originUrl)
        )
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }
}

/**
 * Keeps stream credentials on the stream's own host (TheShield2594/fetch#701).
 *
 * The playback clients send a stream's headers with every request, including HLS/DASH segments and
 * keys on other hosts and every redirect hop. OkHttp only strips `Authorization` on a cross-host
 * redirect, and re-adding the headers per hop undid even that. Here credential headers go only to
 * requests in scope of the origin (same host, no HTTPS to HTTP downgrade); the rest, such as
 * User-Agent, Referer and Origin, still follow everywhere, as CDNs commonly check them.
 */
internal object StreamHeaderScope {
    private val ORIGIN_ONLY_NAMES = setOf("authorization", "proxy-authorization", "cookie")
    private val ORIGIN_ONLY_PREFIXES = listOf("x-emby-", "x-mediabrowser-", "x-jellyfin-", "x-plex-")
    private val ORIGIN_ONLY_FRAGMENTS = listOf("token", "auth", "api-key", "apikey", "session", "secret", "password")

    /** Whether [name] can carry a credential, so it must not leave the origin host. */
    fun isOriginOnly(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower in ORIGIN_ONLY_NAMES ||
            ORIGIN_ONLY_PREFIXES.any { lower.startsWith(it) } ||
            ORIGIN_ONLY_FRAGMENTS.any { it in lower }
    }

    /**
     * The headers [request] should carry from [streamHeaders]: all of them in scope of [origin],
     * only those that aren't [isOriginOnly] outside it.
     */
    fun applyTo(request: Request, streamHeaders: Map<String, String>, origin: HttpUrl): Request {
        val inScope = isInHeaderScope(request.url, origin)
        val next = request.newBuilder()
        for ((key, value) in streamHeaders) {
            if (key.equals("Range", ignoreCase = true)) continue
            if (inScope || !isOriginOnly(key)) next.header(key, value) else next.removeHeader(key)
        }
        return next.build()
    }

    /**
     * Adds the per-hop network interceptor that applies [applyTo]. It also catches the headers set
     * as the data source's default request properties, which OkHttp copies into each redirect.
     */
    fun hasScopedHeaders(streamHeaders: Map<String, String>): Boolean =
        streamHeaders.keys.any { !it.equals("Range", ignoreCase = true) }

    fun addInterceptor(builder: OkHttpClient.Builder, streamHeaders: Map<String, String>, originUrl: String?) {
        val headers = streamHeaders.filterKeys { !it.equals("Range", ignoreCase = true) }
        if (headers.isEmpty()) return
        val fixedOrigin = originUrl?.toHttpUrlOrNull()
        builder.addNetworkInterceptor { chain ->
            val origin = fixedOrigin ?: chain.call().request().url
            chain.proceed(applyTo(chain.request(), headers, origin))
        }
    }

    /** [client] with [addInterceptor] applied, for paths that build their own data source. */
    fun scopedClient(client: OkHttpClient, streamHeaders: Map<String, String>, originUrl: String?): OkHttpClient {
        if (!hasScopedHeaders(streamHeaders)) return client
        return client.newBuilder().also { addInterceptor(it, streamHeaders, originUrl) }.build()
    }
}
