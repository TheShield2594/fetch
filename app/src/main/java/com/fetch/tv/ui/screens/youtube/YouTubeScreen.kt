package com.fetch.tv.ui.screens.youtube

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.R
import com.fetch.tv.data.youtube.YouTubeBrowseTarget
import com.fetch.tv.data.youtube.YouTubeChannelRef
import com.fetch.tv.data.youtube.YouTubeFeed
import com.fetch.tv.data.youtube.YouTubeLink
import com.fetch.tv.data.youtube.YouTubeLinkKind
import com.fetch.tv.data.youtube.YouTubeShelf
import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.ErrorState
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.screens.settings.SettingsChoiceChip
import com.fetch.tv.ui.theme.FetchRadii
import com.fetch.tv.ui.theme.FetchTheme

private val VideoCardWidth = 300.dp
private val ShortCardWidth = 180.dp

/**
 * The YouTube section: the signed-in account's home feed, subscriptions and history, and search.
 * It's in the sidebar only while the profile is signed in to YouTube in Settings.
 */
@Composable
fun YouTubeScreen(
    viewModel: YouTubeViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val firstTabFocusRequester = remember { FocusRequester() }
    // The video whose long-press menu is open.
    var menuVideo by remember { mutableStateOf<YouTubeVideo?>(null) }
    val openPage = state.browse.lastOrNull()
    val pageKey = pageKey(state, state.browse.size)
    val memory = remember { YouTubePageMemory() }
    // The card to focus once the page showing composes it.
    var pendingFocus by remember { mutableStateOf<PendingFocus?>(null) }
    var openDepth by remember { mutableIntStateOf(state.browse.size) }

    BackHandler(enabled = openPage != null) {
        // Back goes to the card that opened the page.
        val below = pageKey(state, state.browse.size - 1)
        pendingFocus = PendingFocus(below, memory.focusedItem(below))
        viewModel.closeBrowse()
    }

    // A page that opens starts at its top, with its first card focused. One closed is forgotten,
    // so opening it again starts afresh too.
    LaunchedEffect(state.browse.size) {
        val depth = state.browse.size
        if (depth > openDepth) pendingFocus = PendingFocus(pageKey, item = null)
        if (depth < openDepth) memory.forgetPagesFrom(depth)
        openDepth = depth
    }

    val feed = state.currentFeed
    // A page that loads again starts at its top.
    LaunchedEffect(pageKey, feed.isLoading) {
        if (feed.isLoading) memory.forget(pageKey)
    }
    // When the card to focus never shows (the page came back empty, or it's gone), the tabs take focus.
    LaunchedEffect(pendingFocus, feed.isLoading) {
        val pending = pendingFocus ?: return@LaunchedEffect
        if (feed.isLoading) return@LaunchedEffect
        repeat(FOCUS_RESTORE_FRAMES) { withFrameNanos { } }
        if (pendingFocus == pending) {
            pendingFocus = null
            firstTabFocusRequester.requestFocusAfterFrames()
        }
    }
    val cardFocus = CardFocus(
        page = pageKey,
        pending = pendingFocus,
        onFocused = { item -> memory.setFocusedItem(pageKey, item) },
        onRestored = { pendingFocus = null }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background)
            .padding(top = FetchTheme.spacing.screen.vertical),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FetchTheme.spacing.screen.horizontal),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            YouTubeFeed.entries.forEachIndexed { index, feed ->
                SettingsChoiceChip(
                    label = stringResource(feed.labelRes()),
                    selected = state.selectedFeed == feed,
                    onClick = { viewModel.selectFeed(feed) },
                    modifier = if (index == 0) Modifier.focusRequester(firstTabFocusRequester) else Modifier
                )
            }
        }

        if (openPage != null) {
            Text(
                text = openPage.target.title.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                color = FetchTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = FetchTheme.spacing.screen.horizontal)
            )
        } else if (state.selectedFeed == YouTubeFeed.SEARCH) {
            YouTubeSearchField(
                query = state.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                onSubmit = viewModel::submitSearch,
                modifier = Modifier.padding(horizontal = FetchTheme.spacing.screen.horizontal)
            )
        }

        // Each tab and opened page keeps its own scroll position, from [memory].
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            key(pageKey) {
                when {
                    state.signedOut -> EmptyScreenState(
                        title = stringResource(R.string.youtube_signed_out_title),
                        subtitle = stringResource(R.string.youtube_signed_out_subtitle),
                        icon = Icons.Default.VideoLibrary,
                        action = StateAction(stringResource(R.string.nav_settings), onOpenSettings)
                    )
                    feed.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                    feed.failed -> ErrorState(
                        message = stringResource(R.string.youtube_feed_error),
                        onRetry = viewModel::refresh
                    )
                    // Before a search, Search asks for one; after it, an empty result says so.
                    feed.shelves.isEmpty() -> EmptyScreenState(
                        title = stringResource(
                            when {
                                openPage != null || state.selectedFeed != YouTubeFeed.SEARCH -> R.string.youtube_feed_empty
                                feed.loaded -> R.string.youtube_search_no_results
                                else -> R.string.youtube_search_empty
                            }
                        ),
                        icon = if (openPage == null && state.selectedFeed == YouTubeFeed.SEARCH) Icons.Default.Search else Icons.Default.VideoLibrary
                    )
                    // Subscriptions, history, search and the library are one long list: a grid reads better than one row.
                    feed.shelves.size == 1 -> YouTubeVideoGrid(
                        shelf = feed.shelves[0],
                        gridState = memory.grid(pageKey),
                        focus = cardFocus,
                        isLoadingMore = feed.isLoadingMore || 0 in feed.loadingShelves,
                        onNearEnd = viewModel::loadMore,
                        onPlay = viewModel::play,
                        onMenu = { menuVideo = it },
                        onOpen = viewModel::openBrowse
                    )
                    else -> YouTubeShelves(
                        feed = feed,
                        listState = memory.list(pageKey),
                        rowState = { index -> memory.list("$pageKey/row_$index") },
                        focus = cardFocus,
                        onNearFeedEnd = viewModel::loadMore,
                        onNearShelfEnd = viewModel::loadMoreInShelf,
                        onPlay = viewModel::play,
                        onMenu = { menuVideo = it },
                        onViewAll = viewModel::openBrowse
                    )
                }
            }
        }
    }

    menuVideo?.let { video ->
        YouTubeVideoMenu(
            video = video,
            onDismiss = { menuVideo = null },
            onPlay = {
                menuVideo = null
                viewModel.play(video)
            },
            onOpenChannel = { channel ->
                menuVideo = null
                viewModel.openChannel(video, channel)
            }
        )
    }

    state.playback?.let { playback ->
        YouTubePlayerOverlay(
            playback = playback,
            onProgress = viewModel::onPlaybackProgress,
            onSelectCaption = viewModel::selectCaption,
            onPlayNext = viewModel::playNext,
            onDismiss = viewModel::closePlayback,
            onRetry = viewModel::retryPlayback,
            onRate = viewModel::rate,
            onToggleSubscription = viewModel::toggleSubscription,
            onOpenChannel = viewModel::openPlayingChannel
        )
    }
}

