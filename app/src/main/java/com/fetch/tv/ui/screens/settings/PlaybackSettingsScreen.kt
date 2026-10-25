package com.fetch.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
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
import com.fetch.tv.core.torrent.TorrentCacheClearResult
import com.fetch.tv.core.torrent.TorrentSettingsData
import com.fetch.tv.core.torrent.TorrentState
import com.fetch.tv.data.local.PlayerSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlaybackSettingsScreen(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.playback_title),
        subtitle = stringResource(R.string.playback_subtitle)
    ) {
        PlaybackSettingsContent(viewModel = viewModel)
    }
}

/**
 * The playback settings. With a [section], that section's page as the settings rail opens it;
 * without one, every section as a collapsible list, for the standalone screen.
 */
@Composable
internal fun PlaybackSettingsContent(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    section: PlaybackSection? = null
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    val transparentLetterbox by viewModel.transparentLetterbox.collectAsStateWithLifecycle(initialValue = false)
    val torrentSettings by viewModel.torrentSettingsFlow.collectAsStateWithLifecycle(initialValue = TorrentSettingsData())
    val torrentCacheState by viewModel.torrentCacheState.collectAsStateWithLifecycle()
    val torrentState by viewModel.torrentState.collectAsStateWithLifecycle()
    var torrentCacheClearResult by remember { mutableStateOf<TorrentCacheClearResult?>(null) }
    var torrentCacheClearFailed by remember { mutableStateOf(false) }
    val torrentCacheClearAvailable = torrentState !is TorrentState.Connecting &&
        torrentState !is TorrentState.Streaming &&
        !torrentCacheState.isClearing
    val p2p = P2pSettingsUi(
        enabled = torrentSettings.p2pEnabled,
        hideStats = torrentSettings.hideTorrentStats,
        profile = torrentSettings.torrentProfile,
        cacheSize = torrentSettings.cacheSize,
        cacheSummary = torrentCacheSummary(
            cacheState = torrentCacheState,
            clearAvailable = torrentCacheClearAvailable,
            clearResult = torrentCacheClearResult,
            clearFailed = torrentCacheClearFailed
        ),
        cacheClearEnabled = torrentCacheClearAvailable
    )
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
            title = stringResource(section?.title ?: R.string.playback_title),
            subtitle = stringResource(section?.description ?: R.string.playback_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val onOpenDialog: (PlaybackDialog) -> Unit = { openDialog = it }
            val onMemorySettingChanged: () -> Unit = { memoryUsageTrigger++ }
            val onClearTorrentCache: () -> Unit = {
                torrentCacheClearResult = null
                torrentCacheClearFailed = false
                coroutineScope.launch {
                    runCatching { viewModel.clearTorrentCache() }
                        .onSuccess { torrentCacheClearResult = it }
                        .onFailure { torrentCacheClearFailed = true }
                }
            }
            if (section == null) {
                PlaybackSettingsSections(
                    playerSettings = playerSettings,
                    p2p = p2p,
                    transparentLetterbox = transparentLetterbox,
                    onUpdate = onUpdate,
                    onOpenDialog = onOpenDialog,
                    onMemorySettingChanged = onMemorySettingChanged,
                    onClearTorrentCache = onClearTorrentCache,
                    initialFocusRequester = initialFocusRequester
                )
            } else {
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
                        PlaybackSectionContent(
                            section = section,
                            playerSettings = playerSettings,
                            p2p = p2p,
                            transparentLetterbox = transparentLetterbox,
                            onUpdate = onUpdate,
                            onOpenDialog = onOpenDialog,
                            onMemorySettingChanged = onMemorySettingChanged,
                            onClearTorrentCache = onClearTorrentCache
                        )
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
        p2p = p2p,
        installedAddonNames = installedAddonNames,
        onUpdate = onUpdate,
        onDismiss = { openDialog = null }
    )
}
