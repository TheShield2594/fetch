package com.fetch.desktop.data

import com.fetch.desktop.yearOf
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an old settings file turns prefer my own library on once`() {
        // What desktop saved before media servers: no schema, own library off.
        val old = json.decodeFromString(DesktopSettings.serializer(), """{"rankingRules":{"preferOwnLibrary":false,"dropUndecodable":false}}""")
        val migrated = old.migrated()
        assertTrue(migrated.rankingRules.preferOwnLibrary)
        assertEquals(2, migrated.schema)
        // After that, turning it off sticks.
        val off = migrated.copy(rankingRules = migrated.rankingRules.copy(preferOwnLibrary = false))
        assertFalse(off.migrated().rankingRules.preferOwnLibrary)
    }

    @Test
    fun `new installs prefer their own library and never drop streams as undecodable`() {
        assertTrue(DesktopDefaults.rankingRules.preferOwnLibrary)
        assertFalse(DesktopDefaults.rankingRules.dropUndecodable)
        assertEquals(StreamRankingRules().copy(dropUndecodable = false), DesktopDefaults.rankingRules)
    }

    @Test
    fun `new installs play in mpv's own window, and a saved built-in player choice is kept`() {
        val fresh = DesktopSettings().migrated()
        assertFalse(fresh.player.builtInPlayer)
        assertFalse(fresh.player.askPlayer)
        val saved = json.decodeFromString(DesktopSettings.serializer(), """{"player":{"builtInPlayer":true}}""").migrated()
        assertTrue(saved.player.builtInPlayer)
        val asking = json.decodeFromString(DesktopSettings.serializer(), """{"player":{"askPlayer":true}}""").migrated()
        assertTrue(asking.player.askPlayer)
    }

    @Test
    fun `the year comes from the start of a release line`() {
        assertEquals(2008, yearOf("2008–2013"))
        assertEquals(null, yearOf("TBA"))
    }
}
