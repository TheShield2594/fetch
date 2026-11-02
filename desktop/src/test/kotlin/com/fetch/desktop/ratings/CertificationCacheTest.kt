package com.fetch.desktop.ratings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CertificationCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `round trips ratings and none markers through the file`() {
        val file = tmp.root.resolve("sub/ratings.json")
        val now = 1_000_000L
        CertificationCache(file, clock = { now }).apply {
            put("movie", "tt1", Certification("PG-13", "US"))
            put("series", "tt2:1:3", null)
            save()
        }
        assertFalse(tmp.root.resolve("sub/ratings.json.tmp").exists())

        val reloaded = CertificationCache(file, clock = { now + 1 })
        assertEquals(Certification("PG-13", "US"), reloaded.get("movie", "tt1")!!.certification)
        val none = reloaded.get("tv", "tt2")
        assertNotNull(none)
        assertNull(none!!.certification)
        assertNull(reloaded.get("series", "tt1")) // keyed by type
        assertNull(reloaded.get("movie", "tt9"))
    }

    @Test
    fun `entries expire after the ttl and are dropped on save`() {
        val file = tmp.root.resolve("ratings.json")
        var now = 0L
        val cache = CertificationCache(file, ttlMs = 100, clock = { now })
        cache.put("movie", "tt1", Certification("R", "US"))
        now = 50
        cache.put("movie", "tt2", null)
        now = 99
        assertNotNull(cache.get("movie", "tt1"))
        now = 100
        assertNull(cache.get("movie", "tt1"))
        assertNotNull(cache.get("movie", "tt2"))
        cache.save()
        assertFalse(file.readText().contains("tt1"))
    }

    @Test
    fun `corrupt file reads as empty`() {
        val file = tmp.root.resolve("ratings.json").apply { writeText("not json") }
        assertNull(CertificationCache(file).get("movie", "tt1"))
    }
}
