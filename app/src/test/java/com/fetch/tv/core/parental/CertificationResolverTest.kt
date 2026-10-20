package com.fetch.tv.core.parental

import com.fetch.tv.domain.model.ContentType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CertificationResolverTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var now = 0L

    private fun source(block: suspend () -> Map<String, List<String>>?) = object : CertificationSource {
        override suspend fun certificationsByCountry(query: CertificationQuery) = block()
    }

    private fun query(id: String = "tt1", known: String? = null) =
        CertificationQuery(contentId = id, type = ContentType.MOVIE, imdbId = id, knownCertification = known)

    @Test
    fun `a rating from the source needs no lookup`() = scope.runTest {
        var lookups = 0
        val resolver = CertificationResolver(source { lookups++; null }, this) { now }

        assertEquals(CertificationLevel.TEENS, resolver.levelIfKnown(query(known = "PG-13"))?.level)
        assertEquals(0, lookups)
    }

    @Test
    fun `unknown titles are looked up once in the background and cached`() = scope.runTest {
        var lookups = 0
        val resolver = CertificationResolver(source { lookups++; mapOf("US" to listOf("R")) }, this) { now }

        assertNull(resolver.levelIfKnown(query()))
        assertNull(resolver.levelIfKnown(query()))
        advanceUntilIdle()

        assertEquals(CertificationLevel.MATURE, resolver.levelIfKnown(query())?.level)
        assertEquals(1, lookups)
        assertEquals(1L, resolver.revision.value)
    }

    @Test
    fun `failed lookups back off instead of retrying on every pass`() = scope.runTest {
        var lookups = 0
        var fail = true
        val resolver = CertificationResolver(source {
            lookups++
            if (fail) error("offline") else mapOf("US" to listOf("G"))
        }, this) { now }

        resolver.levelIfKnown(query())
        advanceUntilIdle()
        resolver.levelIfKnown(query())
        advanceUntilIdle()
        assertEquals(1, lookups)
        assertEquals(0L, resolver.revision.value)

        now += 5 * 60_000L
        fail = false
        resolver.levelIfKnown(query())
        advanceUntilIdle()
        assertEquals(2, lookups)
        assertEquals(CertificationLevel.ALL_AGES, resolver.levelIfKnown(query())?.level)
    }

    @Test
    fun `episode ids share the series rating`() {
        val episode = CertificationQuery(contentId = "tt9:1:2", type = ContentType.SERIES)
        val series = CertificationQuery(contentId = "tt9", type = ContentType.SERIES)
        assertEquals(series.cacheKey, episode.cacheKey)
    }
}
