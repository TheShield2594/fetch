package com.fetch.tv.core.network

import okhttp3.OkHttpClient

/**
 * Root of every OkHttp client in the app. Each `OkHttpClient.Builder()` brings its own
 * connection pool and dispatcher threads, so clients start from `root.newBuilder()` and
 * share them. Connections are pooled per address, which includes the TLS settings, so a
 * trust-all client never reuses a validating client's connection or the other way round.
 *
 * Playback keeps its own pool and dispatcher (PlayerPlaybackNetworking): it runs many
 * parallel HTTP/1.1 range requests to one host and shouldn't compete with the rest.
 */
object SharedHttpClient {
    val root: OkHttpClient by lazy { OkHttpClient() }
}
