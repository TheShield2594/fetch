package com.fetch.desktop.collections

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** One canned answer: status, body and headers. */
data class FakeAnswer(val code: Int = 200, val body: String = "{}", val headers: Map<String, String> = emptyMap())

/** Answers each request with [respond] and records what was sent (with headers, for Trakt's paging). */
class CollectionsFakeHttp(private val respond: (Request) -> FakeAnswer) : Interceptor {
    val sent = java.util.Collections.synchronizedList(mutableListOf<Request>())

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        sent += request
        val answer = respond(request)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.code)
            .message("")
            .apply { answer.headers.forEach { (k, v) -> header(k, v) } }
            .body(answer.body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    companion object {
        /** Routes by path suffix; unknown paths 404. */
        fun routes(vararg routes: Pair<String, FakeAnswer>) = CollectionsFakeHttp { request ->
            val path = request.url.encodedPath
            routes.firstOrNull { path.endsWith(it.first) }?.second ?: FakeAnswer(404, """{"success":false}""")
        }
    }
}
