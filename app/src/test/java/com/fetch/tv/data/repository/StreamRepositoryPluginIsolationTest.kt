package com.fetch.tv.data.repository

import android.content.Context
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.plugin.PluginManager
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.data.remote.api.AddonApi
import com.fetch.tv.data.remote.dto.StreamDto
import com.fetch.tv.data.remote.dto.StreamResponseDto
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonResource
import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.RepositoryType
import com.fetch.tv.domain.model.ScraperInfo
import com.fetch.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class StreamRepositoryPluginIsolationTest {
    @Test
    fun `addon results arrive while plugin TMDB lookup is pending`() = runBlocking {
        val harness = newHarness(listOf(compatibleScraper()))
        val tmdbResult = CompletableDeferred<String?>()
        coEvery { harness.tmdbService.ensureTmdbId("tt1341338", "movie") } coAnswers {
            tmdbResult.await()
        }

        val result = withTimeout(1_000) {
            harness.repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt1341338",
                season = null,
                episode = null
            ).first { it is NetworkResult.Success }
        }

        val groups = (result as NetworkResult.Success).data
        assertEquals(listOf("Fast Addon"), groups.map { it.addonName })
        assertEquals(1, groups.single().streams.size)
        assertTrue(tmdbResult.isActive)
        coVerify(exactly = 1) { harness.api.getStreams(any()) }
        tmdbResult.complete(null)
        Unit
    }

    @Test
    fun `TMDB lookup is skipped when no compatible plugin is enabled`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        assertTrue(results.last() is NetworkResult.Success)
        coVerify(exactly = 0) { harness.tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 1) { harness.api.getStreams(any()) }
    }

    /**
     * The stream path must read addon display info from the installed [Addon] it was given, not
     * refetch the manifest. fetchAddon is deliberately left stubbed on the harness so this asserts
     * the call is available and still not made, rather than merely un-stubbed.
     */
    @Test
    fun `stream lookup issues no manifest request`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        val success = results.last() as NetworkResult.Success
        assertEquals(listOf("Fast Addon"), success.data.map { it.addonName })
        // Pins the URL too: the regression is not merely "one call happened", it is that the
        // stream request is the only request, built from the addon's own baseUrl.
        coVerify(exactly = 1) { harness.api.getStreams("https://addon.example/stream/movie/tt1341338.json") }
        coVerify(exactly = 0) { harness.addonRepository.fetchAddon(any()) }
    }

    @Test
    fun `streams carry the installed addon name and logo`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        val success = results.last() as NetworkResult.Success
        val groups = success.data
        assertEquals(listOf("Fast Addon"), groups.map { it.addonName })
        assertEquals(listOf(ADDON_LOGO), groups.map { it.addonLogo })
        assertTrue(groups.single().streams.all { it.addonName == "Fast Addon" })
        assertTrue(groups.single().streams.all { it.addonLogo == ADDON_LOGO })
        coVerify(exactly = 0) { harness.addonRepository.fetchAddon(any()) }
    }

    private fun newHarness(enabledScrapers: List<ScraperInfo>): Harness {
        val addon = compatibleAddon()
        val api = mockk<AddonApi>()
        coEvery { api.getStreams(any()) } returns Response.success(
            StreamResponseDto(
                streams = listOf(
                    StreamDto(
                        name = "Fast Stream",
                        url = "https://stream.example/video.m3u8"
                    )
                )
            )
        )

        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { addonRepository.fetchAddon(addon.baseUrl) } returns NetworkResult.Success(addon)

        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.enabledScrapers } returns flowOf(enabledScrapers)
        every { pluginManager.pluginsEnabled } returns flowOf(enabledScrapers.isNotEmpty())
        every { pluginManager.groupStreamsByRepository } returns flowOf(false)
        every { pluginManager.repositories } returns flowOf(emptyList())

        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val tmdbService = mockk<TmdbService>(relaxed = true)

        return Harness(
            repository = StreamRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                api = api,
                addonRepository = addonRepository,
                pluginManager = pluginManager,
                profileManager = profileManager,
                tmdbService = tmdbService,
                serverStreams = mockk {
                    every { isNativeRequest(any()) } returns false
                    every { revision } returns 0
                    every { sources(any(), any(), any(), any(), any()) } returns emptyList()
                },
                addonHealthTracker = com.fetch.tv.core.streams.AddonHealthTracker()
            ),
            api = api,
            tmdbService = tmdbService,
            addonRepository = addonRepository
        )
    }

    private fun compatibleAddon(): Addon = Addon(
        id = "fast-addon",
        name = "Fast Addon",
        version = "1.0.0",
        description = null,
        logo = ADDON_LOGO,
        baseUrl = "https://addon.example",
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(
            AddonResource(
                name = "stream",
                types = listOf("movie"),
                idPrefixes = listOf("tt")
            )
        )
    )

    private fun compatibleScraper(): ScraperInfo = ScraperInfo(
        id = "plugin",
        name = "Plugin",
        description = "",
        version = "1.0.0",
        filename = "plugin.js",
        supportedTypes = listOf("movie"),
        enabled = true,
        manifestEnabled = true,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = "repo",
        formats = null,
        type = RepositoryType.FETCH_JS
    )

    private companion object {
        const val ADDON_LOGO = "https://addon.example/logo.png"
    }

    private data class Harness(
        val repository: StreamRepositoryImpl,
        val api: AddonApi,
        val tmdbService: TmdbService,
        val addonRepository: AddonRepository
    )
}
