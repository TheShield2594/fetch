package com.fetch.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fetch.tv.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.ImeAction
import com.fetch.tv.core.streams.ranking.AudioFormat
import com.fetch.tv.core.streams.ranking.ReleaseSource
import com.fetch.tv.core.streams.ranking.StreamLanguage
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.screens.account.InputField
import com.fetch.tv.core.streams.ranking.StreamResolution
import com.fetch.tv.core.streams.ranking.VideoCodec
import com.fetch.tv.data.local.PlayerSettings

private val RankingResolutionChoices = listOf(
    StreamResolution.UHD_2160,
    StreamResolution.QHD_1440,
    StreamResolution.FHD_1080,
    StreamResolution.HD_720,
    StreamResolution.SD_480
)

private val RankingSizeChoicesGb = listOf<Double?>(null, 5.0, 10.0, 15.0, 25.0, 40.0, 60.0)

private val RankingCodecChoices = listOf<VideoCodec?>(null, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.AVC)

private val RankingMinSizeChoicesGb = listOf<Double?>(null, 0.5, 1.0, 2.0, 5.0, 10.0)

private val RankingLimitChoices = listOf(0, 5, 10, 20, 50)

private val RankingSourceChoices = ReleaseSource.entries.filter { it != ReleaseSource.UNKNOWN }

internal val StreamRankingDialogs = setOf(
    PlaybackDialog.RANKING_PREFERRED_RESOLUTION,
    PlaybackDialog.RANKING_MAX_RESOLUTION,
    PlaybackDialog.RANKING_PREFERRED_CODEC,
    PlaybackDialog.RANKING_PREFERRED_SIZE,
    PlaybackDialog.RANKING_MAX_SIZE,
    PlaybackDialog.RANKING_MIN_SIZE,
    PlaybackDialog.RANKING_PREFERRED_LANGUAGES,
    PlaybackDialog.RANKING_EXCLUDED_LANGUAGES,
    PlaybackDialog.RANKING_EXCLUDED_SOURCES,
    PlaybackDialog.RANKING_EXCLUDED_AUDIO,
    PlaybackDialog.RANKING_PREFERRED_GROUPS,
    PlaybackDialog.RANKING_EXCLUDED_GROUPS,
    PlaybackDialog.RANKING_MAX_RESULTS,
    PlaybackDialog.RANKING_MAX_PER_RESOLUTION
)

