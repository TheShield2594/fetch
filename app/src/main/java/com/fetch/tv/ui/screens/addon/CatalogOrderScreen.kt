@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.addon

import com.fetch.tv.ui.theme.FetchTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.screens.settings.SettingsToggleRow
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import kotlinx.coroutines.launch
import com.fetch.tv.ui.util.dpadRepeatThrottle
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Customise Home: every row Home shows (addon catalogs, folders and filter rows), to reorder,
 * hide or pin, and the ways to add a row. Opened from Settings › Customise Home, the addon
 * manager, or a Home poster's menu, which passes [focusKey] so it opens on that row.
 */
@Composable
fun CatalogOrderScreen(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    focusKey: String? = null,
    onAddCatalogRow: () -> Unit = {},
    onAddFilterRow: () -> Unit = {},
    onAddFolderRow: () -> Unit = {},
    onEditFilterRows: () -> Unit = {},
    onEditFolders: () -> Unit = {},
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BackHandler { onBackPress() }

    // Opened from a row on Home: land on that row's first control, one press from moving it.
    val focusRowRequester = remember { FocusRequester() }
    var focusRowHandled by rememberSaveable(focusKey) { mutableStateOf(focusKey == null) }
    val focusRowIndex = uiState.items.indexOfFirst { it.key == focusKey }
    LaunchedEffect(focusRowIndex, focusRowHandled) {
        if (focusRowHandled || focusRowIndex < 0) return@LaunchedEffect
        // The header is item 0, so the row's list index is one past its position.
        listState.scrollToItem(focusRowIndex + 1)
        focusRowRequester.requestFocusAfterFrames(frames = 2)
        focusRowHandled = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FetchTheme.spacing.screen.horizontal, vertical = FetchTheme.spacing.screen.vertical)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().dpadRepeatThrottle(),
            contentPadding = PaddingValues(bottom = FetchTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
        ) {
            item {
                Text(
                    text = stringResource(R.string.customise_home_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = FetchTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                Text(
                    text = stringResource(R.string.customise_home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))
                CustomiseHomeActionsRow(
                    label = stringResource(R.string.customise_home_add_row),
                    actions = listOf(
                        stringResource(R.string.customise_home_from_catalog) to onAddCatalogRow,
                        stringResource(R.string.customise_home_from_filter) to onAddFilterRow,
                        stringResource(R.string.customise_home_from_folder) to onAddFolderRow
                    )
                )
                Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                CustomiseHomeActionsRow(
                    label = null,
                    actions = listOf(
                        stringResource(R.string.customise_home_edit_filter_rows) to onEditFilterRows,
                        stringResource(R.string.customise_home_edit_folders) to onEditFolders
                    )
                )
                Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))
                SettingsToggleRow(
                    title = stringResource(R.string.catalog_order_follow_addons),
                    subtitle = stringResource(R.string.catalog_order_follow_addons_desc),
                    checked = uiState.followAddonsOrder,
                    onToggle = { viewModel.toggleFollowAddonsOrder(!uiState.followAddonsOrder) }
                )
            }

            when {
                uiState.isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = FetchTheme.spacing.xl),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }
                }

                uiState.items.isEmpty() -> {
                    item {
                        Text(
                            text = stringResource(R.string.catalog_order_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = FetchTheme.colors.TextSecondary
                        )
                    }
                }

                else -> {
                    itemsIndexed(
                        items = uiState.items,
                        key = { _, item -> item.key }
                    ) { index, item ->
                        CatalogOrderCard(
                            item = item,
                            firstControlModifier = if (item.key == focusKey) {
                                Modifier.focusRequester(focusRowRequester)
                            } else {
                                Modifier
                            },
                            onMoveUp = {
                                viewModel.moveUp(item.key)
                                scope.launch {
                                    listState.animateScrollToItem((index - 1).coerceAtLeast(0))
                                }
                            },
                            onMoveDown = {
                                viewModel.moveDown(item.key)
                                scope.launch {
                                    listState.animateScrollToItem(
                                        (index + 1).coerceAtMost(uiState.items.lastIndex)
                                    )
                                }
                            },
                            onToggleEnabled = { viewModel.toggleCatalogEnabled(item.disableKey) },
                            onTogglePinned = { viewModel.togglePinned(item.key) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogOrderCard(
    item: CatalogOrderItem,
    firstControlModifier: Modifier = Modifier,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: () -> Unit,
    onTogglePinned: () -> Unit
) {
    // The first control that can take focus: a disabled button can't hold the requester.
    val firstControl = when {
        item.canMoveUp -> FirstControl.UP
        item.canMoveDown -> FirstControl.DOWN
        else -> FirstControl.PIN
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FetchTheme.colors.BackgroundCard),
        shape = RoundedCornerShape(FetchTheme.radii.md)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${item.catalogName} - ${item.typeLabel.toDisplayTypeLabel()}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (item.isDisabled) FetchTheme.colors.TextSecondary else FetchTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))
                Text(
                    text = item.addonName,
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.TextSecondary
                )
                if (item.isPinned) {
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.catalog_order_pinned_on_home),
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.Primary
                    )
                }
                if (item.isDisabled) {
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.catalog_order_disabled_on_home),
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.Error
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onMoveUp,
                    enabled = item.canMoveUp,
                    modifier = if (firstControl == FirstControl.UP) firstControlModifier else Modifier,
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextSecondary,
                        focusedContainerColor = FetchTheme.colors.FocusBackground,
                        focusedContentColor = FetchTheme.colors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = stringResource(R.string.cd_move_up)
                    )
                }

                Button(
                    onClick = onMoveDown,
                    enabled = item.canMoveDown,
                    modifier = if (firstControl == FirstControl.DOWN) firstControlModifier else Modifier,
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextSecondary,
                        focusedContainerColor = FetchTheme.colors.FocusBackground,
                        focusedContentColor = FetchTheme.colors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.cd_move_down)
                    )
                }

                Button(
                    onClick = onTogglePinned,
                    modifier = if (firstControl == FirstControl.PIN) firstControlModifier else Modifier,
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = if (item.isPinned) FetchTheme.colors.Primary else FetchTheme.colors.TextSecondary,
                        focusedContainerColor = FetchTheme.colors.FocusBackground,
                        focusedContentColor = FetchTheme.colors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                ) {
                    Icon(
                        imageVector = if (item.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = stringResource(
                            if (item.isPinned) R.string.catalog_order_unpin else R.string.catalog_order_pin
                        )
                    )
                }

                Button(
                    onClick = onToggleEnabled,
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = if (item.isDisabled) FetchTheme.colors.Success else FetchTheme.colors.TextSecondary,
                        focusedContainerColor = FetchTheme.colors.FocusBackground,
                        focusedContentColor = if (item.isDisabled) FetchTheme.colors.Success else FetchTheme.colors.Error
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = FetchTheme.focusRing.border(),
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                ) {
                    Text(text = if (item.isDisabled) stringResource(R.string.catalog_order_enable) else stringResource(R.string.catalog_order_disable))
                }
            }
        }
    }
}

private enum class FirstControl { UP, DOWN, PIN }

/** A label, then a row of buttons that each lead off the screen. */
@Composable
private fun CustomiseHomeActionsRow(
    label: String?,
    actions: List<Pair<String, () -> Unit>>
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = FetchTheme.colors.TextPrimary,
                modifier = Modifier.padding(end = FetchTheme.spacing.sm)
            )
        }
        actions.forEach { (text, onClick) ->
            Button(
                onClick = onClick,
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary,
                    focusedContainerColor = FetchTheme.colors.FocusBackground,
                    focusedContentColor = FetchTheme.colors.Primary
                ),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = FetchTheme.focusRing.border(),
                        shape = RoundedCornerShape(FetchTheme.radii.md)
                    )
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
            ) {
                Text(text = text)
            }
        }
    }
}

private fun String.toDisplayTypeLabel(): String {
    return replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}