/** How many frames a card asked to take focus has to show up before the tabs take it instead. */
private const val FOCUS_RESTORE_FRAMES = 10

/** The tab, or the page opened [depth] deep over the tabs, that [state] shows at that depth. */
private fun pageKey(state: YouTubeUiState, depth: Int): String {
    val page = state.browse.getOrNull(depth - 1) ?: return "tab_${state.selectedFeed}"
    return "browse_${depth}_${page.target}"
}

/** A card to focus: [item] on [page], or the page's first card when it's null. */
private data class PendingFocus(val page: String, val item: String?)

/** Where each tab and opened page is scrolled, and which card on it had focus last. */
private class YouTubePageMemory {
    private val lists = mutableMapOf<String, LazyListState>()
    private val grids = mutableMapOf<String, LazyGridState>()
    private val focused = mutableMapOf<String, String>()

    fun list(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }

    fun grid(key: String): LazyGridState = grids.getOrPut(key) { LazyGridState() }

    fun focusedItem(page: String): String? = focused[page]

    fun setFocusedItem(page: String, item: String) {
        focused[page] = item
    }

    /** Forgets [page], and its rows. */
    fun forget(page: String) = forgetWhere { it == page || it.startsWith("$page/") }

    /** Forgets the pages opened deeper than [depth]. */
    fun forgetPagesFrom(depth: Int) = forgetWhere { key ->
        key.startsWith("browse_") && (key.removePrefix("browse_").substringBefore('_').toIntOrNull() ?: 0) > depth
    }