/** Rules for the "Best match" auto-play mode, most commonly changed first. */
@Composable
internal fun PlaybackStreamRankingRows(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit
) {
    val rules = settings.streamRankingRules
    fun update(transform: StreamRankingRules.() -> StreamRankingRules) {
        onUpdate { setStreamRankingRules(rules.transform()) }
    }

    SettingsActionRow(
        title = stringResource(R.string.ranking_preferred_quality),
        subtitle = stringResource(R.string.ranking_preferred_quality_sub),
        value = rules.preferredResolution.displayLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_PREFERRED_RESOLUTION) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_preferred_codec),
        subtitle = null,
        value = rules.preferredCodecs.firstOrNull().displayLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_PREFERRED_CODEC) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_preferred_size),
        subtitle = stringResource(R.string.ranking_preferred_size_sub),
        value = rules.preferredMaxSizeGb.sizeLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_PREFERRED_SIZE) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_preferred_languages),
        subtitle = stringResource(R.string.ranking_preferred_languages_sub),
        value = rules.preferredLanguages.joinToString(", ") { it.label }.ifEmpty { stringResource(R.string.ranking_any) },
        onClick = { onOpenDialog(PlaybackDialog.RANKING_PREFERRED_LANGUAGES) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_preferred_groups),
        subtitle = stringResource(R.string.ranking_preferred_groups_sub),
        value = rules.preferredReleaseGroups.joinToString(", ").ifEmpty { stringResource(R.string.ranking_none) },
        onClick = { onOpenDialog(PlaybackDialog.RANKING_PREFERRED_GROUPS) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.ranking_skip_dv5),
        subtitle = stringResource(R.string.ranking_skip_dv5_sub),
        checked = 5 in rules.excludedDolbyVisionProfiles,
        onToggle = {
            update {
                copy(
                    excludedDolbyVisionProfiles = if (5 in excludedDolbyVisionProfiles) {
                        excludedDolbyVisionProfiles - 5
                    } else {
                        excludedDolbyVisionProfiles + 5
                    }
                )
            }
        }
    )
    SettingsToggleRow(
        title = stringResource(R.string.ranking_prefer_cached),
        subtitle = stringResource(R.string.ranking_prefer_cached_sub),
        checked = rules.preferCachedDebrid,
        onToggle = { update { copy(preferCachedDebrid = !preferCachedDebrid) } }
    )
    SettingsToggleRow(
        title = stringResource(R.string.ranking_prefer_library),
        subtitle = stringResource(R.string.ranking_prefer_library_sub),
        checked = rules.preferOwnLibrary,
        onToggle = { update { copy(preferOwnLibrary = !preferOwnLibrary) } }
    )

    SettingsActionRow(
        title = stringResource(R.string.ranking_max_quality),
        subtitle = null,
        value = rules.maxResolution.displayLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_MAX_RESOLUTION) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_max_size),
        subtitle = stringResource(R.string.ranking_max_size_sub),
        value = rules.maxSizeGb.sizeLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_MAX_SIZE) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_min_size),
        subtitle = stringResource(R.string.ranking_min_size_sub),
        value = rules.minSizeGb.sizeLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_MIN_SIZE) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_excluded_languages),
        subtitle = stringResource(R.string.ranking_excluded_languages_sub),
        value = rules.excludedLanguages.countLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_EXCLUDED_LANGUAGES) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_excluded_sources),
        subtitle = null,
        value = rules.excludedSources.countLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_EXCLUDED_SOURCES) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_excluded_audio),
        subtitle = stringResource(R.string.ranking_excluded_audio_sub),
        value = rules.excludedAudio.countLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_EXCLUDED_AUDIO) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_excluded_groups),
        subtitle = null,
        value = rules.excludedReleaseGroups.joinToString(", ").ifEmpty { stringResource(R.string.ranking_none) },
        onClick = { onOpenDialog(PlaybackDialog.RANKING_EXCLUDED_GROUPS) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_max_results),
        subtitle = stringResource(R.string.ranking_max_results_sub),
        value = rules.maxResults.limitLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_MAX_RESULTS) }
    )
    SettingsActionRow(
        title = stringResource(R.string.ranking_max_per_resolution),
        subtitle = null,
        value = rules.maxPerResolution.limitLabel(),
        onClick = { onOpenDialog(PlaybackDialog.RANKING_MAX_PER_RESOLUTION) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.ranking_hide_cam),
        subtitle = null,
        checked = rules.excludeCamAndTelesync,
        onToggle = { update { copy(excludeCamAndTelesync = !excludeCamAndTelesync) } }
    )
    SettingsToggleRow(
        title = stringResource(R.string.ranking_drop_undecodable),
        subtitle = stringResource(R.string.ranking_drop_undecodable_sub),
        checked = rules.dropUndecodable,
        onToggle = { update { copy(dropUndecodable = !dropUndecodable) } }
    )
}

