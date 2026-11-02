package com.fetch.desktop.servers

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/** Answers every request with [respond] (null: the host is unreachable) and records what was sent. */
class FakeServerHttp(private val respond: (Request) -> Pair<Int, String>?) : Interceptor {

    class Sent(val request: Request, val body: String) {
        val path: String get() = request.url.encodedPath
        fun query(name: String): String? = request.url.queryParameter(name)
        fun header(name: String): String? = request.header(name)
    }

    val sent = mutableListOf<Sent>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    fun sentTo(path: String): List<Sent> = synchronized(sent) { sent.filter { it.path == path } }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        synchronized(sent) { sent += Sent(request, body) }
        val (code, json) = respond(request) ?: throw IOException("unreachable")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
