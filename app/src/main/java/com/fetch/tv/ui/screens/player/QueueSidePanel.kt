package com.fetch.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.R
import com.fetch.tv.data.local.QueueItem
import com.fetch.tv.ui.theme.FetchStatusTones
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.localizeEpisodeTitle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** "S2 E6 · Title" for an episode, the title alone otherwise. */
@Composable
internal fun queueItemSubtitle(item: QueueItem): String? {
    val context = LocalContext.current
    val code = if (item.season != null && item.episode != null) {
        stringResource(R.string.season_episode_format, item.season, item.episode)
    } else {
        null
    }
    val episodeTitle = item.episodeTitle?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
    return joinPlayerMeta(code, episodeTitle).takeIf { it.isNotBlank() }
}

/** A queue item the person just removed, and where it sat in the full queue. */
private data class RemovedQueueItem(val item: QueueItem, val index: Int)

/** Longest an Undo waits for one restored item to settle before putting back the next. */
private const val QUEUE_RESTORE_SETTLE_TIMEOUT_MS = 2_000L

/** The player's Queue panel: what plays after this, in order, with play, reorder and remove. */
@Composable
internal fun QueueSidePanel(
    items: List<QueueItem>,
    currentVideoId: String?,
    onPlay: (QueueItem) -> Unit,
    onMove: (QueueItem, up: Boolean) -> Unit,
    onRemove: (QueueItem) -> Unit,
    /** Puts a removed item back at [index] in the full queue. */
    onRestore: (item: QueueItem, index: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val upcoming = remember(items, currentVideoId) { upcomingQueueItems(items, currentVideoId) }
    val firstItemFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    val undoFocusRequester = remember { FocusRequester() }

    var panelHasFocus by remember { mutableStateOf(false) }
    // The last removal (one row, or every row for Clear all), kept while the panel is open so
    // it can be undone from here.
    var lastRemoved by remember { mutableStateOf<List<RemovedQueueItem>>(emptyList()) }
    // Items an Undo is putting back, in queue order. One at a time: each restore walks its
    // item up from the end of the queue, so the next starts once the previous has settled.
    var pendingRestores by remember { mutableStateOf<List<RemovedQueueItem>>(emptyList()) }
    val currentItems by rememberUpdatedState(items)

    // A removal takes focus to its Undo, which sits where the removed row was reached from.
    LaunchedEffect(lastRemoved) {
        if (lastRemoved.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { undoFocusRequester.requestFocus() }
    }

    // Keyed on the head: an Undo appending to the tail must not restart, and so re-dispatch,
    // the restore still settling.
    val pendingHead = pendingRestores.firstOrNull()
    LaunchedEffect(pendingHead) {
        val head = pendingHead ?: return@LaunchedEffect
        onRestore(head.item, head.index)
        withTimeoutOrNull(QUEUE_RESTORE_SETTLE_TIMEOUT_MS) {
            snapshotFlow {
                val at = currentItems.indexOfFirst { it.videoId == head.item.videoId }
                at >= 0 && at <= head.index
            }.first { it }
        }
        pendingRestores = pendingRestores.drop(1)
    }

    // On open, and again when the focused row was removed, so focus never falls out of the panel.
    LaunchedEffect(upcoming.size) {
        repeat(10) {
            withFrameNanos { }
            if (panelHasFocus) return@LaunchedEffect
            runCatching {
                if (upcoming.isEmpty()) closeFocusRequester.requestFocus() else firstItemFocusRequester.requestFocus()
            }
        }
    }

    Column(
        modifier = modifier
            .onFocusChanged { panelHasFocus = it.hasFocus }
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = FetchTheme.spacing.lg, bottomStart = FetchTheme.spacing.lg))
            .background(FetchTheme.colors.BackgroundElevated)
            .padding(FetchTheme.spacing.xl)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.player_queue),
                style = MaterialTheme.typography.headlineSmall,
                color = FetchTheme.colors.TextPrimary
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (upcoming.size > 1) {
                    DialogButton(
                        text = stringResource(R.string.player_queue_clear_all),
                        onClick = {
                            lastRemoved = upcoming.map { item ->
                                RemovedQueueItem(
                                    item = item,
                                    index = items.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
                                )
                            }
                            upcoming.forEach(onRemove)
                        },
                        isPrimary = false
                    )
                }
                DialogButton(
                    text = stringResource(R.string.sources_close),
                    onClick = onClose,
                    isPrimary = false,
                    modifier = Modifier.focusRequester(closeFocusRequester)
                )
            }
        }

        lastRemoved.takeIf { it.isNotEmpty() }?.let { removed ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FetchTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (removed.size == 1) {
                        stringResource(R.string.player_queue_removed, removed.single().item.name)
                    } else {
                        stringResource(R.string.player_queue_cleared)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                DialogButton(
                    text = stringResource(R.string.player_queue_undo),
                    onClick = {
                        lastRemoved = emptyList()
                        pendingRestores = pendingRestores + removed.sortedBy { it.index }
                    },
                    isPrimary = false,
                    modifier = Modifier.focusRequester(undoFocusRequester)
                )
            }
        }

        if (upcoming.isEmpty()) {
            Text(
                text = stringResource(R.string.player_queue_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = FetchTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = FetchTheme.spacing.xl)
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FetchTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            itemsIndexed(upcoming, key = { _, item -> item.videoId }) { index, item ->
                QueueRow(
                    item = item,
                    canMoveUp = index > 0,
                    canMoveDown = index < upcoming.lastIndex,
                    focusRequester = if (index == 0) firstItemFocusRequester else null,
                    onPlay = { onPlay(item) },
                    onMove = { up -> onMove(item, up) },
                    onRemove = {
                        lastRemoved = listOf(
                            RemovedQueueItem(
                                item = item,
                                index = items.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
                            )
                        )
                        onRemove(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    focusRequester: FocusRequester?,
    onPlay: () -> Unit,
    onMove: (up: Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Card(
            onClick = onPlay,
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            colors = CardDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                focusedContainerColor = FetchTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(),
                    shape = RoundedCornerShape(FetchTheme.radii.xl)
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1.01f),
            shape = CardDefaults.shape(shape = RoundedCornerShape(FetchTheme.radii.xl))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(112.dp)
                        .height(63.dp)
                        .clip(RoundedCornerShape(FetchTheme.radii.md))
                        .background(FetchTheme.colors.SurfaceVariant)
                ) {
                    AsyncImage(
                        model = item.backdrop ?: item.poster,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = FetchTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    queueItemSubtitle(item)?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FetchTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        QueueRowIconButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.player_queue_move_up),
            enabled = canMoveUp,
            onClick = { onMove(true) }
        )
        QueueRowIconButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.player_queue_move_down),
            enabled = canMoveDown,
            onClick = { onMove(false) }
        )
        QueueRowIconButton(
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.cw_action_remove_from_queue),
            enabled = true,
            onClick = onRemove,
            destructive = true
        )
    }
}

@Composable
private fun QueueRowIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Remove: a red fill and a focus ring on focus, so it can't be mistaken for a reorder arrow. */
    destructive: Boolean = false
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(FetchTheme.spacing.xxxl),
        colors = IconButtonDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = if (destructive) FetchStatusTones.errorContainer else FetchTheme.colors.TextPrimary,
            contentColor = FetchTheme.colors.TextPrimary,
            focusedContentColor = if (destructive) FetchStatusTones.errorText else FetchTheme.colors.Background,
            disabledContainerColor = FetchTheme.colors.BackgroundCard.copy(alpha = 0.4f),
            disabledContentColor = FetchTheme.colors.TextTertiary
        ),
        border = if (destructive) {
            IconButtonDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(),
                    shape = CircleShape
                )
            )
        } else {
            IconButtonDefaults.border()
        },
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
    }
}
