package com.fetch.tv.data.repository

import android.content.Context
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.streams.AddonHealthTracker
import com.fetch.tv.core.streams.AddonRequestGuard
import com.fetch.tv.core.streams.AddonRequestKind
import com.fetch.tv.core.streams.AddonRequestOutcome
import com.fetch.tv.data.mediaserver.ServerStreamSource
import com.fetch.tv.data.mediaserver.ServerStreams
import com.fetch.tv.data.remote.api.AddonApi
import com.fetch.tv.data.remote.dto.MetaDto
import com.fetch.tv.data.remote.dto.MetaResponseDto
import com.fetch.tv.data.remote.dto.StreamDto
import com.fetch.tv.data.remote.dto.StreamResponseDto
import com.fetch.tv.data.remote.dto.VideoDto
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonResource
import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.StreamSourceOutcome
import com.fetch.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/**
 * How one addon's stream request is made: Refresh must get past the HTTP cache, and the
 * inline-meta fallback for an empty answer must only go to addons that can answer it, inside
 * the addon's request budget.
 */
class StreamRepositoryAddonRequestTest {

    private val api = mockk<AddonApi>()
    private val tracker = mockk<AddonHealthTracker>(relaxed = true)

    @Test
    fun `a forced refresh asks the addon to revalidate instead of serving its cached streams`() = runTest {
        coEvery { api.getStreams(any(), any()) } returns Response.success(StreamResponseDto(listOf(stream())))
        val repository = newRepository(addon(resources = listOf("stream")))

        repository.getStreamsFromAllAddons("movie", "tt1", forceRefresh = false).last()
        repository.getStreamsFromAllAddons("movie", "tt1", forceRefresh = true).last()

        coVerify(exactly = 1) { api.getStreams(any(), null) }
        coVerify(exactly = 1) { api.getStreams(any(), "no-cache") }
    }

    @Test
    fun `an empty movie answer does not ask for the meta`() = runTest {
        coEvery { api.getStreams(any(), any()) } returns Response.success(StreamResponseDto(emptyList()))
        val repository = newRepository(addon(resources = listOf("stream", "meta")))

        repository.getStreamsFromAllAddons("movie", "tt1").last()

        coVerify(exactly = 0) { api.getMeta(any(), any()) }
        verify { tracker.record("Addon", AddonRequestOutcome.EMPTY, any(), any(), AddonRequestKind.STREAM, any()) }
    }

    @Test
    fun `an empty answer from an addon without a meta resource does not ask for the meta`() = runTest {
        coEvery { api.getStreams(any(), any()) } returns Response.success(StreamResponseDto(emptyList()))
        val repository = newRepository(addon(resources = listOf("stream")))

        repository.getStreamsFromAllAddons("series", "tt1:1:2", season = 1, episode = 2).last()

        coVerify(exactly = 0) { api.getMeta(any(), any()) }
    }

    @Test
    fun `inline meta streams rescue an empty series answer and count as the addon's success`() = runTest {
        coEvery { api.getStreams(any(), any()) } returns Response.success(StreamResponseDto(emptyList()))
        coEvery { api.getMeta(any(), any()) } returns Response.success(
            MetaResponseDto(
                MetaDto(
                    id = "tt1",
                    type = "series",
                    name = "Show",
                    videos = listOf(VideoDto(id = "tt1:1:2", streams = listOf(stream())))
                )
            )
        )
        val repository = newRepository(addon(resources = listOf("stream", "meta")))

        val result = repository.getStreamsFromAllAddons("series", "tt1:1:2", season = 1, episode = 2).last()

        val groups = (result as NetworkResult.Success<List<AddonStreams>>).data
        assertEquals(1, groups.single().streams.size)
        coVerify(exactly = 1) { api.getMeta("https://addon.example/meta/series/tt1.json", null) }
        // One outcome per addon, recorded after the fallback: the chip settles on the real answer.
        verify(exactly = 1) { tracker.record("Addon", AddonRequestOutcome.SUCCESS, any(), any(), AddonRequestKind.STREAM, any()) }
        verify(exactly = 0) { tracker.record("Addon", AddonRequestOutcome.EMPTY, any(), any(), any(), any()) }
    }

