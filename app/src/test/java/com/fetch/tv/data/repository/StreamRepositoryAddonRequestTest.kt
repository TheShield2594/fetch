package com.fetch.tv.data.repository

import android.content.Context
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.streams.AddonHealthTracker
import com.fetch.tv.core.streams.AddonRequestGuard
import com.fetch.tv.core.streams.AddonRequestKind
import com.fetch.tv.core.streams.AddonRequestOutcome
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
import com.fetch.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
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

    private fun stream() = StreamDto(name = "1080p", url = "https://cdn.example/video.mkv")

    private fun addon(resources: List<String>) = Addon(
        id = "test.addon",
        name = "Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        resources = resources.map { AddonResource(name = it, types = listOf("movie", "series"), idPrefixes = null) },
        idPrefixes = listOf("tt")
    )

    private fun newRepository(vararg addons: Addon): StreamRepositoryImpl {
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
            every { sources(any(), any(), any(), any(), any()) } returns emptyList()
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
