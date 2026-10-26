package com.fetch.tv.ui.screens.settings

import com.fetch.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SettingsEssentialIndexTest {

    private val stringIds = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    @Test
    fun `essential search covers exactly the pages essential mode shows`() {
        val essentialPages = visibleSettingsCategories(
            isPrimaryProfile = true,
            isEssentialMode = true,
            isDebugBuild = false,
            isWatchPartyAvailable = true,
            isYouTubeAvailable = true
        ).toSet() - SettingsCategory.SEARCH

        assertEquals(essentialPages, essentialSettingsSearchIndex.keys)
    }

    @Test
    fun `every essential entry is a row its page has in advanced mode too`() {
        val problems = essentialSettingsSearchIndex.flatMap { (category, titles) ->
            titles.filterNot { it in settingsSearchIndex[category].orEmpty() }
                .map { "$category lists R.string.${stringIds[it]}, which its page doesn't have" }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
