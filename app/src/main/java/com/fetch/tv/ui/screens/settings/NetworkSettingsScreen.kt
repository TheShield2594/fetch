@file:OptIn(ExperimentalTvMaterial3Api::class)


package com.fetch.tv.ui.screens.settings

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.local.AddonTimeoutPreferences
import com.fetch.tv.data.local.Dv7HandlingMode
import com.fetch.tv.data.local.InternalPlayerEngine
import com.fetch.tv.domain.model.ExperienceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ClearCwCacheEntryPoint {
    fun cwEnrichmentCache(): com.fetch.tv.data.local.ContinueWatchingEnrichmentCache
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ProfileManagerEntryPoint {
    fun profileManager(): com.fetch.tv.core.profile.ProfileManager
}

/** "Confirm before exiting", shared by the Advanced page in both experience modes. */
@Composable
internal fun ConfirmExitToggleRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileManager = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProfileManagerEntryPoint::class.java
        ).profileManager()
    }
    val confirmExitEnabled by profileManager.confirmExitEnabled.collectAsState()
    SettingsToggleRow(
        title = stringResource(R.string.advanced_confirm_exit),
        subtitle = stringResource(R.string.advanced_confirm_exit_subtitle),
        checked = confirmExitEnabled,
        onToggle = {
            scope.launch {
                profileManager.setConfirmExitEnabled(!confirmExitEnabled)
            }
        }
    )
}


