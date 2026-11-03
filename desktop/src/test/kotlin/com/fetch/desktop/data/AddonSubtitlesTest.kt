package com.fetch.desktop.data

import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonResource
import com.fetch.tv.domain.model.ContentType
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonSubtitlesTest {
    private fun addon(name: String) = Addon(
        id = name, name = name, version = "1", description = null, logo = null, baseUrl = "https://$name.example",
        resources = listOf(AddonResource("subtitles", listOf("movie"), null)),
        types = listOf(ContentType.MOVIE), catalogs = emptyList()
    )

    @Test
    fun `each subtitle keeps the addon it came from`() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val host = chain.request().url.host.substringBefore('.')
            val body = if (host == "broken") "nope" else
                """{"subtitles":[{"id":"1","lang":"ENG","url":"https://cdn.example/$host.srt"},{"id":"2","lang":"fre","url":"not-a-link"}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val subs = AddonClient.create(http).subtitlesBySource(listOf(addon("opensubs"), addon("broken"), addon("subdl")), "movie", "tt1")
        assertEquals(
            listOf(
                SourcedSubtitle("eng", "https://cdn.example/opensubs.srt", "opensubs"),
                SourcedSubtitle("eng", "https://cdn.example/subdl.srt", "subdl")
            ),
            subs
        )
    }
}
