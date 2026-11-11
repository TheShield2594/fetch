package com.fetch.desktop.youtube

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** A request as sent, with its body read out. */
data class SentRequest(val request: Request, val body: String) {
    val path: String get() = request.url.encodedPath
    val host: String get() = request.url.host
}

/**
 * Answers every request from [respond] instead of the network, and keeps what was sent, so the
 * YouTube clients are tested without live YouTube (as RatingsFakeHttp does for TMDB).
 */
class YouTubeFakeHttp(private val respond: (SentRequest) -> Pair<Int, String>) : Interceptor {
    val sent: MutableList<SentRequest> = java.util.Collections.synchronizedList(mutableListOf())

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        val sentRequest = SentRequest(request, body)
        sent += sentRequest
        val (code, text) = respond(sentRequest)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(text.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /** Requests to InnerTube's [endpoint] ("browse", "search"…). */
    fun innertube(endpoint: String): List<SentRequest> = sent.filter { it.path == "/youtubei/v1/$endpoint" }

    companion object {
        /** A saved TV response from shared/src/test/resources/youtube. */
        fun fixture(name: String): String =
            YouTubeFakeHttp::class.java.getResourceAsStream("/youtube/$name.json")!!.bufferedReader().use { it.readText() }
    }
}

/** An accounts_list answer: the Google account's own channel and one brand account. */
val ACCOUNTS_JSON = """
{"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
 {"accountItem":{"accountName":{"simpleText":"Ada"},"accountPhoto":{"thumbnails":[{"url":"https://yt3.example/ada.jpg","width":88}]},
   "isSelected":true,"channelHandle":{"simpleText":"@ada"},"accountByline":{"simpleText":"ada@example.com"},
   "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"accountStateToken":{}}]}}}},
 {"accountItem":{"accountName":{"simpleText":"Ada Makes Things"},"isSelected":false,
   "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"PAGE1"}}]}}}}
]}}]}}]}}}}]}
"""

/** A TV subscriptions page: two video tiles outside any shelf (one a Short, one live), and the next page's token. */
fun subscriptionsJson(vararg ids: String = arrayOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc")): String {
    fun tile(id: String, i: Int): String {
        val short = i == 1
        val live = i == 2
        return """{"tileRenderer":{"contentType":"${if (short) "TILE_CONTENT_TYPE_SHORTS" else "TILE_CONTENT_TYPE_VIDEO"}",
          "onSelectCommand":{"watchEndpoint":{"videoId":"$id"}},
          "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Video $i"},
            "lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"runs":[{"text":"Channel $i","navigationEndpoint":{"browseEndpoint":{"browseId":"UCchan$i"}}}]}}}]}},
                     {"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"1K views"}}}]}}]}},
          "header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/$id/hq.jpg","width":480}]},
            "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"${if (live) "LIVE" else "10:00"}"},"style":"${if (live) "LIVE" else "DEFAULT"}"}},
                                 {"thumbnailOverlayResumePlaybackRenderer":{"percentDurationWatched":${if (i == 0) 40 else 0}}}]}},
          "menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"FB_$id"}}}}]}}}}"""
    }
    return """{"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{"gridRenderer":{
      "items":[${ids.mapIndexed { i, id -> tile(id, i) }.joinToString(",")}],
      "continuations":[{"nextContinuationData":{"continuation":"NEXT_PAGE"}}]}}}}}}}"""
}
