package com.fetch.tv.core.addon

import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonBehaviorHints
import com.fetch.tv.domain.repository.AddonRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonConfigurationTest {

    @Test
    fun `configure url drops manifest and query`() {
        assertEquals("https://a.example/cfg=1/configure", addonConfigureUrl("https://a.example/cfg=1/manifest.json"))
        assertEquals("https://a.example/configure", addonConfigureUrl("https://a.example/?token=x"))
        assertEquals("https://a.example/configure", addonConfigureUrl("https://a.example/"))
    }

    @Test
    fun `install urls accept stremio links and keep queries`() {
        assertEquals("https://a.example/x", normalizeAddonInstallUrl("stremio://a.example/x/manifest.json"))
        assertEquals("https://a.example?k=1", normalizeAddonInstallUrl("https://a.example/manifest.json?k=1"))
        assertNull(normalizeAddonInstallUrl("ftp://a.example"))
    }

    @Test
    fun `manifest that still needs configuration is not installed`() = runBlocking {
        val repo = FakeRepo(mapOf("https://a.example" to addon("a", "https://a.example", required = true)))
        val outcome = repo.installOrReplace("https://a.example")
        assertTrue(outcome is AddonInstallOutcome.NeedsConfiguration)
        assertTrue(repo.urls.value.isEmpty())
    }

    @Test
    fun `configured url replaces the unconfigured copy in place`() = runBlocking {
        val repo = FakeRepo(
            mapOf(
                "https://a.example" to addon("a", "https://a.example", required = true),
                "https://b.example" to addon("b", "https://b.example"),
                "https://a.example/cfg" to addon("a", "https://a.example/cfg")
            ),
            installed = listOf("https://a.example", "https://b.example")
        )
        val outcome = repo.installOrReplace("https://a.example/cfg")
        assertEquals("https://a.example", (outcome as AddonInstallOutcome.Installed).replacedUrl)
        assertEquals(listOf("https://a.example/cfg", "https://b.example"), repo.urls.value)
    }

    @Test
    fun `a second configuration of an addon is added alongside the first`() = runBlocking {
        val repo = FakeRepo(
            mapOf(
                "https://a.example/one" to addon("a", "https://a.example/one"),
                "https://a.example/two" to addon("a", "https://a.example/two")
            ),
            installed = listOf("https://a.example/one")
        )
        repo.installOrReplace("https://a.example/two")
        assertEquals(listOf("https://a.example/one", "https://a.example/two"), repo.urls.value)
    }

    @Test
    fun `explicit replacement swaps the named entry`() = runBlocking {
        val repo = FakeRepo(
            mapOf(
                "https://a.example/one" to addon("a", "https://a.example/one"),
                "https://a.example/two" to addon("a", "https://a.example/two")
            ),
            installed = listOf("https://a.example/one")
        )
        repo.installOrReplace("https://a.example/two", replacing = "https://a.example/one")
        assertEquals(listOf("https://a.example/two"), repo.urls.value)
    }

    private fun addon(id: String, url: String, required: Boolean = false) = Addon(
        id = id, name = id, displayName = id, version = "1", description = null, logo = null,
        background = null, baseUrl = url, catalogs = emptyList(), types = emptyList(),
        rawTypes = emptyList(), resources = emptyList(),
        behaviorHints = AddonBehaviorHints(configurable = true, configurationRequired = required)
    )

    private class FakeRepo(
        private val manifests: Map<String, Addon>,
        installed: List<String> = emptyList()
    ) : AddonRepository {
        val urls = MutableStateFlow(installed)
        private val addons = MutableStateFlow(installed.map { manifests.getValue(it) })

        override fun getInstalledAddons(): Flow<List<Addon>> = addons
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> =
            manifests[baseUrl]?.let { NetworkResult.Success(it) } ?: NetworkResult.Error("missing")
        override suspend fun addAddon(url: String) = setAddonOrder(urls.value + url)
        override suspend fun removeAddon(url: String) = setAddonOrder(urls.value - url)
        override suspend fun setAddonOrder(urls: List<String>) {
            this.urls.value = urls
            addons.value = urls.map { manifests.getValue(it) }
        }
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = Unit
    }
}
