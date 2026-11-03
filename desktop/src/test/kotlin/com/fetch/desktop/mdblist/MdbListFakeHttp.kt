package com.fetch.desktop.mdblist

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** One canned answer: a status, a JSON body and any headers. */
data class FakeReply(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/**
 * Answers every request with [respond] and records what was sent (with its body read out), like
 * `RatingsFakeHttp`, but with headers for MDBList's rate limit. No request leaves the machine.
 */
class MdbListFakeHttp(private val respond: (Request, String) -> FakeReply) : Interceptor {
    data class Sent(val request: Request, val body: String) {
        val method: String get() = request.method
        val path: String get() = request.url.encodedPath
        fun query(name: String): String? = request.url.queryParameter(name)
    }

    val sent = java.util.Collections.synchronizedList(mutableListOf<Sent>())

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    fun http(clock: () -> Long = System::currentTimeMillis) = MdbListHttp(client, baseUrl = "https://api.mdblist.test", appVersion = "test", clock = clock)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        sent += Sent(request, body)
        val reply = respond(request, body)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message("")
            .apply { reply.headers.forEach { (k, v) -> header(k, v) } }
            .body(reply.body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    companion object {
        /** Routes by `METHOD path` (or just the path, for any method); unknown routes 404. */
        fun routes(vararg routes: Pair<String, String>) = MdbListFakeHttp { request, _ ->
            val path = request.url.encodedPath
            val hit = routes.firstOrNull { (route, _) ->
                val parts = route.split(' ', limit = 2)
                if (parts.size == 2) parts[0] == request.method && parts[1] == path else route == path
            }
            hit?.let { FakeReply(200, it.second) } ?: FakeReply(404, """{"error":"not_found"}""")
        }
    }
}
