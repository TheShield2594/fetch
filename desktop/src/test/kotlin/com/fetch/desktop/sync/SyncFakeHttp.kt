package com.fetch.desktop.sync

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Answers every request with [respond] and records what was sent (a copy of tracking's FakeHttp). */
class SyncFakeHttp(private val respond: (Request) -> Pair<Int, String>) : Interceptor {

    class Sent(val request: Request, val body: String) {
        val path: String get() = request.url.encodedPath
        fun header(name: String): String? = request.header(name)
    }

    val sent = mutableListOf<Sent>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        sent += Sent(request, body)
        val (code, json) = respond(request)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

val testServer = SyncServerConfig("https://sync.example.com/", "anon-key")

val testSession = SyncSession("access-1", "refresh-1", 2_000_000_000L, "user-1", "me@example.com")

const val TEST_ORIGIN = "fetch-desktop-test0000000000000000000000000000"

const val REFRESHED_TOKENS =
    """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600,"expires_at":2000003600,"user":{"id":"user-1","email":"me@example.com"}}"""
