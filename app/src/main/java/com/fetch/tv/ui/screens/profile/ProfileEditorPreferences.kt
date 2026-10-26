package com.fetch.tv.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.core.build.AppFeaturePolicy
import com.fetch.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.fetch.tv.data.local.AudioLanguageOption
import com.fetch.tv.data.local.PlayerSettings
import com.fetch.tv.data.local.SubtitleLanguageOption
import com.fetch.tv.data.local.displayName
import com.fetch.tv.ui.screens.settings.LanguageSelectionDialog
import com.fetch.tv.ui.screens.settings.SettingsActionRow
import com.fetch.tv.ui.screens.settings.SettingsToggleRow
import com.fetch.tv.ui.screens.settings.audioLanguageLabel
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The settings people most often want to differ between profiles, as the profile editor shows them.
 * Each one lives in that profile's own settings store, the same one its settings page writes.
 */
data class ProfileEditorPreferences(
    val audioLanguage: String,
    /** A language code, [SubtitleLanguageOption.DEVICE], or [SUBTITLES_OFF]. */
    val subtitleLanguage: String,
    val autoplayNextEpisode: Boolean,
    val autoplayHomeTrailers: Boolean
) {
    companion object {
        const val SUBTITLES_OFF = "none"

        fun from(player: PlayerSettings, autoplayHomeTrailers: Boolean) = ProfileEditorPreferences(
            audioLanguage = player.preferredAudioLanguage,
            subtitleLanguage = when {
                player.subtitleStyle.preferredLanguage == SUBTITLES_OFF -> SUBTITLES_OFF
                player.subtitleStyle.isPreferredLanguageSystemDefault -> SubtitleLanguageOption.DEVICE
                else -> player.subtitleStyle.preferredLanguage
            },
            autoplayNextEpisode = player.streamAutoPlayNextEpisodeEnabled,
            autoplayHomeTrailers = autoplayHomeTrailers
        )
    }
}

@Composable
internal fun ProfileEditorPreferencesPanel(
    preferences: ProfileEditorPreferences?,
    onPreferencesChange: (ProfileEditorPreferences) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        SettingsActionRow(
            title = stringResource(R.string.essential_audio_language),
            subtitle = null,
            value = preferences?.let { audioLanguageLabel(it.audioLanguage) },
            onClick = { showAudioDialog = true },
            enabled = preferences != null
        )
        SettingsActionRow(
            title = stringResource(R.string.essential_subtitle_language),
            subtitle = null,
            value = preferences?.let { profileSubtitleLanguageLabel(it.subtitleLanguage) },
            onClick = { showSubtitleDialog = true },
            enabled = preferences != null
        )
        SettingsToggleRow(
            title = stringResource(R.string.essential_autoplay_next_episode),
            subtitle = null,
            checked = preferences?.autoplayNextEpisode == true,
            onToggle = {
                preferences?.let { onPreferencesChange(it.copy(autoplayNextEpisode = !it.autoplayNextEpisode)) }
            },
            enabled = preferences != null
        )
        if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
            SettingsToggleRow(
                title = stringResource(R.string.profile_preferences_home_trailers),
                subtitle = stringResource(R.string.profile_preferences_home_trailers_sub),
                checked = preferences?.autoplayHomeTrailers == true,
                onToggle = {
                    preferences?.let { onPreferencesChange(it.copy(autoplayHomeTrailers = !it.autoplayHomeTrailers)) }
                },
                enabled = preferences != null
            )
        }
    }

    if (showAudioDialog && preferences != null) {
        LanguageSelectionDialog(
            title = stringResource(R.string.essential_audio_language),
            selectedLanguage = preferences.audioLanguage,
            showNoneOption = false,
            extraOptions = listOf(
                AudioLanguageOption.DEFAULT to stringResource(R.string.audio_lang_media_default),
                AudioLanguageOption.DEVICE to stringResource(R.string.audio_lang_device),
                AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)
            ),
            onLanguageSelected = { language ->
                if (language != null) onPreferencesChange(preferences.copy(audioLanguage = language))
                showAudioDialog = false
            },
            onDismiss = { showAudioDialog = false }
        )
    }
    if (showSubtitleDialog && preferences != null) {
        LanguageSelectionDialog(
            title = stringResource(R.string.essential_subtitle_language),
            selectedLanguage = preferences.subtitleLanguage.takeIf { it != ProfileEditorPreferences.SUBTITLES_OFF },
            showNoneOption = true,
            extraOptions = listOf(SubtitleLanguageOption.DEVICE to stringResource(R.string.appearance_language_system)),
            onLanguageSelected = { language ->
                onPreferencesChange(
                    preferences.copy(subtitleLanguage = language ?: ProfileEditorPreferences.SUBTITLES_OFF)
                )
                showSubtitleDialog = false
            },
            onDismiss = { showSubtitleDialog = false }
        )
    }
}

@Composable
private fun profileSubtitleLanguageLabel(language: String): String = when (language) {
    ProfileEditorPreferences.SUBTITLES_OFF -> stringResource(R.string.action_none)
    SubtitleLanguageOption.DEVICE -> stringResource(R.string.appearance_language_system)
    else -> AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == language }?.displayName
        ?: stringResource(R.string.appearance_language_system)
}
