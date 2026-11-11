@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.fetch.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.LocalNetworkTls
import com.fetch.tv.core.network.PlaybackSocketFactory
import okhttp3.OkHttpClient
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

    fun createHttpClient(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false
    ): OkHttpClient {
        val builder = playbackHttpClient.newBuilder()
        if (useLongReadTimeout) {
            builder.readTimeout(LOOPBACK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val proxyHeaders = defaultHeaders.filterKeys { key ->
            !key.equals("Range", ignoreCase = true)
        }
        if (proxyHeaders.isNotEmpty()) {
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                val next = request.newBuilder()
                for ((key, value) in proxyHeaders) {
                    next.header(key, value)
                }
                chain.proceed(next.build())
            }
        }
        return builder
            .let { FetchExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
    }

    @UnstableApi
    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false
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
        val client = createHttpClient(requestHeaders, useLongReadTimeout)
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
        defaultHeaders: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders))
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
