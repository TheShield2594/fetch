package com.fetch.tv.core.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonUrlsTest {

    @Test
    fun `resource paths go before the base url's query string`() {
        val base = "https://addon.example/cfg/?token=abc"
        assertEquals("https://addon.example/cfg/manifest.json?token=abc", AddonUrls.manifest(base))
        assertEquals("https://addon.example/cfg/meta/series/tt1.json?token=abc", AddonUrls.meta(base, "series", "tt1"))
        assertEquals("https://addon.example/cfg/stream/series/tt1%3A1%3A2.json?token=abc", AddonUrls.stream(base, "series", "tt1:1:2"))
    }

    @Test
    fun `catalog urls carry skip and extras`() {
        val base = "https://addon.example/"
        assertEquals("https://addon.example/catalog/movie/top.json", AddonUrls.catalog(base, "movie", "top"))
        assertEquals("https://addon.example/catalog/movie/top/skip=100.json", AddonUrls.catalog(base, "movie", "top", skip = 100))
        assertEquals(
            "https://addon.example/catalog/movie/top/genre=Science%20Fiction&skip=50.json",
            AddonUrls.catalog(base, "movie", "top", skip = 50, extraArgs = mapOf("genre" to "Science Fiction"))
        )
    }

    @Test
    fun `install links normalize to an http base url`() {
        assertEquals("https://addon.example/x", normalizeAddonInstallUrl(" stremio://addon.example/x/manifest.json "))
        assertEquals("https://addon.example/x?cfg=1", normalizeAddonInstallUrl("https://addon.example/x/manifest.json?cfg=1"))
        assertNull(normalizeAddonInstallUrl("addon.example/manifest.json"))
    }
}
