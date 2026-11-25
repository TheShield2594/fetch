package com.fetch.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoredNextUpValidationTest {

    @Test
    fun `a restored show stays eligible even after the badge pipeline validates it again`() {
        val deadlines = mutableSetOf<String>()
        val isFresh: (String) -> Boolean = { it in deadlines }
        val restored = mutableSetOf("show")

        // Restore cleared the deadline, then the badge pipeline set a fresh one.
        deadlines += "show"

        assertFalse(olderSeedValidationFresh("show", restored, isFresh))

        // Once the older-seed pass has tried it, the normal skip applies again.
        restored -= "show"
        assertTrue(olderSeedValidationFresh("show", restored, isFresh))
    }

    @Test
    fun `other shows keep their freshness skip`() {
        assertTrue(olderSeedValidationFresh("other", setOf("show")) { true })
        assertFalse(olderSeedValidationFresh("other", setOf("show")) { false })
    }
}