@Composable
internal fun StreamRankingSettingsDialog(
    dialog: PlaybackDialog,
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onDismiss: () -> Unit
) {
    val rules = settings.streamRankingRules
    fun save(newRules: StreamRankingRules) {
        onUpdate { setStreamRankingRules(newRules) }
        onDismiss()
    }

    when (dialog) {
        PlaybackDialog.RANKING_PREFERRED_RESOLUTION -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.ranking_preferred_quality),
            options = RankingResolutionChoices.map { SettingsPickerOption(it, it.displayLabel()) },
            selectedValue = rules.preferredResolution,
            onOptionSelected = { save(rules.copy(preferredResolution = it)) },
            onDismiss = onDismiss,
            width = 420.dp,
            maxHeight = 380.dp
        )
        PlaybackDialog.RANKING_MAX_RESOLUTION -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.ranking_max_quality),
            options = RankingResolutionChoices.map { SettingsPickerOption(it, it.displayLabel()) },
            selectedValue = rules.maxResolution,
            onOptionSelected = { save(rules.copy(maxResolution = it)) },
            onDismiss = onDismiss,
            width = 420.dp,
            maxHeight = 380.dp
        )
        PlaybackDialog.RANKING_PREFERRED_CODEC -> {
            val options = RankingCodecChoices.map { codec ->
                SettingsPickerOption(codec?.name ?: ANY, codec.displayLabel())
            }
            SettingsSingleChoiceDialog(
                title = stringResource(R.string.ranking_preferred_codec),
                options = options,
                selectedValue = rules.preferredCodecs.firstOrNull()?.name ?: ANY,
                onOptionSelected = { key ->
                    val codec = VideoCodec.entries.firstOrNull { it.name == key }
                    save(rules.copy(preferredCodecs = listOfNotNull(codec)))
                },
                onDismiss = onDismiss,
                width = 420.dp,
                maxHeight = 340.dp
            )
        }
        PlaybackDialog.RANKING_MIN_SIZE -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.ranking_min_size),
            options = RankingMinSizeChoicesGb.map { gb -> SettingsPickerOption(gb?.toString() ?: ANY, gb.sizeLabel()) },
            selectedValue = rules.minSizeGb?.toString() ?: ANY,
            onOptionSelected = { key -> save(rules.copy(minSizeGb = key.toDoubleOrNull())) },
            onDismiss = onDismiss,
            width = 420.dp,
            maxHeight = 380.dp
        )
        PlaybackDialog.RANKING_PREFERRED_LANGUAGES,
        PlaybackDialog.RANKING_EXCLUDED_LANGUAGES -> {
            val preferred = dialog == PlaybackDialog.RANKING_PREFERRED_LANGUAGES
            SettingsMultiChoiceDialog(
                title = stringResource(if (preferred) R.string.ranking_preferred_languages else R.string.ranking_excluded_languages),
                subtitle = stringResource(if (preferred) R.string.ranking_preferred_languages_sub else R.string.ranking_excluded_languages_sub),
                options = StreamLanguage.entries.map { SettingsPickerOption(it, it.label) },
                selectedValues = if (preferred) rules.preferredLanguages else rules.excludedLanguages.toList(),
                onValuesSelected = { values ->
                    save(if (preferred) rules.copy(preferredLanguages = values) else rules.copy(excludedLanguages = values.toSet()))
                },
                onDismiss = onDismiss
            )
        }
        PlaybackDialog.RANKING_EXCLUDED_SOURCES -> SettingsMultiChoiceDialog(
            title = stringResource(R.string.ranking_excluded_sources),
            options = RankingSourceChoices.map { SettingsPickerOption(it, it.label) },
            selectedValues = rules.excludedSources.toList(),
            onValuesSelected = { save(rules.copy(excludedSources = it.toSet())) },
            onDismiss = onDismiss
        )
        PlaybackDialog.RANKING_EXCLUDED_AUDIO -> SettingsMultiChoiceDialog(
            title = stringResource(R.string.ranking_excluded_audio),
            subtitle = stringResource(R.string.ranking_excluded_audio_sub),
            options = AudioFormat.entries.map { SettingsPickerOption(it, it.label) },
            selectedValues = rules.excludedAudio.toList(),
            onValuesSelected = { save(rules.copy(excludedAudio = it.toSet())) },
            onDismiss = onDismiss
        )
        PlaybackDialog.RANKING_PREFERRED_GROUPS -> RankingTextListDialog(
            title = stringResource(R.string.ranking_preferred_groups),
            values = rules.preferredReleaseGroups,
            onSave = { save(rules.copy(preferredReleaseGroups = it)) },
            onDismiss = onDismiss
        )
        PlaybackDialog.RANKING_EXCLUDED_GROUPS -> RankingTextListDialog(
            title = stringResource(R.string.ranking_excluded_groups),
            values = rules.excludedReleaseGroups,
            onSave = { save(rules.copy(excludedReleaseGroups = it)) },
            onDismiss = onDismiss
        )
        PlaybackDialog.RANKING_MAX_RESULTS,
        PlaybackDialog.RANKING_MAX_PER_RESOLUTION -> {
            val total = dialog == PlaybackDialog.RANKING_MAX_RESULTS
            SettingsSingleChoiceDialog(
                title = stringResource(if (total) R.string.ranking_max_results else R.string.ranking_max_per_resolution),
                options = RankingLimitChoices.map { SettingsPickerOption(it, it.limitLabel()) },
                selectedValue = if (total) rules.maxResults else rules.maxPerResolution,
                onOptionSelected = { save(if (total) rules.copy(maxResults = it) else rules.copy(maxPerResolution = it)) },
                onDismiss = onDismiss,
                width = 420.dp,
                maxHeight = 360.dp
            )
        }
        PlaybackDialog.RANKING_PREFERRED_SIZE,
        PlaybackDialog.RANKING_MAX_SIZE -> {
            val isCap = dialog == PlaybackDialog.RANKING_MAX_SIZE
            val current = if (isCap) rules.maxSizeGb else rules.preferredMaxSizeGb
            val options = RankingSizeChoicesGb.map { gb -> SettingsPickerOption(gb?.toString() ?: ANY, gb.sizeLabel()) }
            SettingsSingleChoiceDialog(
                title = stringResource(if (isCap) R.string.ranking_max_size else R.string.ranking_preferred_size),
                options = options,
                selectedValue = current?.toString() ?: ANY,
                onOptionSelected = { key ->
                    val gb = key.toDoubleOrNull()
                    save(if (isCap) rules.copy(maxSizeGb = gb) else rules.copy(preferredMaxSizeGb = gb))
                },
                onDismiss = onDismiss,
                width = 420.dp,
                maxHeight = 420.dp
            )
        }
        else -> Unit
    }
}

