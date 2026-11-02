package com.fetch.desktop.ratings

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Answers every request with [respond] and records what was sent. */
class RatingsFakeHttp(private val respond: (Request) -> Pair<Int, String>) : Interceptor {
    val sent = java.util.Collections.synchronizedList(mutableListOf<Request>())

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        sent += request
        val (code, json) = respond(request)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }

    companion object {
        /** Routes by path suffix; unknown paths 404. */
        fun routes(vararg routes: Pair<String, String>) = RatingsFakeHttp { request ->
            val path = request.url.encodedPath
            routes.firstOrNull { path.endsWith(it.first) }?.let { 200 to it.second } ?: (404 to """{"success":false}""")
        }
    }
}
