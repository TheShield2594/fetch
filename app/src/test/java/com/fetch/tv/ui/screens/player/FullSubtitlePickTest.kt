package com.fetch.tv.ui.screens.player

import com.fetch.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullSubtitlePickTest {

    private fun sub(id: String, lang: String) =
        Subtitle(id = id, url = "https://subs.example/$id.srt", lang = lang, addonName = "Subs", addonLogo = null)

    @Test
    fun forcedAddonListedFirst_isSkippedForTheFullOne() {
        val forced = sub("en-forced", "en")
        val full = sub("en-full", "en")
        assertEquals(full, listOf(forced, full).firstFullSubtitleFor("en"))
    }

    @Test
    fun onlyAForcedAddonInTheLanguage_givesNoFullPick() {
        assertNull(listOf(sub("en-forced", "en"), sub("fr-full", "fr")).firstFullSubtitleFor("en"))
    }

    @Test
    fun forcedMarkerInTheUrlOrAddonName_alsoCounts() {
        val byUrl = Subtitle(id = "a", url = "https://x/forced/a.srt", lang = "en", addonName = "Subs", addonLogo = null)
        val byAddon = Subtitle(id = "b", url = "https://x/b.srt", lang = "en", addonName = "Forced Subs", addonLogo = null)
        val full = sub("c", "en")
        assertEquals(full, listOf(byUrl, byAddon, full).firstFullSubtitleFor("en"))
    }
}
