package com.fetch.desktop.player

import com.fetch.desktop.AddonSubtitle
import com.fetch.desktop.data.PlayerExtras
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleOrganization
import com.fetch.desktop.player.embedded.MpvTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleExtrasTest {

    private fun props(settings: PlayerSettings, hdr: Boolean = false, lift: Int = 0) = SubtitleStyle.properties(settings, hdr, lift).toMap()

    @Test
    fun `the style becomes mpv properties`() {
        val p = props(PlayerSettings(subtitleScale = 1.2, subtitleBold = true, subtitleColor = "#FFD700", subtitleOutlineColor = "#00E5FF", subtitlePosition = 90))
        assertEquals("1.2", p["sub-scale"])
        assertEquals("yes", p["sub-bold"])
        assertEquals("#FFD700", p["sub-color"])
        assertEquals("outline-and-shadow", p["sub-border-style"])
        assertEquals("#00E5FF", p["sub-border-color"])
        assertEquals("3", p["sub-border-size"])
        assertEquals("90", p["sub-pos"])
        val box = props(PlayerSettings(subtitleBackground = true))
        assertEquals("opaque-box", box["sub-border-style"])
        assertEquals("#99000000", box["sub-back-color"])
        assertEquals("0", props(PlayerSettings(subtitleOutline = false))["sub-border-size"])
    }

    @Test
    fun `lowered opacity fades text and outline together`() {
        val color = SubtitleStyle.withOpacity("#FFFFFF", 50)
        assertEquals("#80FFFFFF", color)
        assertEquals(50, SubtitleStyle.opacityPercent(color))
        assertEquals("#80000000", props(PlayerSettings(subtitleColor = color))["sub-border-color"])
        assertEquals("a preset keeps the opacity", "#80FFD700", SubtitleStyle.keepOpacity("#FFD700", color))
        assertEquals("#FFD700", SubtitleStyle.withOpacity("#80FFD700", 100))
    }

    @Test
    fun `HDR video gets the HDR color, dimmed`() {
        val settings = PlayerSettings(
            subtitleColor = "#FFFFFF",
            extras = PlayerExtras(subtitleHdrStyle = true, subtitleHdrColor = "#FFD700", subtitleHdrBrightness = 0)
        )
        assertEquals("0 brightness is 30 %", 0.3, SubtitleStyle.hdrFactor(0), 1e-9)
        assertEquals(1.0, SubtitleStyle.hdrFactor(100), 1e-9)
        assertEquals("#FFFFFF", props(settings, hdr = false)["sub-color"])
        // 0xFF * 0.3 = 76 (0x4C), 0xD7 * 0.3 = 64 (0x40)
        assertEquals("#4C4000", props(settings, hdr = true)["sub-color"])
        assertEquals("#000000", props(settings, hdr = true)["sub-border-color"])
        assertEquals("off: HDR changes nothing", "#FFFFFF", props(settings.copy(extras = settings.extras.copy(subtitleHdrStyle = false)), hdr = true)["sub-color"])
    }

    @Test
    fun `HDR is PQ or HLG`() {
        assertTrue(SubtitleStyle.isHdr("pq", "bt.2020"))
        assertTrue(SubtitleStyle.isHdr("hlg"))
        assertFalse(SubtitleStyle.isHdr("bt.1886", "bt.709"))
        assertFalse(SubtitleStyle.isHdr(null))
    }

    @Test
    fun `subtitles lift over the controls, but not while previewing`() {
        assertEquals(17, SubtitleStyle.liftPercent(false, 1000f, true, 170f, false, 120f))
        assertEquals(12, SubtitleStyle.liftPercent(false, 1000f, false, 170f, true, 120f))
        assertEquals(0, SubtitleStyle.liftPercent(true, 1000f, true, 170f, true, 120f))
        assertEquals("83", props(PlayerSettings(), lift = 17)["sub-pos"])
    }

    private fun track(id: String, lang: String?, selected: Boolean = false, external: Boolean = false, file: String? = null, title: String? = null) =
        MpvTrack(id, "sub", title, lang, "subrip", external, selected, externalFilename = file)

    @Test
    fun `languages normalise as on TV`() {
        assertEquals("en", SubtitleOrganisation.languageKey("eng"))
        assertEquals("fr", SubtitleOrganisation.languageKey("fre"))
        assertEquals("de", SubtitleOrganisation.languageKey("de-DE"))
        assertEquals("pt-br", SubtitleOrganisation.languageKey("por", "Portuguese (Brazil)"))
        assertEquals("es-419", SubtitleOrganisation.languageKey("es-419"))
        assertEquals(SubtitleOrganisation.UNKNOWN, SubtitleOrganisation.languageKey(null))
        assertEquals("English", SubtitleOrganisation.languageLabel("en"))
        assertEquals("Portuguese (Brazil)", SubtitleOrganisation.languageLabel("pt-br"))
    }

    @Test
    fun `by language, preferred first, built-in before addons in addon order`() {
        val tracks = listOf(track("1", "ger"), track("2", "eng", selected = false), track("3", "jpn"), track("4", "eng", external = true, file = "https://b/en2.srt"))
        val addons = listOf(
            AddonSubtitle("en", "https://b/en2.srt", "B"),
            AddonSubtitle("eng", "https://a/en1.srt", "A"),
            AddonSubtitle("fr", "https://a/fr.srt", "A")
        )
        val groups = SubtitleOrganisation.organise(tracks, addons, SubtitleOrganization.BY_LANGUAGE, listOf("en", "fr"), onlyPreferred = false, addonOrder = listOf("A", "B"))
        assertEquals(listOf("en", "fr", "de", "ja"), groups.map { it.key })
        val english = groups[0].choices
        assertTrue(english[0] is SubtitleChoice.Track)
        val addonUrls = english.filterIsInstance<SubtitleChoice.Addon>().map { it.subtitle.url }
        assertEquals(listOf("https://a/en1.srt", "https://b/en2.srt"), addonUrls)
        assertEquals("an added addon file shows once, with its track", "4", english.filterIsInstance<SubtitleChoice.Addon>().last().added?.id)
        assertEquals(1, english.count { it is SubtitleChoice.Track })
    }

    @Test
    fun `only preferred languages hides the rest but keeps the playing one`() {
        val tracks = listOf(track("1", "ger", selected = true), track("2", "eng"), track("3", "jpn"))
        val groups = SubtitleOrganisation.organise(tracks, emptyList(), SubtitleOrganization.BY_LANGUAGE, listOf("en"), onlyPreferred = true)
        assertEquals(listOf("en", "de"), groups.map { it.key })
        assertEquals(1, SubtitleOrganisation.hiddenCount(tracks, emptyList(), listOf("en"), SubtitleOrganization.BY_LANGUAGE))
        val flat = SubtitleOrganisation.organise(tracks, listOf(AddonSubtitle("en", "u", "")), SubtitleOrganization.NONE, listOf("en"), onlyPreferred = false)
        assertEquals(1, flat.size)
        assertEquals(4, flat[0].choices.size)
        val bySource = SubtitleOrganisation.organise(tracks, listOf(AddonSubtitle("en", "u", "OpenSubtitles")), SubtitleOrganization.BY_ADDON, emptyList(), onlyPreferred = false)
        assertEquals(listOf("In the file", "OpenSubtitles"), bySource.map { it.label })
    }
}
