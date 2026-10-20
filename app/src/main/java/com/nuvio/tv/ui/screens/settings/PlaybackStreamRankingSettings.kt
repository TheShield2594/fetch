package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.ranking.StreamRankingRules
import com.nuvio.tv.core.streams.ranking.StreamResolution
import com.nuvio.tv.core.streams.ranking.VideoCodec
import com.nuvio.tv.data.local.PlayerSettings

private val RankingResolutionChoices = listOf(
    StreamResolution.UHD_2160,
    StreamResolution.QHD_1440,
    StreamResolution.FHD_1080,
    StreamResolution.HD_720,
    StreamResolution.SD_480
)

private val RankingSizeChoicesGb = listOf<Double?>(null, 5.0, 10.0, 15.0, 25.0, 40.0, 60.0)

private val RankingCodecChoices = listOf<VideoCodec?>(null, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.AVC)

internal val StreamRankingDialogs = setOf(
    PlaybackDialog.RANKING_PREFERRED_RESOLUTION,
    PlaybackDialog.RANKING_MAX_RESOLUTION,
    PlaybackDialog.RANKING_PREFERRED_CODEC,
    PlaybackDialog.RANKING_PREFERRED_SIZE,
    PlaybackDialog.RANKING_MAX_SIZE
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
private fun Double?.sizeLabel(): String =
    if (this == null) stringResource(R.string.ranking_no_limit) else stringResource(R.string.ranking_size_gb, this.toInt())
