package com.fetch.tv.ui.components

import androidx.lifecycle.ViewModelStore
import com.fetch.tv.data.mediaserver.ServerRepository
import com.fetch.tv.data.mediaserver.ServerStreams
import com.fetch.tv.data.mediaserver.ServersUiState
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonResource
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.domain.repository.MetaRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackAvailabilityViewModelTest {
    @Test
    fun `source changes update availability without fetching metadata or executing sources`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModelStore = ViewModelStore()
        try {
            val addons = MutableStateFlow<List<Addon>>(emptyList())
            val addonRepository = mockk<AddonRepository>()
            val metaRepository = mockk<MetaRepository>()
            every { addonRepository.getInstalledAddons() } returns addons
            every { metaRepository.getCachedMeta(any(), any()) } returns null
            val serverRepository = mockk<ServerRepository> {
                every { uiState } returns MutableStateFlow(ServersUiState())
            }
            val serverStreams = mockk<ServerStreams> {
                every { canServe(any(), any()) } returns false
            }
            val viewModel = PlaybackAvailabilityViewModel(
                addonRepository,
                metaRepository,
                serverRepository,
                serverStreams
            )
            viewModelStore.put("availability", viewModel)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.availability.collect {}
            }
            runCurrent()
            assertTrue(viewModel.availability.value.isLoaded)
            assertFalse(viewModel.availability.value.canStream("movie", "tt123"))

            val addon = Addon(
                id = "addon", name = "Addon", version = "1", description = null, logo = null,
                baseUrl = "https://example.com", catalogs = emptyList(), types = emptyList(),
                resources = listOf(AddonResource("stream", listOf("movie"), null))
            )
            addons.value = listOf(addon)
            runCurrent()
            assertTrue(viewModel.availability.value.canStream("movie", "tt123"))
            addons.value = listOf(addon.copy(enabled = false))
            runCurrent()
            assertFalse(viewModel.availability.value.canStream("movie", "tt123"))


            coVerify(exactly = 0) { addonRepository.fetchAddon(any()) }
            verify(exactly = 0) { metaRepository.getMetaFromAllAddons(any(), any(), any()) }
            verify(exactly = 0) { metaRepository.getMeta(any(), any(), any()) }
        } finally {
            viewModelStore.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
