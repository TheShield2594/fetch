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
                SettingsCategory.ANIME_SKIP,
                SettingsCategory.PLAYBACK_STREAMS,
                SettingsCategory.PLAYBACK_AUDIO_SUBTITLES,
                SettingsCategory.PLAYBACK_VIDEO,
                SettingsCategory.ADDONS,
                SettingsCategory.CONTENT_DISCOVERY,
                SettingsCategory.TMDB,
                SettingsCategory.MDBLIST,
                SettingsCategory.CUSTOMISE_HOME,
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
                SettingsCategory.ADDONS,
                SettingsCategory.CUSTOMISE_HOME,
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
    fun `youtube sits after tracking only in builds that have it`() {
        val withYouTube = visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false, isYouTubeAvailable = true)
        val withoutYouTube = visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false, isYouTubeAvailable = false)

        assertEquals(withYouTube.indexOf(SettingsCategory.TRACKING) + 1, withYouTube.indexOf(SettingsCategory.YOUTUBE))
        assertFalse(withYouTube.startsNewGroup(withYouTube.indexOf(SettingsCategory.YOUTUBE)))
        assertFalse(SettingsCategory.YOUTUBE in withoutYouTube)
    }

    @Test
    fun `advanced mode has the four playback pages, essential mode one`() {
        val advanced = visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false)
        val essential = visibleSettingsCategories(true, isEssentialMode = true, isDebugBuild = false)

        assertEquals(
            PlaybackPage.entries,
            advanced.filter { it.group == SettingsRailGroup.PLAYBACK }.mapNotNull { it.playbackPage }
        )
        assertFalse(SettingsCategory.PLAYBACK in advanced)
        assertEquals(
            listOf(SettingsCategory.PLAYBACK),
            essential.filter { it.group == SettingsRailGroup.PLAYBACK }
        )
    }

    @Test
    fun `a playback page is listed while the player settings show any of its sections`() {
        val categories = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = false,
            isDebugBuild = false,
            playbackSections = listOf(PlaybackSection.BUFFER_NETWORK, PlaybackSection.AUDIO)
        )

        assertEquals(
            listOf(PlaybackPage.PLAYER_NETWORK, PlaybackPage.AUDIO_SUBTITLES),
            categories.mapNotNull { it.playbackPage }
        )
        // Hiding Buffer & network alone leaves every page.
        assertEquals(
            PlaybackPage.entries,
            visibleSettingsCategories(
                isPrimaryProfile = true,
                isEssentialMode = false,
                isDebugBuild = false,
                playbackSections = PlaybackSection.entries - PlaybackSection.BUFFER_NETWORK
            ).mapNotNull { it.playbackPage }
        )
    }

    @Test
    fun `every playback section is on exactly one page, and every page has one rail category`() {
        assertEquals(
            PlaybackSection.entries.sorted(),
            PlaybackPage.entries.flatMap { it.sections }.sorted()
        )
        assertEquals(
            PlaybackPage.entries,
            SettingsCategory.entries.mapNotNull { it.playbackPage }
        )
    }

    @Test
    fun `advanced mode has at most 14 rail entries, debug builds included`() {
        val rail = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = false,
            isDebugBuild = true,
            isYouTubeAvailable = true
        ).settingsRailEntries()

        assertEquals(
            listOf(
                SettingsCategory.SEARCH,
                SettingsCategory.PROFILES,
                SettingsCategory.PLAYBACK_PLAYER,
                SettingsCategory.PLAYBACK_STREAMS,
                SettingsCategory.PLAYBACK_AUDIO_SUBTITLES,
                SettingsCategory.PLAYBACK_VIDEO,
                SettingsCategory.ADDONS,
                SettingsCategory.TMDB,
                SettingsCategory.CUSTOMISE_HOME,
                SettingsCategory.APPEARANCE,
                SettingsCategory.ACCOUNT,
                SettingsCategory.PHONE,
                SettingsCategory.ADVANCED,
                SettingsCategory.ABOUT
            ),
            rail
        )
        allCombinations().forEach { pages ->
            assertTrue("${pages.settingsRailEntries()}", pages.settingsRailEntries().size <= 14)
        }
    }

    @Test
    fun `essential mode's rail is shorter still`() {
        val rail = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = true,
            isDebugBuild = true,
            isYouTubeAvailable = true
        ).settingsRailEntries()

        assertEquals(
            listOf(
                SettingsCategory.SEARCH,
                SettingsCategory.PROFILES,
                SettingsCategory.PLAYBACK,
                SettingsCategory.ADDONS,
                SettingsCategory.TMDB,
                SettingsCategory.APPEARANCE,
                SettingsCategory.LAYOUT,
                SettingsCategory.ACCOUNT,
                SettingsCategory.PHONE,
                SettingsCategory.ADVANCED,
                SettingsCategory.ABOUT
            ),
            rail
        )
    }

    @Test
    fun `a tab set shares one rail entry, its first visible page, and opens on any of its pages`() {
        val advanced = visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = true, isYouTubeAvailable = true)
        val secondary = visibleSettingsCategories(false, isEssentialMode = false, isDebugBuild = false, isYouTubeAvailable = true)

        assertEquals(
            listOf(SettingsCategory.ACCOUNT, SettingsCategory.TRACKING, SettingsCategory.YOUTUBE),
            advanced.tabsOf(SettingsCategory.ACCOUNT)
        )
        assertEquals(SettingsCategory.ACCOUNT, advanced.railEntryFor(SettingsCategory.YOUTUBE))
        assertEquals(listOf(SettingsCategory.CUSTOMISE_HOME, SettingsCategory.LAYOUT), advanced.tabsOf(SettingsCategory.CUSTOMISE_HOME))
        assertEquals(listOf(SettingsCategory.ADVANCED, SettingsCategory.DEBUG), advanced.tabsOf(SettingsCategory.ADVANCED))
        assertEquals(listOf(SettingsCategory.PLAYBACK_PLAYER, SettingsCategory.ANIME_SKIP), advanced.tabsOf(SettingsCategory.PLAYBACK_PLAYER))
        // Without the account page, tracking stands for the set.
        assertEquals(SettingsCategory.TRACKING, secondary.railEntryFor(SettingsCategory.YOUTUBE))
        assertTrue(SettingsCategory.TRACKING in secondary.settingsRailEntries())
        // A page outside any set is its own entry with no other tabs.
        assertEquals(listOf(SettingsCategory.APPEARANCE), advanced.tabsOf(SettingsCategory.APPEARANCE))
        // Every visible page is reachable from exactly one rail entry.
        allCombinations().forEach { pages ->
            val rail = pages.settingsRailEntries()
            assertEquals(pages.toSet(), rail.flatMap { pages.tabsOf(it) }.toSet())
            pages.forEach { page -> assertTrue(pages.railEntryFor(page) in rail) }
        }
    }

    @Test
    fun `rail headings follow the entries`() {
        val rail = visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = true, isYouTubeAvailable = true)
            .settingsRailEntries()
        assertEquals(
            listOf(
                SettingsCategory.PROFILES,
                SettingsCategory.PLAYBACK_PLAYER,
                SettingsCategory.ADDONS,
                SettingsCategory.CUSTOMISE_HOME,
                SettingsCategory.ACCOUNT,
                SettingsCategory.PHONE
            ),
            rail.indices.filter { rail.startsNewGroup(it) }.map { rail[it] }
        )
    }

    private fun allCombinations(): List<List<SettingsCategory>> =
        listOf(true, false).flatMap { primary ->
            listOf(true, false).flatMap { essential ->
                listOf(true, false).flatMap { debug ->
                    listOf(true, false).map { youTube ->
                        visibleSettingsCategories(primary, essential, debug, isYouTubeAvailable = youTube)
                    }
                }
            }
        }

    @Test
    fun `essential mode leaves YouTube and Anime-Skip to advanced mode`() {
        val essential = visibleSettingsCategories(true, isEssentialMode = true, isDebugBuild = false, isYouTubeAvailable = true)
        val advanced = visibleSettingsCategories(true, isEssentialMode = false, isDebugBuild = false, isYouTubeAvailable = true)

        assertFalse(SettingsCategory.YOUTUBE in essential)
        assertFalse(SettingsCategory.ANIME_SKIP in essential)
        assertTrue(SettingsCategory.YOUTUBE in advanced)
        assertTrue(SettingsCategory.ANIME_SKIP in advanced)
    }
}