    @Test
    fun `a forced refresh also revalidates the inline meta fallback`() = runTest {
        coEvery { api.getStreams(any(), any()) } returns Response.success(StreamResponseDto(emptyList()))
        coEvery { api.getMeta(any(), any()) } returns Response.success(MetaResponseDto(null))
        val repository = newRepository(addon(resources = listOf("stream", "meta")))

        repository.getStreamsFromAllAddons("series", "tt1:1:2", season = 1, episode = 2, forceRefresh = true).last()

        coVerify(exactly = 1) { api.getMeta(any(), "no-cache") }
    }

    @Test
    fun `each source reports how it finished, and a cached replay brings the outcomes back`() = runTest {
        // TheShield2594/fetch#397: an empty media server and an empty addon are "no results",
        // not failures, also when the search is served again from the session cache.
        coEvery { api.getStreams("https://addon.example/stream/movie/tt1.json", any()) } returns
            Response.success(StreamResponseDto(listOf(stream())))
        coEvery { api.getStreams("https://empty.example/stream/movie/tt1.json", any()) } returns
            Response.success(StreamResponseDto(emptyList()))
        val server = ServerStreamSource(name = "Jellyfin", preferred = false) { emptyList() }
        val repository = newRepository(
            addon(resources = listOf("stream")),
            addon(resources = listOf("stream"), name = "Empty", baseUrl = "https://empty.example"),
            serverSources = listOf(server)
        )
        val expected = mapOf(
            "Addon" to StreamSourceOutcome.STREAMS,
            "Empty" to StreamSourceOutcome.EMPTY,
            "Jellyfin" to StreamSourceOutcome.EMPTY
        )

        val live = repository.searchStreamsFromAllSources("movie", "tt1").last()
        val replayed = repository.searchStreamsFromAllSources("movie", "tt1").toList()

        assertEquals(expected, live.sourceOutcomes)
        assertEquals(expected, replayed.last().sourceOutcomes)
        assertEquals(live.result, replayed.last().result)
        coVerify(exactly = 2) { api.getStreams(any(), any()) }
    }

    @Test
    fun `a failing addon reports a failure`() = runTest {
        coEvery { api.getStreams(any(), any()) } returns Response.error(500, "".toResponseBody(null))
        val repository = newRepository(addon(resources = listOf("stream")))

        val update = repository.searchStreamsFromAllSources("movie", "tt1").last()

        assertEquals(mapOf("Addon" to StreamSourceOutcome.FAILED), update.sourceOutcomes)
    }

    private fun stream() = StreamDto(name = "1080p", url = "https://cdn.example/video.mkv")

    private fun addon(
        resources: List<String>,
        name: String = "Addon",
        baseUrl: String = "https://addon.example"
    ) = Addon(
        id = "test.${name.lowercase()}",
        name = name,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        resources = resources.map { AddonResource(name = it, types = listOf("movie", "series"), idPrefixes = null) },
        idPrefixes = listOf("tt")
    )

    private fun newRepository(
        vararg addons: Addon,
        serverSources: List<ServerStreamSource> = emptyList()
    ): StreamRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "detail"
            every { getString(any(), *anyVararg()) } returns "message"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(addons.toList())
        }
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(1)
        }
        val serverStreams = mockk<ServerStreams> {
            every { isNativeRequest(any()) } returns false
            every { revision } returns 0
            every { sources(any(), any(), any(), any(), any()) } returns serverSources
        }
        return StreamRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            profileManager = profileManager,
            serverStreams = serverStreams,
            addonRequestGuard = AddonRequestGuard(tracker = tracker, timeoutMs = { 10_000L })
        )
    }
}