private const val ANY = "any"

@Composable
private fun StreamResolution.displayLabel(): String =
    if (this == StreamResolution.UNKNOWN) stringResource(R.string.ranking_any) else label

@Composable
private fun VideoCodec?.displayLabel(): String =
    this?.label?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.ranking_any)

@Composable
private fun Double?.sizeLabel(): String = when {
    this == null -> stringResource(R.string.ranking_no_limit)
    this < 1.0 -> "${(this * 1024).toInt()} MB"
    else -> stringResource(R.string.ranking_size_gb, this.toInt())
}

@Composable
private fun Int.limitLabel(): String =
    if (this <= 0) stringResource(R.string.ranking_no_limit) else toString()

@Composable
private fun Collection<*>.countLabel(): String =
    if (isEmpty()) stringResource(R.string.ranking_none) else pluralStringResource(R.plurals.ranking_selected_count, size, size)

/** Edits a list of names, one per line, such as release groups. */
@Composable
private fun RankingTextListDialog(
    title: String,
    values: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(values) { mutableStateOf(values.joinToString(", ")) }
    val submit = { onSave(text.split('\n', ',').map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }) }
    FetchDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.ranking_groups_input_sub),
        width = 560.dp
    ) {
        InputField(
            value = text,
            onValueChange = { text = it },
            placeholder = title,
            imeAction = ImeAction.Done,
            onImeAction = submit,
            modifier = Modifier.fillMaxWidth()
        )
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_clear), onClick = { text = "" })
            SettingsDialogActionButton(text = stringResource(R.string.action_save), onClick = submit, primary = true)
        }
    }
}
