package com.fetch.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {

    @Test
    fun `primary profile in advanced debug build sees every category in group order`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = false,
            isDebugBuild = true
        )

        assertEquals(
            listOf(
                SettingsCategory.SEARCH,
                SettingsCategory.PROFILES,
                SettingsCategory.PLAYBACK_PLAYER,
                SettingsCategory.PLAYBACK_STREAM_SELECTION,
                SettingsCategory.PLAYBACK_UP_NEXT,
                SettingsCategory.PLAYBACK_SKIP_SEGMENTS,
                SettingsCategory.PLAYBACK_PLAYER_INTERFACE,
                SettingsCategory.PLAYBACK_AUDIO,
                SettingsCategory.PLAYBACK_SUBTITLES,
                SettingsCategory.PLAYBACK_VIDEO,
                SettingsCategory.PLAYBACK_BUFFER_NETWORK,
                SettingsCategory.PLAYBACK_P2P,
                SettingsCategory.ANIME_SKIP,
                SettingsCategory.CONTENT_DISCOVERY,
                SettingsCategory.TMDB,
                SettingsCategory.MDBLIST,
                SettingsCategory.APPEARANCE,
                SettingsCategory.LAYOUT,
                SettingsCategory.ACCOUNT,
                SettingsCategory.TRACKING,
                SettingsCategory.PHONE,
                SettingsCategory.ADVANCED,
                SettingsCategory.ABOUT,
                SettingsCategory.DEBUG
            ),
            categories
        )
    }

    @Test
    fun `secondary profile does not see account or profiles`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = false,
            isEssentialMode = false,
            isDebugBuild = false
        )

        assertFalse(SettingsCategory.ACCOUNT in categories)
        assertFalse(SettingsCategory.PROFILES in categories)
        assertEquals(listOf(SettingsCategory.SEARCH, SettingsCategory.PLAYBACK_PLAYER), categories.take(2))
    }

    @Test
    fun `debug is hidden in release builds and in essential mode`() {
        assertFalse(SettingsCategory.DEBUG in visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false))
        assertFalse(SettingsCategory.DEBUG in visibleSettingsCategories(true, isEssentialMode = true, isDebugBuild = true))
    }

    @Test
    fun `experience never appears in the rail`() {
        allCombinations().forEach { categories ->
            assertFalse(SettingsCategory.EXPERIENCE in categories)
        }
    }

    @Test
    fun `about is the last regular category`() {
        allCombinations().forEach { categories ->
            val withoutDebug = categories - SettingsCategory.DEBUG
            assertEquals(SettingsCategory.ABOUT, withoutDebug.last())
        }
    }

    @Test
    fun `each group is contiguous in every configuration`() {
        allCombinations().forEach { categories ->
            val groupRuns = categories.mapNotNull { it.group }.fold(mutableListOf<SettingsRailGroup>()) { runs, group ->
                if (runs.lastOrNull() != group) runs.add(group)
                runs
            }
            assertEquals(groupRuns.distinct(), groupRuns)
        }
    }

    @Test
    fun `there are six categories, in the design's order`() {
        assertEquals(
            listOf(
                SettingsRailGroup.PROFILES,
                SettingsRailGroup.PLAYBACK,
                SettingsRailGroup.SOURCES,
                SettingsRailGroup.HOME,
                SettingsRailGroup.ACCOUNTS,
                SettingsRailGroup.SYSTEM
            ),
            SettingsCategory.entries.mapNotNull { it.group }.distinct()
        )
    }

    @Test
    fun `search leads the rail in every configuration`() {
        allCombinations().forEach { categories ->
            assertEquals(SettingsCategory.SEARCH, categories.first())
        }
    }

    @Test
    fun `headings are placed only where the category changes`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = false,
            isDebugBuild = true
        )
        val headingBefore = categories.indices.filter { categories.startsNewGroup(it) }.map { categories[it] }

        assertEquals(
            listOf(
                SettingsCategory.PROFILES,
                SettingsCategory.PLAYBACK_PLAYER,
                SettingsCategory.CONTENT_DISCOVERY,
                SettingsCategory.APPEARANCE,
                SettingsCategory.ACCOUNT,
                SettingsCategory.PHONE
            ),
            headingBefore
        )
        assertFalse(categories.startsNewGroup(0))
    }

    @Test
    fun `secondary profile rail starts with the playback heading after search`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = false,
            isEssentialMode = true,
            isDebugBuild = false
        )
        assertFalse(categories.startsNewGroup(0))
        assertTrue(categories.startsNewGroup(categories.indexOf(SettingsCategory.PLAYBACK)))
        assertTrue(categories.startsNewGroup(categories.indexOf(SettingsCategory.TRACKING)))
    }

    @Test
    fun `watch party shows for every profile only while available`() {
        listOf(true, false).forEach { primary ->
            val available = visibleSettingsCategories(primary, isEssentialMode = false, isDebugBuild = false, isWatchPartyAvailable = true)
            val unavailable = visibleSettingsCategories(primary, isEssentialMode = false, isDebugBuild = false, isWatchPartyAvailable = false)
            assertTrue(SettingsCategory.WATCH_PARTY in available)
            assertFalse(SettingsCategory.WATCH_PARTY in unavailable)
        }
        assertEquals(
            listOf(SettingsCategory.SEARCH, SettingsCategory.PROFILES, SettingsCategory.WATCH_PARTY),
            visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false, isWatchPartyAvailable = true).take(3)
        )
    }

    @Test
    fun `youtube sits after tracking only in builds that have it`() {
        val withYouTube = visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false, isYouTubeAvailable = true)
        val withoutYouTube = visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false, isYouTubeAvailable = false)

        assertEquals(withYouTube.indexOf(SettingsCategory.TRACKING) + 1, withYouTube.indexOf(SettingsCategory.YOUTUBE))
        assertFalse(withYouTube.startsNewGroup(withYouTube.indexOf(SettingsCategory.YOUTUBE)))
        assertFalse(SettingsCategory.YOUTUBE in withoutYouTube)
    }

    @Test
    fun `advanced mode lists each playback section in the rail, essential mode one playback page`() {
        val advanced = visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false)
        val essential = visibleSettingsCategories(true, isEssentialMode = true, isDebugBuild = false)

        assertEquals(
            PlaybackSection.entries,
            advanced.filter { it.group == SettingsRailGroup.PLAYBACK }.mapNotNull { it.playbackSection }
        )
        assertFalse(SettingsCategory.PLAYBACK in advanced)
        assertEquals(
            listOf(SettingsCategory.PLAYBACK, SettingsCategory.ANIME_SKIP),
            essential.filter { it.group == SettingsRailGroup.PLAYBACK }
        )
    }

    @Test
    fun `the rail only lists the playback sections the player settings show`() {
        val sections = PlaybackSection.entries - PlaybackSection.BUFFER_NETWORK - PlaybackSection.P2P
        val categories = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = false,
            isDebugBuild = false,
            playbackSections = sections
        )

        assertEquals(sections, categories.mapNotNull { it.playbackSection })
    }

    @Test
    fun `every playback section has exactly one rail entry`() {
        assertEquals(
            PlaybackSection.entries,
            SettingsCategory.entries.mapNotNull { it.playbackSection }
        )
    }

    private fun allCombinations(): List<List<SettingsCategory>> =
        listOf(true, false).flatMap { primary ->
            listOf(true, false).flatMap { essential ->
                listOf(true, false).flatMap { debug ->
                    listOf(true, false).flatMap { watchParty ->
                        listOf(true, false).map { youTube ->
                            visibleSettingsCategories(primary, essential, debug, watchParty, youTube)
                        }
                    }
                }
            }
        }
}
