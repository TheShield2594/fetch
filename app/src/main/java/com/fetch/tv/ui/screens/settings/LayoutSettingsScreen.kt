package com.fetch.tv.ui.screens.settings

import com.fetch.tv.ui.theme.FetchTheme

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetch.tv.R
import com.fetch.tv.ui.screens.addon.QrCodeOverlay

@Composable
fun LayoutSettingsScreen(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }
    // Phone pages are only served while this screen is open.
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopStreamBadgeQrMode()
            viewModel.stopCustomPosterQrMode()
        }
    }

    SettingsStandaloneScaffold {
        LayoutSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun LayoutSettingsContent(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    essentialMode: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val streamBadgeUiState by viewModel.streamBadgeUiState.collectAsStateWithLifecycle()
    val customPosterQrState by viewModel.customPosterQrState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sections = visibleLayoutSections(
        essentialMode = essentialMode,
        focusedPosterHasOptions = uiState.focusedPosterHasOptions()
    )
    var expandedSections by rememberSaveable { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(streamBadgeUiState.serverError) {
        val error = streamBadgeUiState.serverError ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.layout_title),
            subtitle = stringResource(
                if (essentialMode) R.string.layout_selection_subtitle else R.string.layout_subtitle
            )
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            val targetPending = settingsRowTargetPending()
            SettingsRowTargetScroller(listState)
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
                ) {
                    if (essentialMode) {
                        item(key = "essential") {
                            Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                                LayoutEssentialSection(
                                    uiState = uiState,
                                    onEvent = viewModel::onEvent,
                                    firstRowFocusRequester = initialFocusRequester
                                )
                            }
                        }
                    }
                    items(items = sections, key = { it.name }) { section ->
                        // While a search result's row is being looked for, every section is open,
                        // and the one it turns up in stays open afterwards.
                        val expanded = section.name in expandedSections || targetPending
                        SettingsCollapsibleSection(
                            title = stringResource(section.title),
                            description = stringResource(section.description),
                            icon = section.icon,
                            expanded = expanded,
                            onToggle = {
                                expandedSections = if (expanded) {
                                    expandedSections - section.name
                                } else {
                                    expandedSections + section.name
                                }
                            },
                            focusRequester = if (section == sections.first()) initialFocusRequester else null,
                            modifier = Modifier.testTag(LayoutSettingsTestTags.section(section))
                        ) {
                            CompositionLocalProvider(
                                LocalSettingsTargetFound provides {
                                    if (section.name !in expandedSections) expandedSections = expandedSections + section.name
                                }
                            ) {
                                LayoutSectionContent(
                                    section = section,
                                    uiState = uiState,
                                    streamBadgeUiState = streamBadgeUiState,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }

        if (streamBadgeUiState.isQrModeActive) {
            QrCodeOverlay(
                qrBitmap = streamBadgeUiState.qrCodeBitmap,
                serverUrl = streamBadgeUiState.serverUrl,
                instruction = stringResource(R.string.stream_badge_qr_instruction),
                onClose = viewModel::stopStreamBadgeQrMode,
                qrSize = 168.dp
            )
        }

        if (customPosterQrState.isActive) {
            QrCodeOverlay(
                qrBitmap = customPosterQrState.qrCodeBitmap,
                serverUrl = customPosterQrState.serverUrl,
                instruction = stringResource(R.string.custom_poster_qr_instruction),
                onClose = viewModel::stopCustomPosterQrMode,
                qrSize = 168.dp
            )
        }
    }
}

@Composable
private fun LayoutSectionContent(
    section: LayoutSection,
    uiState: LayoutSettingsUiState,
    streamBadgeUiState: StreamBadgeSettingsUiState,
    viewModel: LayoutSettingsViewModel
) {
    val onEvent: (LayoutSettingsEvent) -> Unit = viewModel::onEvent
    when (section) {
        LayoutSection.HOME_LAYOUT -> LayoutHomeLayoutSection(uiState, onEvent)
        LayoutSection.HOME_CONTENT -> LayoutHomeContentSection(uiState, onEvent)
        LayoutSection.SIDEBAR -> LayoutSidebarSection(uiState, onEvent)
        LayoutSection.CONTINUE_WATCHING -> LayoutContinueWatchingSection(uiState, onEvent)
        LayoutSection.FOCUSED_POSTER -> LayoutFocusedPosterSection(uiState, onEvent)
        LayoutSection.POSTER_CARD -> LayoutPosterCardSection(uiState, onEvent)
        LayoutSection.CUSTOM_POSTERS -> LayoutCustomPosterSection(
            uiState = uiState,
            onEvent = onEvent,
            onConfigureViaPhone = viewModel::startCustomPosterQrMode
        )
        LayoutSection.DETAIL_PAGE -> LayoutDetailPageSection(uiState, onEvent)
        LayoutSection.STREAMS -> LayoutStreamsSection(
            streamBadgeUiState = streamBadgeUiState,
            onShowFileSizeBadgesChange = viewModel::setShowFileSizeBadges,
            onShowAddonLogoChange = viewModel::setShowAddonLogo,
            onBadgePlacementSelected = viewModel::setStreamBadgePlacement,
            onConfigureBadges = viewModel::startStreamBadgeQrMode
        )
    }
}