    private fun forgetWhere(predicate: (String) -> Boolean) {
        lists.keys.removeAll(predicate)
        grids.keys.removeAll(predicate)
        focused.keys.removeAll(predicate)
    }
}

/** The page showing's cards report focus here, and the one [pending] names takes it back. */
private class CardFocus(
    val page: String,
    val pending: PendingFocus?,
    val onFocused: (item: String) -> Unit,
    val onRestored: () -> Unit
)

/** Focus for the card [item] on [focus]'s page: remembered when it's taken, and taken when asked. */
@Composable
private fun restorableFocus(focus: CardFocus, item: String, isFirst: Boolean): Modifier {
    val requester = remember { FocusRequester() }
    val pending = focus.pending
    val wanted = pending != null && pending.page == focus.page &&
        (pending.item == item || (pending.item == null && isFirst))
    if (wanted) {
        LaunchedEffect(pending) {
            if (requester.requestFocusAfterFrames()) focus.onRestored()
        }
    }
    return Modifier
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) focus.onFocused(item) }
}

/** How close to the end, in items, focus or scrolling gets before the next page loads. */
private const val LOAD_MORE_THRESHOLD = 6
private const val GRID_COLUMNS = 4
private const val SHORTS_GRID_COLUMNS = 7

@Composable
private fun YouTubeVideoGrid(
    shelf: YouTubeShelf,
    gridState: LazyGridState,
    focus: CardFocus,
    isLoadingMore: Boolean,
    onNearEnd: () -> Unit,
    onPlay: (YouTubeVideo) -> Unit,
    onMenu: (YouTubeVideo) -> Unit,
    onOpen: (YouTubeBrowseTarget) -> Unit
) {
    val videos = shelf.videos
    val count = videos.size + shelf.links.size
    val nearEnd by remember(count) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            count > 0 && last >= count - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, count) {
        if (nearEnd) onNearEnd()
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(if (shelf.isShorts) SHORTS_GRID_COLUMNS else GRID_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = FetchTheme.spacing.screen.horizontal,
            end = FetchTheme.spacing.screen.horizontal,
            top = FetchTheme.spacing.xs,
            bottom = FetchTheme.spacing.xxl
        ),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)
    ) {
        itemsIndexed(videos, key = { _, video -> video.videoId }) { index, video ->
            YouTubeVideoCard(
                video = video,
                onClick = { onPlay(video) },
                onLongClick = { onMenu(video) },
                portrait = shelf.isShorts,
                modifier = Modifier.fillMaxWidth(),
                cardModifier = restorableFocus(focus, video.videoId, isFirst = index == 0)
            )
        }
        itemsIndexed(shelf.links, key = { _, link -> "link_${link.target}" }) { index, link ->
            YouTubeLinkCard(
                link = link,
                onOpen = onOpen,
                modifier = Modifier.fillMaxWidth(),
                cardModifier = restorableFocus(focus, "link_${link.target}", isFirst = videos.isEmpty() && index == 0)
            )
        }
        shelf.viewAll?.let { target ->
            item(key = "view_all") {
                YouTubeViewAllCard(
                    onClick = { onOpen(target) },
                    portrait = shelf.isShorts,
                    modifier = Modifier.fillMaxWidth()
                        .then(restorableFocus(focus, "view_all", isFirst = count == 0))
                )
            }
        }
        if (isLoadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "loading_more") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
        }
    }
}