@Composable
fun AdvancedSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: AdvancedSettingsViewModel = hiltViewModel(),
    experienceModeViewModel: ExperienceModeSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.error_unknown)

    // DV Diagnostics: reuse the playback settings store for the conversion-mode
    // override and the last-playback diagnostics card.
    val playbackVm: PlaybackSettingsViewModel = hiltViewModel()
    val dvPlayerSettings by playbackVm.playerSettings.collectAsStateWithLifecycle(
        initialValue = com.fetch.tv.data.local.PlayerSettings()
    )
    val dvDiagnostics by playbackVm.lastPlaybackDiagnostics.collectAsStateWithLifecycle(
        initialValue = com.fetch.tv.core.player.LastPlaybackDiagnostics.EMPTY
    )

    // Stream Speed Test States
    var streamTestState by remember { mutableStateOf("Idle") }
    var streamBaselineSpeed by remember { mutableStateOf<Double?>(null) }
    var streamParallel1Speed by remember { mutableStateOf<Double?>(null) }
    var streamParallel4Speed by remember { mutableStateOf<Double?>(null) }
    var streamParallel8Speed by remember { mutableStateOf<Double?>(null) }
    var streamParallel16Speed by remember { mutableStateOf<Double?>(null) }
    var streamErrorMessage by remember { mutableStateOf<String?>(null) }

    val lastStreamUrl = dvDiagnostics.streamUrl
    val lastHeadersJson = dvDiagnostics.headersJson

    val lastHeadersMap = remember(lastHeadersJson) {
        if (!lastHeadersJson.isNullOrBlank()) {
            runCatching {
                val json = org.json.JSONObject(lastHeadersJson)
                val map = mutableMapOf<String, String>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = json.getString(key)
                }
                map
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
    }

    var estimatedBitrate by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(dvDiagnostics) {
        val formatBitrate = dvDiagnostics.videoBitrate.takeIf { it > 0 }?.toLong()
        if (formatBitrate != null) {
            estimatedBitrate = formatBitrate
        } else if (!lastStreamUrl.isNullOrBlank() && dvDiagnostics.durationMs > 0) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val size = com.fetch.tv.core.network.StreamSpeedTester.getStreamContentLength(lastStreamUrl, lastHeadersMap)
                if (size > 0) {
                    val durationSecs = dvDiagnostics.durationMs / 1000.0
                    if (durationSecs > 0) {
                        estimatedBitrate = ((size * 8.0) / durationSecs).toLong()
                    }
                }
            }
        } else {
            estimatedBitrate = null
        }
    }

    fun runStreamDiagnostics() {
        if (lastStreamUrl.isNullOrBlank()) return
        scope.launch {
            streamBaselineSpeed = null
            streamParallel1Speed = null
            streamParallel4Speed = null
            streamParallel8Speed = null
            streamParallel16Speed = null
            streamErrorMessage = null

            try {
                streamTestState = "Baseline"
                val baseline = com.fetch.tv.core.network.StreamSpeedTester.runBaselineTest(
                    lastStreamUrl,
                    lastHeadersMap
                )
                streamBaselineSpeed = baseline

                if (baseline <= 0.0) {
                    streamErrorMessage = resources.getString(R.string.stream_test_error_connection)
                    streamTestState = "Error"
                    return@launch
                }

                val measureChunkBytes =
                    com.fetch.tv.data.local.PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024L

                streamTestState = "Parallel1"
                streamParallel1Speed = com.fetch.tv.core.network.StreamSpeedTester.runParallelChunkTest(
                    lastStreamUrl,
                    lastHeadersMap,
                    chunkSizeBytes = measureChunkBytes,
                    parallelConnections = 1
                ).mbps

                streamTestState = "Parallel4"
                streamParallel4Speed = com.fetch.tv.core.network.StreamSpeedTester.runParallelChunkTest(
                    lastStreamUrl,
                    lastHeadersMap,
                    chunkSizeBytes = measureChunkBytes,
                    parallelConnections = 4
                ).mbps

                streamTestState = "Parallel8"
                streamParallel8Speed = com.fetch.tv.core.network.StreamSpeedTester.runParallelChunkTest(
                    lastStreamUrl,
                    lastHeadersMap,
                    chunkSizeBytes = measureChunkBytes,
                    parallelConnections = 8
                ).mbps

                streamTestState = "Parallel16"
                streamParallel16Speed = com.fetch.tv.core.network.StreamSpeedTester.runParallelChunkTest(
                    lastStreamUrl,
                    lastHeadersMap,
                    chunkSizeBytes = measureChunkBytes,
                    parallelConnections = 16
                ).mbps

                streamTestState = "Done"
            } catch (e: java.lang.Exception) {
                streamErrorMessage = e.localizedMessage ?: unknownError
                streamTestState = "Error"
            }
        }
    }

    val networkListState = rememberLazyListState()
    SettingsRowTargetScroller(networkListState)
    var showExperienceModeConfirmation by remember { mutableStateOf(false) }
    var showAddonTimeoutDialog by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = networkListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SettingsDetailHeader(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.settings_advanced),
                    subtitle = stringResource(R.string.settings_advanced_subtitle)
                )
            }
        }

        item(key = "experience_mode_settings") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.experience_mode_group_title)
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.experience_mode_switch_to_essential),
                    subtitle = stringResource(R.string.experience_mode_switch_to_essential_subtitle),
                    value = stringResource(R.string.experience_mode_advanced),
                    onClick = { showExperienceModeConfirmation = true },
                    modifier = if (initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }

        item(key = "performance_settings") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.advanced_section_performance)
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_fast_horizontal_navigation),
                    subtitle = stringResource(R.string.advanced_fast_horizontal_navigation_subtitle),
                    checked = uiState.fastHorizontalNavigationEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetFastHorizontalNavigationEnabled(
                                !uiState.fastHorizontalNavigationEnabled
                            )
                        )
                    }
                )
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_fetch_focus_scroll),
                    subtitle = stringResource(R.string.advanced_fetch_focus_scroll_subtitle),
                    checked = uiState.smoothBringIntoViewEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetSmoothBringIntoViewEnabled(
                                !uiState.smoothBringIntoViewEnabled
                            )
                        )
                    }
                )
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_rgb565),
                    subtitle = stringResource(R.string.advanced_rgb565_subtitle),
                    checked = uiState.rgb565Enabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetRgb565Enabled(
                                !uiState.rgb565Enabled
                            )
                        )
                    }
                )
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_hardware_bitmaps),
                    subtitle = stringResource(R.string.advanced_hardware_bitmaps_subtitle),
                    checked = uiState.hardwareBitmapsEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetHardwareBitmapsEnabled(
                                !uiState.hardwareBitmapsEnabled
                            )
                        )
                    }
                )
            }
        }

        item(key = "addon_request_settings") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.advanced_section_addons)
            ) {
                val fastSeconds = AddonTimeoutPreferences.FAST_TIMEOUT_SECONDS
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_skip_slow_addons),
                    subtitle = stringResource(R.string.advanced_skip_slow_addons_subtitle, fastSeconds),
                    checked = uiState.skipSlowAddons,
                    onToggle = {
                        viewModel.onEvent(AdvancedSettingsEvent.SetSkipSlowAddons(!uiState.skipSlowAddons))
                    }
                )
                SettingsActionRow(
                    title = stringResource(R.string.advanced_addon_timeout),
                    subtitle = stringResource(R.string.advanced_addon_timeout_subtitle),
                    value = stringResource(
                        R.string.advanced_addon_timeout_value,
                        if (uiState.skipSlowAddons) fastSeconds else uiState.addonTimeoutSeconds
                    ),
                    enabled = !uiState.skipSlowAddons,
                    onClick = { showAddonTimeoutDialog = true }
                )
                if (uiState.skipSlowAddons) {
                    SettingsNote(text = stringResource(R.string.advanced_addon_timeout_disabled_note))
                }
            }
        }

        item(key = "startup_settings") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.advanced_section_startup)
            ) {
                val profileManager = remember {
                    dagger.hilt.android.EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        ProfileManagerEntryPoint::class.java
                    ).profileManager()
                }
                val startupSplashEnabled by profileManager.startupSplashEnabled.collectAsState()
                SettingsToggleRow(
                    title = stringResource(R.string.appearance_startup_splash),
                    subtitle = stringResource(R.string.appearance_startup_splash_subtitle),
                    checked = startupSplashEnabled,
                    onToggle = {
                        scope.launch {
                            profileManager.setStartupSplashEnabled(!startupSplashEnabled)
                        }
                    }
                )

                ConfirmExitToggleRow()
            }
        }

        item(key = "diagnostics") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.advanced_section_diagnostics)
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_player_stats_hud),
                    subtitle = stringResource(R.string.advanced_player_stats_hud_subtitle),
                    checked = uiState.playerStatsHudEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetPlayerStatsHudEnabled(
                                !uiState.playerStatsHudEnabled
                            )
                        )
                    }
                )
            }
        }

        item(key = "network_tests") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.advanced_section_network_tests)
            ) {
                val isStreamRunning = streamTestState != "Idle" && streamTestState != "Done" && streamTestState != "Error"
                val hasStream = !lastStreamUrl.isNullOrBlank()
                SettingsActionRow(
                    title = stringResource(
                        if (isStreamRunning) R.string.stream_test_btn_running
                        else R.string.stream_test_card_title
                    ),
                    subtitle = if (hasStream) {
                        stringResource(R.string.stream_test_server_label, lastStreamUrl.let { android.net.Uri.parse(it).host } ?: stringResource(R.string.stream_quality_unknown))
                    } else {
                        stringResource(R.string.stream_test_no_stream)
                    },
                    value = if (isStreamRunning) {
                        when (streamTestState) {
                            "Baseline" -> stringResource(R.string.stream_test_btn_measuring_baseline)
                            "Parallel1" -> stringResource(R.string.stream_test_btn_measuring_parallel1)
                            "Parallel4" -> stringResource(R.string.stream_test_btn_measuring_parallel4)
                            "Parallel8" -> stringResource(R.string.stream_test_btn_measuring_parallel8)
                            "Parallel16" -> stringResource(R.string.stream_test_btn_measuring_parallel16)
                            else -> stringResource(R.string.stream_test_btn_running)
                        }
                    } else null,
                    enabled = hasStream && !isStreamRunning,
                    onClick = { if (hasStream && !isStreamRunning) runStreamDiagnostics() }
                )
            }
        }

        if (streamTestState != "Idle") {
            item(key = "stream_speed_results") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(FetchTheme.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.stream_test_section_header),
                                style = MaterialTheme.typography.titleSmall,
                                color = FetchTheme.colors.TextSecondary
                            )

                            val bitrateMbps = estimatedBitrate?.takeIf { it > 0 }?.let { it.toDouble() / 1_000_000.0 }
                            if (bitrateMbps != null) {
                                Text(
                                    text = stringResource(R.string.stream_test_video_bitrate, "%.1f Mbps".format(bitrateMbps)),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = FetchTheme.colors.TextPrimary
                                )
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
                        ) {
                            StreamTestResultRow(
                                label = stringResource(R.string.stream_test_label_baseline),
                                speed = streamBaselineSpeed,
                                isRunning = streamTestState == "Baseline"
                            )
                            StreamTestResultRow(
                                label = stringResource(R.string.stream_test_label_parallel1),
                                speed = streamParallel1Speed,
                                isRunning = streamTestState == "Parallel1"
                            )
                            StreamTestResultRow(
                                label = stringResource(R.string.stream_test_label_parallel4),
                                speed = streamParallel4Speed,
                                isRunning = streamTestState == "Parallel4"
                            )
                            StreamTestResultRow(
                                label = stringResource(R.string.stream_test_label_parallel8),
                                speed = streamParallel8Speed,
                                isRunning = streamTestState == "Parallel8"
                            )
                            StreamTestResultRow(
                                label = stringResource(R.string.stream_test_label_parallel16),
                                speed = streamParallel16Speed,
                                isRunning = streamTestState == "Parallel16"
                            )

                            if (streamTestState == "Error" && streamErrorMessage != null) {
                                Text(
                                    text = stringResource(R.string.stream_test_error_prefix, streamErrorMessage!!),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FetchTheme.colors.Error
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "skip_markers") {
            SkipMarkerSettingsGroup()
        }

        item(key = "clear_cw_cache") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.advanced_section_cache)
            ) {
                var cleared by remember { mutableStateOf(false) }
                SettingsActionRow(
                    title = stringResource(R.string.advanced_clear_cw_cache),
                    subtitle = if (cleared) {
                        stringResource(R.string.advanced_clear_cw_cache_done)
                    } else {
                        stringResource(R.string.advanced_clear_cw_cache_subtitle)
                    },
                    onClick = {
                        if (!cleared) {
                            scope.launch {
                                val entryPoint = dagger.hilt.android.EntryPointAccessors
                                    .fromApplication(
                                        context.applicationContext,
                                        ClearCwCacheEntryPoint::class.java
                                    )
                                entryPoint.cwEnrichmentCache().clearAll()
                                cleared = true
                            }
                        }
                    }
                )
            }
        }

        if (dvPlayerSettings.internalPlayerEngine == InternalPlayerEngine.EXOPLAYER ||
            dvPlayerSettings.internalPlayerEngine == InternalPlayerEngine.AUTO) {
            item(key = "dv_conversion_mode") {
                val overrideEnabled = dvPlayerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
                var showModeDialog by remember { mutableStateOf(false) }
                val modeOptions = listOf(
                    SettingsPickerOption(
                        -1,
                        stringResource(R.string.dv7_libdovi_mode_none_title),
                        stringResource(R.string.dv7_libdovi_mode_none_sub)
                    ),
                    SettingsPickerOption(0, stringResource(R.string.dv7_libdovi_mode_0_title), stringResource(R.string.dv7_libdovi_mode_0_sub)),
                    SettingsPickerOption(1, stringResource(R.string.dv7_libdovi_mode_1_title), stringResource(R.string.dv7_libdovi_mode_1_sub)),
                    SettingsPickerOption(2, stringResource(R.string.dv7_libdovi_mode_2_title), stringResource(R.string.dv7_libdovi_mode_2_sub)),
                    SettingsPickerOption(3, stringResource(R.string.dv7_libdovi_mode_3_title), stringResource(R.string.dv7_libdovi_mode_3_sub)),
                    SettingsPickerOption(4, stringResource(R.string.dv7_libdovi_mode_4_title), stringResource(R.string.dv7_libdovi_mode_4_sub))
                )
                val effectiveOverride = if (overrideEnabled) dvPlayerSettings.dv7LibdoviModeOverride else -1
                val currentLabel = modeOptions.firstOrNull { it.value == effectiveOverride }?.title
                    ?: modeOptions.first().title
                SettingsGroupCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.advanced_section_dv_diagnostics)
                ) {
                    SettingsActionRow(
                        title = stringResource(R.string.dv7_libdovi_mode_row_title),
                        subtitle = stringResource(R.string.dv7_libdovi_mode_caption),
                        value = currentLabel,
                        onClick = { showModeDialog = true },
                        enabled = overrideEnabled
                    )
                }
                if (showModeDialog) {
                    SettingsSingleChoiceDialog(
                        title = stringResource(R.string.dv7_libdovi_mode_row_title),
                        subtitle = stringResource(R.string.dv7_libdovi_mode_caption),
                        options = modeOptions,
                        selectedValue = effectiveOverride,
                        onOptionSelected = { value ->
                            scope.launch { playbackVm.setDv7LibdoviModeOverride(value) }
                            showModeDialog = false
                        },
                        onDismiss = { showModeDialog = false },
                        width = 460.dp,
                        maxHeight = 360.dp
                    )
                }
            }

            diagnosticsCardItems(
                diagnostics = dvDiagnostics,
                dvCurrentlyEnabled = dvPlayerSettings.dv7HandlingMode != Dv7HandlingMode.OFF
            )
        }
    }
        SettingsVerticalScrollIndicators(state = networkListState)
    }

    if (showExperienceModeConfirmation) {
        ExperienceModeConfirmationDialog(
            targetMode = ExperienceMode.ESSENTIAL,
            onConfirm = { experienceModeViewModel.setMode(ExperienceMode.ESSENTIAL) },
            onDismiss = { showExperienceModeConfirmation = false }
        )
    }

    if (showAddonTimeoutDialog) {
        val defaultSeconds = AddonTimeoutPreferences.DEFAULT_TIMEOUT_SECONDS
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.advanced_addon_timeout),
            subtitle = stringResource(R.string.advanced_addon_timeout_subtitle),
            options = AddonTimeoutPreferences.TIMEOUT_OPTIONS_SECONDS.map { seconds ->
                SettingsPickerOption(
                    value = seconds,
                    title = if (seconds == defaultSeconds) {
                        stringResource(R.string.advanced_addon_timeout_default, seconds)
                    } else {
                        stringResource(R.string.advanced_addon_timeout_value, seconds)
                    }
                )
            },
            selectedValue = uiState.addonTimeoutSeconds,
            onOptionSelected = { seconds ->
                viewModel.onEvent(AdvancedSettingsEvent.SetAddonTimeoutSeconds(seconds))
                showAddonTimeoutDialog = false
            },
            onDismiss = { showAddonTimeoutDialog = false }
        )
    }

}

@Composable
private fun StreamTestResultRow(
    label: String,
    speed: Double?,
    isRunning: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary
        )
        Text(
            text = when {
                isRunning -> stringResource(R.string.stream_test_btn_running)
                speed != null -> "%.1f Mbps".format(speed)
                else -> "---"
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (speed != null && !isRunning) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextTertiary
        )
    }
}
