package com.fetch.desktop.playback

import com.fetch.desktop.data.AddonClient
import com.fetch.desktop.data.AddonStreamResult
import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.tv.domain.model.Addon
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class FetchProgressTest {
    private fun addon(name: String, url: String = "https://$name.example") = Addon(
        id = name, name = name, version = "1", description = null, logo = null, baseUrl = url,
        resources = listOf(com.fetch.tv.domain.model.AddonResource("stream", listOf("movie"), null)),
        types = listOf(com.fetch.tv.domain.model.ContentType.MOVIE), catalogs = emptyList()
    )

    @Test
    fun `each addon's chip fills in as it answers, in answer order`() {
        var progress = FetchProgress.start(listOf("A", "B", "C"))
        assertEquals("Fetching… 0 answered, 3 addons still looking", FetchProgress.headline(progress))
        progress = FetchProgress.answered(progress, AddonStreamResult(addon("C"), emptyList(), "Timed out"))
        progress = FetchProgress.answered(progress, AddonStreamResult(addon("A"), emptyList()))
        assertEquals("Fetching… 2 answered, 1 addon still looking", FetchProgress.headline(progress))
        assertEquals(listOf("C · timed out", "A · 0", "B"), FetchProgress.inAnswerOrder(progress).map { it.label })
        progress = FetchProgress.finished(progress)
        assertEquals(AddonFetchStatus.ERROR, progress.first { it.name == "B" }.status)
        assertEquals("All 3 addons answered", FetchProgress.headline(progress))
    }

    @Test
    fun `addons answer one by one, slow ones last and cut off at the timeout`() = runBlocking {
        val delays = mapOf("fast" to 0L, "slow" to 300L, "stuck" to 5_000L)
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val host = chain.request().url.host.substringBefore('.')
            Thread.sleep(delays[host] ?: 0)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                .body("""{"streams":[{"name":"$host 1080p","url":"https://cdn.example/$host.mkv"}]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        val client = AddonClient.create(http)
        val addons = listOf(addon("stuck"), addon("slow"), addon("fast"))
        val answers = client.streamsAsAnswered(addons, "movie", "tt1", timeoutMs = 1_000).toList()
        assertEquals(listOf("fast", "slow", "stuck"), answers.map { it.addon.name })
        assertEquals("Timed out", answers.last().error)
        assertEquals("fast 1080p", answers.first().streams.single().name)
    }

    @Test
    fun `the TV's timeout choices, and how long one request may take`() {
        assertEquals(30, AutoPlaySettings.normalizeTimeout(60))
        assertEquals(10, AutoPlaySettings.normalizeTimeout(11))
        assertEquals(AutoPlaySettings.TIMEOUT_UNLIMITED, AutoPlaySettings.normalizeTimeout(AutoPlaySettings.TIMEOUT_UNLIMITED))
        assertEquals(20_000L, AutoPlaySettings.requestTimeoutMs(3))
        assertEquals(30_000L, AutoPlaySettings.requestTimeoutMs(30))
        assertEquals(60_000L, AutoPlaySettings.requestTimeoutMs(AutoPlaySettings.TIMEOUT_UNLIMITED))
        assertEquals("1d 12h", AutoPlaySettings.cacheLabel(36))
        assertEquals(97.5f, AutoPlaySettings.halfStep(97.4f, 97f, 100f))
    }
}
