package com.fetch.desktop.tracking

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.Collections

/** Answers every request with [respond] and records what was sent. */
class FakeHttp(private val respond: (Request) -> Pair<Int, String>) : Interceptor {

    class Sent(val request: Request, val body: String)

    // Requests arrive on OkHttp / IO threads; a plain list could drop one under contention.
    val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())

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

    companion object {
        fun always(code: Int, json: String = "{}") = FakeHttp { code to json }
    }
}

val testCredentials = TrackingCredentials(
    traktClientId = "trakt-id",
    traktClientSecret = "trakt-secret",
    simklClientId = "simkl-id",
    simklAppName = "fetch",
)

val testTokens = TrackingTokens("access-1", "refresh-1", 2_000_000_000L)
