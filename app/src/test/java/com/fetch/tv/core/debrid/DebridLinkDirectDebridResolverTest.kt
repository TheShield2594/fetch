package com.fetch.tv.core.debrid

import com.fetch.tv.data.local.DebridSettingsDataStore
import com.fetch.tv.data.remote.api.DebridLinkApi
import com.fetch.tv.data.remote.dto.DebridLinkEnvelopeDto
import com.fetch.tv.data.remote.dto.DebridLinkTorrentDto
import com.fetch.tv.data.remote.dto.DebridLinkTorrentFileDto
import com.fetch.tv.domain.model.DebridSettings
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamClientResolve
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DebridLinkDirectDebridResolverTest {
    @Test
    fun `cached hash returns the direct link of the chosen file`() = runTest {
        val api = FakeDebridLinkApi(
            add = Response.success(
                DebridLinkEnvelopeDto(
                    success = true,
                    value = DebridLinkTorrentDto(
                        id = "t1",
                        files = listOf(
                            DebridLinkTorrentFileDto("t1-1", "Movie.2024.2160p.mkv", 5000, "https://dl.example/big", 100.0),
                            DebridLinkTorrentFileDto("t1-2", "Sample.mkv", 50, "https://dl.example/sample", 100.0)
                        )
                    )
                )
            )
        )

        val result = resolver(api).resolve(stream(), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.Success)
        result as DirectDebridResolveResult.Success
        assertEquals("https://dl.example/big", result.url)
        assertEquals(5000L, result.videoSize)
        assertEquals("abcdef", api.lastAdded)
        assertEquals(0, api.removeCalls)
    }

    @Test
    fun `hash Debrid-Link does not have is not cached`() = runTest {
        val api = FakeDebridLinkApi(
            add = Response.error(400, """{"success":false,"error":"notAddTorrent"}""".toResponseBody("application/json".toMediaType()))
        )

        assertEquals(DirectDebridResolveResult.NotCached, resolver(api).resolve(stream(), null, null))
    }

    @Test
    fun `bad token is an error`() = runTest {
        val api = FakeDebridLinkApi(
            add = Response.error(401, """{"success":false,"error":"badToken"}""".toResponseBody("application/json".toMediaType()))
        )

        assertEquals(DirectDebridResolveResult.Error, resolver(api).resolve(stream(), null, null))
    }

    @Test
    fun `zipped torrent lists its files and removes it when nothing is downloaded`() = runTest {
        val api = FakeDebridLinkApi(
            add = Response.success(
                DebridLinkEnvelopeDto(success = true, value = DebridLinkTorrentDto(id = "t2", isZip = true))
            ),
            list = Response.success(
                DebridLinkEnvelopeDto(
                    success = true,
                    value = listOf(
                        DebridLinkTorrentDto(
                            id = "t2",
                            files = listOf(DebridLinkTorrentFileDto("t2-1", "Movie.mkv", 10, "https://dl.example/m", 40.0))
                        )
                    )
                )
            )
        )

        val result = resolver(api).resolve(stream(), null, null)

        assertEquals(DirectDebridResolveResult.NotCached, result)
        assertEquals(1, api.listCalls)
        assertEquals(1, api.removeCalls)
    }

    private fun resolver(api: DebridLinkApi): DebridLinkDirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(DebridSettings(enabled = true, debridLinkApiKey = "dl_key"))
        return DebridLinkDirectDebridResolver(dataStore, api, DebridLinkFileSelector())
    }

    private fun stream(): Stream = Stream(
        name = "Torrent",
        title = "Movie",
        description = null,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "torrent",
            infoHash = "abcdef",
            fileIdx = null,
            magnetUri = "magnet:?xt=urn:btih:abcdef",
            sources = null,
            torrentName = null,
            filename = null,
            mediaType = null,
            mediaId = null,
            mediaOnlyId = null,
            title = "Movie",
            season = null,
            episode = null,
            service = DebridProviders.DEBRID_LINK_ID,
            serviceIndex = null,
            serviceExtension = null,
            isCached = false
        )
    )

    private class FakeDebridLinkApi(
        private val add: Response<DebridLinkEnvelopeDto<DebridLinkTorrentDto>>,
        private val list: Response<DebridLinkEnvelopeDto<List<DebridLinkTorrentDto>>> =
            Response.success(DebridLinkEnvelopeDto(success = true, value = emptyList()))
    ) : DebridLinkApi {
        var lastAdded: String? = null
        var listCalls = 0
        var removeCalls = 0

        override suspend fun getAccount(authorization: String): Response<ResponseBody> =
            Response.success("{}".toResponseBody())

        override suspend fun addTorrent(authorization: String, url: String): Response<DebridLinkEnvelopeDto<DebridLinkTorrentDto>> {
            lastAdded = url
            return add
        }

        override suspend fun listTorrents(authorization: String, ids: String): Response<DebridLinkEnvelopeDto<List<DebridLinkTorrentDto>>> {
            listCalls++
            return list
        }

        override suspend fun removeTorrents(authorization: String, ids: String): Response<ResponseBody> {
            removeCalls++
            return Response.success("{}".toResponseBody())
        }
    }
}
