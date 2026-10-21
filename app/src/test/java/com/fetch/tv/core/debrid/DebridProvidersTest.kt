package com.fetch.tv.core.debrid

import com.fetch.tv.domain.model.DebridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridProvidersTest {
    @Test
    fun `configured services follow registration order and skip providers without a key`() {
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            realDebridApiKey = "rd",
            debridLinkApiKey = "dl"
        )

        val services = DebridProviders.configuredServices(settings)

        assertEquals(
            listOf(DebridProviders.Torbox, DebridProviders.RealDebrid, DebridProviders.DebridLink),
            services.map { it.provider }
        )
        assertEquals("dl", services.last().apiKey)
    }

    @Test
    fun `every provider resolves torrent results and only some keep a cloud library`() {
        DebridProviders.all().forEach { provider ->
            assertTrue(provider.id, DebridProviders.isVisible(provider.id))
            assertTrue(provider.id, provider.supports(DebridProviderCapability.LocalTorrentResolve))
        }
        assertTrue(DebridProviders.RealDebrid.supports(DebridProviderCapability.CloudLibrary))
        assertFalse(DebridProviders.AllDebrid.supports(DebridProviderCapability.CloudLibrary))
        assertFalse(DebridProviders.DebridLink.supports(DebridProviderCapability.CloudLibrary))
        assertEquals(DebridProviderAuthMethod.DeviceCode, DebridProviders.AllDebrid.authMethod)
    }
}
