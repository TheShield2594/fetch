package com.fetch.tv.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetch.tv.R
import com.fetch.tv.data.local.PlayerSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The playback settings. With a [page], that page as the settings rail opens it: each of its
 * sections the player engine shows, one after another under its own heading. Without one, every
 * section as a collapsible list.
 */
@Composable
internal fun PlaybackSettingsContent(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    page: PlaybackPage? = null
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    val transparentLetterbox by viewModel.transparentLetterbox.collectAsStateWithLifecycle(initialValue = false)
    val installedAddonNames by viewModel.installedAddonNames.collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()
    var openDialog by remember { mutableStateOf<PlaybackDialog?>(null) }
    var memoryUsageTrigger by remember { mutableIntStateOf(0) }
    var showMemoryUsage by remember { mutableStateOf(false) }
    val onUpdate: PlaybackSettingsUpdate = remember(viewModel, coroutineScope) {
        { block -> coroutineScope.launch { viewModel.block() } }
    }

    LaunchedEffect(memoryUsageTrigger) {
        if (memoryUsageTrigger == 0) return@LaunchedEffect
        showMemoryUsage = true
        delay(2200)
        showMemoryUsage = false
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(page?.title ?: R.string.playback_title),
            subtitle = stringResource(page?.description ?: R.string.playback_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val onOpenDialog: (PlaybackDialog) -> Unit = { openDialog = it }
            val onMemorySettingChanged: () -> Unit = { memoryUsageTrigger++ }
            if (page == null) {
                PlaybackSettingsSections(
                    playerSettings = playerSettings,
                    transparentLetterbox = transparentLetterbox,
                    onUpdate = onUpdate,
                    onOpenDialog = onOpenDialog,
                    onMemorySettingChanged = onMemorySettingChanged,
                    initialFocusRequester = initialFocusRequester
                )
            } else {
                val visibleSections = visiblePlaybackSections(playerSettings)
                val sections = page.sections.filter { it in visibleSections }
                val scrollState = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            // Not a focus target itself: asking it for focus lands on its first row.
                            .then(if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        sections.forEach { section ->
                            key(section) {
                                val sectionTitle = stringResource(section.title)
                                // A search result naming the section lands on its first row.
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .settingsRowTarget(sectionTitle),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    SettingsSectionLabel(
                                        text = sectionTitle,
                                        description = stringResource(section.description)
                                    )
                                    PlaybackSectionContent(
                                        section = section,
                                        playerSettings = playerSettings,
                                        transparentLetterbox = transparentLetterbox,
                                        onUpdate = onUpdate,
                                        onOpenDialog = onOpenDialog,
                                        onMemorySettingChanged = onMemorySettingChanged
                                    )
                                }
                            }
                        }
                    }
                    SettingsVerticalScrollIndicators(state = scrollState)
                }
            }
        }

        AnimatedVisibility(
            visible = showMemoryUsage && (
                playerSettings.bufferEngineEnabled ||
                    playerSettings.parallelNetworkEnabled ||
                    playerSettings.fetchPerformanceModeEnabled
                ),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            PlaybackMemoryUsageCard(settings = playerSettings)
        }
    }

    PlaybackSettingsDialogs(
        dialog = openDialog,
        settings = playerSettings,
        installedAddonNames = installedAddonNames,
        onUpdate = onUpdate,
        onDismiss = { openDialog = null }
    )
}
