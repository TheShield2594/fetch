package com.fetch.tv.ui.screens.player

import com.fetch.tv.data.local.SubtitleMode
import com.fetch.tv.data.local.SubtitleStyleSettings
import com.fetch.tv.data.local.forcedOnlyForMatchingAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleModeTest {

    @Test
    fun defaults_areAutoWithForcedSubtitlesAndNoSdhStripping() {
        val style = SubtitleStyleSettings()
        assertEquals(SubtitleMode.AUTO, style.mode)
        assertTrue(style.useForcedSubtitles)
        assertFalse(style.stripSdh)
    }

    @Test
    fun auto_followsTheForcedSubtitlesSetting() {
        assertTrue(SubtitleStyleSettings(mode = SubtitleMode.AUTO, useForcedSubtitles = true).forcedOnlyForMatchingAudio)
        assertFalse(SubtitleStyleSettings(mode = SubtitleMode.AUTO, useForcedSubtitles = false).forcedOnlyForMatchingAudio)
    }

    @Test
    fun off_followsTheForcedSubtitlesSetting_andOnNeverIsForcedOnly() {
        assertTrue(SubtitleStyleSettings(mode = SubtitleMode.OFF, useForcedSubtitles = true).forcedOnlyForMatchingAudio)
        assertFalse(SubtitleStyleSettings(mode = SubtitleMode.OFF, useForcedSubtitles = false).forcedOnlyForMatchingAudio)
        assertFalse(SubtitleStyleSettings(mode = SubtitleMode.ON, useForcedSubtitles = true).forcedOnlyForMatchingAudio)
    }
}
