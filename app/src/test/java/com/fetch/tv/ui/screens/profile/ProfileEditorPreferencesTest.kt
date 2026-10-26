package com.fetch.tv.ui.screens.profile

import com.fetch.tv.data.local.AudioLanguageOption
import com.fetch.tv.data.local.PlayerSettings
import com.fetch.tv.data.local.SubtitleLanguageOption
import com.fetch.tv.data.local.SubtitleStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileEditorPreferencesTest {

    @Test
    fun `reads the profile's languages and autoplay settings`() {
        val preferences = ProfileEditorPreferences.from(
            PlayerSettings(
                preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
                subtitleStyle = SubtitleStyleSettings(preferredLanguage = "fr", isPreferredLanguageSystemDefault = false),
                streamAutoPlayNextEpisodeEnabled = true
            ),
            autoplayHomeTrailers = true
        )

        assertEquals(
            ProfileEditorPreferences(
                audioLanguage = AudioLanguageOption.ORIGINAL,
                subtitleLanguage = "fr",
                autoplayNextEpisode = true,
                autoplayHomeTrailers = true
            ),
            preferences
        )
    }

    @Test
    fun `subtitles follow the device, or are off`() {
        val device = ProfileEditorPreferences.from(PlayerSettings(), autoplayHomeTrailers = false)
        assertEquals(SubtitleLanguageOption.DEVICE, device.subtitleLanguage)

        val off = ProfileEditorPreferences.from(
            PlayerSettings(subtitleStyle = SubtitleStyleSettings(preferredLanguage = "none")),
            autoplayHomeTrailers = false
        )
        assertEquals(ProfileEditorPreferences.SUBTITLES_OFF, off.subtitleLanguage)
    }
}
