package com.fetch.tv.core.debrid

import com.fetch.tv.data.local.DebridSettingsDataStore
import com.fetch.tv.data.remote.api.AllDebridApi
import com.fetch.tv.data.remote.dto.AllDebridEnvelopeDto
import com.fetch.tv.data.remote.dto.AllDebridErrorDto
import com.fetch.tv.data.remote.dto.AllDebridFileNodeDto
import com.fetch.tv.data.remote.dto.AllDebridMagnetFilesDto
import com.fetch.tv.data.remote.dto.AllDebridMagnetFilesEntryDto
import com.fetch.tv.data.remote.dto.AllDebridMagnetUploadDto
import com.fetch.tv.data.remote.dto.AllDebridPinCheckDto
import com.fetch.tv.data.remote.dto.AllDebridPinDto
import com.fetch.tv.data.remote.dto.AllDebridUnlockDto
import com.fetch.tv.data.remote.dto.AllDebridUploadedMagnetDto
import com.fetch.tv.data.remote.dto.flattenAllDebridFiles
import com.fetch.tv.domain.model.DebridSettings
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamClientResolve
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AllDebridDirectDebridResolverTest {
    @Test
    fun `ready magnet picks the episode from the file tree and unlocks it`() = runTest {
        val api = FakeAllDebridApi(
            upload = success(AllDebridMagnetUploadDto(listOf(AllDebridUploadedMagnetDto(id = 42, ready = true)))),
            files = success(
                AllDebridMagnetFilesDto(
                    listOf(
                        AllDebridMagnetFilesEntryDto(
                            id = "42",
                            files = listOf(
                                AllDebridFileNodeDto(
                                    name = "Show S01",
                                    entries = listOf(
                                        AllDebridFileNodeDto("Show.S01E01.mkv", 900, "https://alldebrid.com/f/1"),
                                        AllDebridFileNodeDto("Show.S01E02.mkv", 800, "https://alldebrid.com/f/2")
                                    )
                                ),
                                AllDebridFileNodeDto("sample.txt", 1, "https://alldebrid.com/f/3")
                            )
                        )
                    )
                )
            ),
            unlock = success(AllDebridUnlockDto(link = "https://cdn.example/e2.mkv", filename = "Show.S01E02.mkv", filesize = 800))
        )

        val result = resolver(api).resolve(stream(), season = 1, episode = 2)

        assertTrue(result is DirectDebridResolveResult.Success)
        result as DirectDebridResolveResult.Success
        assertEquals("https://cdn.example/e2.mkv", result.url)
        assertEquals("Show.S01E02.mkv", result.filename)
        assertEquals("https://alldebrid.com/f/2", api.lastUnlockedLink)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `magnet that is not ready is removed and reported not cached`() = runTest {
        val api = FakeAllDebridApi(
            upload = success(AllDebridMagnetUploadDto(listOf(AllDebridUploadedMagnetDto(id = 7, ready = false))))
        )

        val result = resolver(api).resolve(stream(), season = null, episode = null)

        assertEquals(DirectDebridResolveResult.NotCached, result)
        assertEquals(0, api.filesCalls)
        assertEquals(1, api.deleteCalls)
    }

    @Test
    fun `bad api key is an error, not a stale link`() = runTest {
        val api = FakeAllDebridApi(
            upload = Response.success(
                AllDebridEnvelopeDto<AllDebridMagnetUploadDto>(
                    status = "error",
                    error = AllDebridErrorDto(code = "AUTH_BAD_APIKEY")
                )
            )
        )

        val result = resolver(api).resolve(stream(), season = null, episode = null)

        assertEquals(DirectDebridResolveResult.Error, result)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `file tree flattens to paths with links`() {
        val files = listOf(
            AllDebridFileNodeDto(
                name = "a",
                entries = listOf(AllDebridFileNodeDto(name = "b", entries = listOf(AllDebridFileNodeDto("c.mkv", 5, "l1"))))
            ),
            AllDebridFileNodeDto("d.mkv", 6, "l2"),
            AllDebridFileNodeDto("no-link.mkv", 7, null)
        ).flattenAllDebridFiles()

        assertEquals(listOf("a/b/c.mkv", "d.mkv"), files.map { it.path })
        assertEquals("c.mkv", files.first().displayName())
    }

    private fun <T> success(data: T): Response<AllDebridEnvelopeDto<T>> =
        Response.success(AllDebridEnvelopeDto(status = "success", data = data))

    private fun resolver(api: AllDebridApi): AllDebridDirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(DebridSettings(enabled = true, allDebridApiKey = "ad_key"))
        return AllDebridDirectDebridResolver(dataStore, api, AllDebridFileSelector())
    }

    private fun stream(): Stream = Stream(
        name = "Torrent",
        title = "Show",
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
            title = "Show",
            season = null,
            episode = null,
            service = DebridProviders.ALL_DEBRID_ID,
            serviceIndex = null,
            serviceExtension = null,
            isCached = false
        )
    )

    private class FakeAllDebridApi(
        private val upload: Response<AllDebridEnvelopeDto<AllDebridMagnetUploadDto>>,
        private val files: Response<AllDebridEnvelopeDto<AllDebridMagnetFilesDto>> =
            Response.success(AllDebridEnvelopeDto(status = "success", data = AllDebridMagnetFilesDto(emptyList()))),
        private val unlock: Response<AllDebridEnvelopeDto<AllDebridUnlockDto>> =
            Response.success(AllDebridEnvelopeDto(status = "success", data = AllDebridUnlockDto()))
    ) : AllDebridApi {
        var filesCalls = 0
        var deleteCalls = 0
        var lastUnlockedLink: String? = null

        override suspend fun getUser(authorization: String): Response<ResponseBody> =
            Response.success("{}".toResponseBody())

        override suspend fun getPin(): Response<AllDebridEnvelopeDto<AllDebridPinDto>> =
            error("unused")

        override suspend fun checkPin(check: String, pin: String): Response<AllDebridEnvelopeDto<AllDebridPinCheckDto>> =
            error("unused")

        override suspend fun uploadMagnet(authorization: String, magnet: String) = upload

        override suspend fun getMagnetFiles(authorization: String, id: Long): Response<AllDebridEnvelopeDto<AllDebridMagnetFilesDto>> {
            filesCalls++
            return files
        }

        override suspend fun unlockLink(authorization: String, link: String): Response<AllDebridEnvelopeDto<AllDebridUnlockDto>> {
            lastUnlockedLink = link
            return unlock
        }

        override suspend fun deleteMagnet(authorization: String, id: Long): Response<ResponseBody> {
            deleteCalls++
            return Response.success("{}".toResponseBody())
        }
    }
}