@Composable
private fun YouTubeShelves(
    feed: YouTubeFeedState,
    listState: LazyListState,
    rowState: (Int) -> LazyListState,
    focus: CardFocus,
    onNearFeedEnd: () -> Unit,
    onNearShelfEnd: (Int) -> Unit,
    onPlay: (YouTubeVideo) -> Unit,
    onMenu: (YouTubeVideo) -> Unit,
    onViewAll: (YouTubeBrowseTarget) -> Unit
) {
    val shelves = feed.shelves
    val nearEnd by remember(shelves.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            shelves.isNotEmpty() && last >= shelves.size - 2
        }
    }
    LaunchedEffect(nearEnd, shelves.size) {
        if (nearEnd) onNearFeedEnd()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FetchTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)
    ) {
        items(shelves.size, key = { index -> "shelf_$index" }) { index ->
            val shelf = shelves[index]
            Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                // Rows YouTube leaves untitled (its top picks, "wormholes") stay untitled, as in its TV app.
                shelf.title?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = FetchTheme.colors.TextPrimary,
                        modifier = Modifier.padding(horizontal = FetchTheme.spacing.screen.horizontal)
                    )
                }
                LazyRow(
                    state = rowState(index),
                    contentPadding = PaddingValues(horizontal = FetchTheme.spacing.screen.horizontal),
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
                ) {
                    itemsIndexed(shelf.videos, key = { _, video -> video.videoId }) { videoIndex, video ->
                        YouTubeVideoCard(
                            video = video,
                            onClick = { onPlay(video) },
                            onFocused = {
                                if (videoIndex >= shelf.videos.size - LOAD_MORE_THRESHOLD) onNearShelfEnd(index)
                            },
                            onLongClick = { onMenu(video) },
                            portrait = shelf.isShorts,
                            modifier = Modifier.width(if (shelf.isShorts) ShortCardWidth else VideoCardWidth),
                            cardModifier = restorableFocus(
                                focus,
                                "shelf_$index/${video.videoId}",
                                isFirst = index == 0 && videoIndex == 0
                            )
                        )
                    }
                    itemsIndexed(shelf.links, key = { _, link -> "link_${link.target}" }) { linkIndex, link ->
                        YouTubeLinkCard(
                            link = link,
                            onOpen = onViewAll,
                            modifier = Modifier.width(VideoCardWidth),
                            cardModifier = restorableFocus(
                                focus,
                                "shelf_$index/link_${link.target}",
                                isFirst = index == 0 && shelf.videos.isEmpty() && linkIndex == 0
                            )
                        )
                    }
                    shelf.viewAll?.let { target ->
                        item(key = "view_all") {
                            YouTubeViewAllCard(
                                onClick = { onViewAll(target) },
                                portrait = shelf.isShorts,
                                modifier = Modifier.width(if (shelf.isShorts) ShortCardWidth else VideoCardWidth)
                                    .then(restorableFocus(focus, "shelf_$index/view_all", isFirst = false))
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun YouTubeVideoCard(
    video: YouTubeVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    /** Shorts rows: a 9:16 card for their portrait thumbnails. */
    portrait: Boolean = false,
    /** For the focusable card itself, rather than the card with its title under it. */
    cardModifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(FetchRadii.tokens.md)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = cardModifier
                .fillMaxWidth()
                .aspectRatio(if (portrait) 9f / 16f else 16f / 9f)
                .onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(containerColor = FetchTheme.colors.BackgroundCard),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = shape
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1.05f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                (video.duration ?: if (video.isLive) stringResource(R.string.youtube_live) else null)?.let { duration ->
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelMedium,
                        color = FetchTheme.colors.TextPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(FetchTheme.spacing.xs)
                            .clip(RoundedCornerShape(FetchRadii.tokens.xs))
                            .background(
                                if (video.isLive) FetchTheme.colors.Error
                                else FetchTheme.colors.Background.copy(alpha = 0.8f)
                            )
                            .padding(horizontal = FetchTheme.spacing.xs)
                    )
                }
                video.watchedPercent?.takeIf { it > 0 }?.let { percent ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                            .height(FetchTheme.spacing.xxs)
                            .background(FetchTheme.colors.Error)
                    )
                }
            }
        }
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        listOfNotNull(video.channelName, video.detail).joinToString(" • ").takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A channel, playlist or library shortcut. It opens its page, titled as the card is. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun YouTubeLinkCard(
    link: YouTubeLink,
    onOpen: (YouTubeBrowseTarget) -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(FetchRadii.tokens.md)
    val title = link.title ?: stringResource(
        if (link.kind == YouTubeLinkKind.LIKED) R.string.youtube_liked_videos else R.string.youtube_watch_later
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Card(
            onClick = { onOpen(link.target.copy(title = title)) },
            modifier = cardModifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(containerColor = FetchTheme.colors.BackgroundCard),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = shape
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1.05f)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (link.thumbnailUrl != null) {
                    AsyncImage(
                        model = link.thumbnailUrl,
                        contentDescription = null,
                        // A channel's picture is a square avatar; it isn't cropped to the card.
                        contentScale = if (link.kind == YouTubeLinkKind.CHANNEL) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = when (link.kind) {
                            YouTubeLinkKind.CHANNEL -> Icons.Default.Person
                            YouTubeLinkKind.PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistPlay
                            YouTubeLinkKind.WATCH_LATER -> Icons.Default.Schedule
                            YouTubeLinkKind.LIKED -> Icons.Default.ThumbUp
                        },
                        contentDescription = null,
                        tint = FetchTheme.colors.TextPrimary
                    )
                }
                if (link.kind == YouTubeLinkKind.PLAYLIST && link.thumbnailUrl != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = FetchTheme.colors.TextPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(FetchTheme.spacing.xs)
                            .clip(RoundedCornerShape(FetchRadii.tokens.xs))
                            .background(FetchTheme.colors.Background.copy(alpha = 0.8f))
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        link.subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The card at the end of a row that opens all of it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun YouTubeViewAllCard(
    onClick: () -> Unit,
    portrait: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(FetchRadii.tokens.md)
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(if (portrait) 9f / 16f else 16f / 9f),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = FetchTheme.colors.BackgroundCard),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ViewList, contentDescription = null, tint = FetchTheme.colors.TextPrimary)
            Text(
                text = stringResource(R.string.youtube_view_all),
                style = MaterialTheme.typography.titleMedium,
                color = FetchTheme.colors.TextPrimary
            )
        }
    }
}

