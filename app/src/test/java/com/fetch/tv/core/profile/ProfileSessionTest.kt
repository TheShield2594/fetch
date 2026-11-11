package com.fetch.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ProfileSessionTest {

    private class Counter {
        var value = 0
    }

    private val counterKey = ProfileSession.Key<Counter>("counter")

    @Test
    fun `a cache is created once per session`() {
        val session = ProfileSession(profileId = 2)
        var created = 0
        val first = session.cache(counterKey) { created++; Counter() }
        val second = session.cache(counterKey) { created++; Counter() }

        assertSame(first, second)
        assertEquals(1, created)
    }

    @Test
    fun `a new session starts with empty caches`() {
        val before = ProfileSession(profileId = 2)
        before.cache(counterKey) { Counter() }.value = 5

        val after = ProfileSession(profileId = 3)
        val fresh = after.cache(counterKey) { Counter() }

        assertNotSame(before.cache(counterKey) { Counter() }, fresh)
        assertEquals(0, fresh.value)
        assertEquals(3, after.profileId)
    }
}