/** A video's long-press menu: play it, or open its channel, or one of a collaboration's. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun YouTubeVideoMenu(
    video: YouTubeVideo,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onOpenChannel: (YouTubeChannelRef?) -> Unit
) {
    val playFocusRequester = remember { FocusRequester() }
    // The dialog's content attaches after this effect starts.
    LaunchedEffect(Unit) { playFocusRequester.requestFocusAfterFrames() }
    val buttonColors = ButtonDefaults.colors(
        containerColor = FetchTheme.colors.BackgroundCard,
        contentColor = FetchTheme.colors.TextPrimary
    )
    FetchDialog(onDismiss = onDismiss, title = video.title, subtitle = video.channelName) {
        Button(
            onClick = onPlay,
            colors = buttonColors,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(playFocusRequester)
        ) {
            Text(stringResource(R.string.youtube_menu_play))
        }
        // A collaboration names each of its channels; a video from one channel just goes to it.
        val named = video.channels.filter { it.name != null }
        if (named.size > 1) {
            named.forEach { channel ->
                Button(onClick = { onOpenChannel(channel) }, colors = buttonColors, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.youtube_menu_go_to_named_channel, channel.name.orEmpty()))
                }
            }
        } else if (video.channelId != null || video.channels.isNotEmpty()) {
            Button(onClick = { onOpenChannel(null) }, colors = buttonColors, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.youtube_menu_go_to_channel))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun YouTubeSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FetchRadii.tokens.sm)
    Card(
        onClick = { inputFocusRequester.requestFocus() },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundElevated,
            focusedContainerColor = FetchTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border),
                shape = shape
            ),
            focusedBorder = Border(border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs), shape = shape)
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = FetchTheme.colors.TextSecondary)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(inputFocusRequester)
                    .onKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action == KeyEvent.ACTION_UP &&
                            (native.keyCode == KeyEvent.KEYCODE_ENTER || native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                        ) {
                            keyboardController?.hide()
                            onSubmit()
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboardController?.hide()
                    onSubmit()
                }),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = FetchTheme.colors.TextPrimary),
                cursorBrush = SolidColor(if (isFocused) FetchTheme.colors.Primary else FetchTheme.colors.Background.copy(alpha = 0f)),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.youtube_search_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = FetchTheme.colors.TextTertiary
                        )
                    }
                    inner()
                }
            )
        }
    }
}

private fun YouTubeFeed.labelRes(): Int = when (this) {
    YouTubeFeed.HOME -> R.string.youtube_tab_home
    YouTubeFeed.SUBSCRIPTIONS -> R.string.youtube_tab_subscriptions
    YouTubeFeed.HISTORY -> R.string.youtube_tab_history
    YouTubeFeed.LIBRARY -> R.string.youtube_tab_library
    YouTubeFeed.SEARCH -> R.string.youtube_tab_search
}
