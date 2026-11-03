package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.SettingsPage
import com.fetch.desktop.YouTubeFeedKey
import com.fetch.desktop.YouTubeFeedState
import com.fetch.desktop.YouTubeState
import com.fetch.desktop.youtube.YouTubeBrowseTarget
import com.fetch.desktop.youtube.YouTubeClient
import com.fetch.desktop.youtube.YouTubeFeed
import com.fetch.desktop.youtube.YouTubeLink
import com.fetch.desktop.youtube.YouTubeLinkKind
import com.fetch.desktop.youtube.YouTubePlaylistPrivacy
import com.fetch.desktop.youtube.YouTubeShelf
import com.fetch.desktop.youtube.YouTubeVideo
import kotlinx.coroutines.launch

/** Material Symbols' "smart_display" (Apache 2.0), for the sidebar entry. */
val YouTubeIcon: ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(
        pathData = addPathNodes("M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4zM9.5,16.5v-9l7,4.5L9.5,16.5z"),
        fill = SolidColor(Color.Black)
    )
    .build()

private val VideoCardWidth = 300.dp
private val ShortCardWidth = 170.dp
private val LiveRed = Color(0xFFCC0000)

/**
 * The YouTube page, as on the TV: Home, Subscriptions, History, Library and Search tabs over the
 * signed-in feeds. A click plays a video; right-click opens the TV's long-press menu.
 */
@Composable
fun YouTubeScreen(state: AppState) {
    val yt = state.youtube
    if (!yt.signedIn || yt.signedOut || state.limits.isRestricted) {
        YouTubeUnavailable(state)
        return
    }
    LaunchedEffect(Unit) { yt.selectFeed(yt.selectedFeed) }
    Column(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.padding(start = PageGutter, end = PageGutter, top = 36.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("YouTube", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                yt.accountName?.let { Text(it, color = FetchPalette.TextMuted, modifier = Modifier.padding(bottom = 6.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                YouTubeFeed.entries.forEach { feed ->
                    TabChip(feed.label, selected = yt.selectedFeed == feed, onClick = { yt.selectFeed(feed) })
                }
                Spacer(Modifier.weight(1f))
                if (yt.selectedFeed != YouTubeFeed.SEARCH) {
                    SecondaryButton("Refresh", onClick = { yt.refresh(YouTubeFeedKey.Tab(yt.selectedFeed)) })
                }
            }
            if (yt.selectedFeed == YouTubeFeed.SEARCH) YouTubeSearchBar(yt)
        }
        val key = YouTubeFeedKey.Tab(yt.selectedFeed)
        if (yt.selectedFeed == YouTubeFeed.SEARCH && yt.pages[key] == null) {
            EmptyState("Search YouTube", "Type what you're looking for, then press Enter.")
        } else {
            YouTubeFeedView(state, key, Modifier.fillMaxSize())
        }
    }
    YouTubePlaylistDialog(yt)
}

/** A channel, a playlist or all of a row, opened from the YouTube page. */
@Composable
fun YouTubeBrowseScreen(state: AppState, target: YouTubeBrowseTarget) {
    val yt = state.youtube
    if (!yt.signedIn || state.limits.isRestricted) {
        YouTubeUnavailable(state)
        return
    }
    val key = YouTubeFeedKey.Browse(target)
    LaunchedEffect(target) { yt.ensureLoaded(key) }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(start = PageGutter, end = PageGutter, top = 32.dp)
        ) {
            CircleIconButton(FetchIcons.Back, "Back", onClick = state::back, size = 40.dp)
            Text(
                target.title ?: browseTitle(target),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton("Refresh", onClick = { yt.refresh(key) })
        }
        YouTubeFeedView(state, key, Modifier.fillMaxSize())
    }
    YouTubePlaylistDialog(yt)
}

private fun browseTitle(target: YouTubeBrowseTarget): String = when (target.browseId) {
    YouTubeClient.WATCH_LATER_BROWSE_ID -> "Watch later"
    YouTubeClient.LIKED_BROWSE_ID -> "Liked videos"
    else -> "YouTube"
}

@Composable
private fun YouTubeUnavailable(state: AppState) {
    val yt = state.youtube
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                state.limits.isRestricted -> {
                    Text(tr("YouTube isn't available on this profile"), style = MaterialTheme.typography.titleLarge)
                    Text(tr("Profiles with limits don't have YouTube, as on the TV app."), color = FetchPalette.TextSecondary)
                }
                else -> {
                    Text(tr("You're signed out of YouTube"), style = MaterialTheme.typography.titleLarge)
                    Text(tr("Sign in again in Settings to see your feeds."), color = FetchPalette.TextSecondary)
                    PrimaryButton("Open YouTube settings", icon = null, onClick = {
                        yt.cancelSignIn()
                        state.openSection(Screen.Settings(SettingsPage.YOUTUBE))
                    })
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        label,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) FetchPalette.OnAccent else FetchPalette.TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    selected -> FetchPalette.Accent
                    hovered -> Color.White.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.07f)
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    )
}

/** The search box with YouTube's suggestions while typing, and recent searches before. */
@Composable
private fun YouTubeSearchBar(yt: YouTubeState) {
    val shape = RoundedCornerShape(50)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(FetchPalette.Surface)
                .border(1.dp, FetchPalette.Outline, shape)
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Icon(FetchIcons.Search, contentDescription = null, tint = FetchPalette.TextSecondary, modifier = Modifier.size(22.dp))
            Box(Modifier.weight(1f)) {
                if (yt.searchQuery.isEmpty()) Text(tr("Search YouTube"), color = FetchPalette.TextMuted, fontSize = 17.sp)
                BasicTextField(
                    value = yt.searchQuery,
                    onValueChange = yt::onSearchQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(FetchPalette.Accent),
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            yt.search()
                            true
                        } else false
                    }
                )
            }
            if (yt.searchQuery.isNotEmpty()) CircleIconButton(FetchIcons.Close, "Clear", onClick = { yt.onSearchQueryChange("") }, size = 30.dp)
        }
        val chips = if (yt.searchQuery.isBlank()) yt.recentSearches else yt.suggestions
        if (chips.isNotEmpty()) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = 900.dp)
            ) {
                Text(
                    if (yt.searchQuery.isBlank()) tr("RECENT") else tr("SUGGESTIONS"),
                    style = OverlineStyle,
                    color = FetchPalette.TextMuted,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp)
                )
                chips.forEach { text -> TabChip(text, selected = false, onClick = { yt.search(text) }) }
                if (yt.searchQuery.isBlank()) {
                    Text(
                        tr("Clear recent searches"),
                        color = FetchPalette.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.CenterVertically).clickable(onClick = yt::clearRecentSearches)
                            .pointerHoverIcon(PointerIcon.Hand).padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * One feed or page: a grid when it's one list (subscriptions, history, search, a playlist), rows
 * when YouTube groups it into shelves (Home, a channel). More loads as you scroll.
 */
@Composable
fun YouTubeFeedView(state: AppState, key: YouTubeFeedKey, modifier: Modifier = Modifier) {
    val yt = state.youtube
    val feed = yt.pages[key] ?: YouTubeFeedState(loading = true)
    when {
        feed.loading && feed.shelves.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FetchPalette.Accent, modifier = Modifier.size(36.dp))
        }
        feed.failed && feed.shelves.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(tr("Couldn't load YouTube. Check your connection and try again."), color = FetchPalette.TextSecondary)
                SecondaryButton("Try again", onClick = { yt.refresh(key) })
            }
        }
        feed.shelves.isEmpty() -> EmptyState(
            if (key.feed == YouTubeFeed.SEARCH) "No videos match your search" else "Nothing to show here yet",
            if (key.feed == YouTubeFeed.SEARCH) "Try other words." else "Videos YouTube has for you show up here.",
            modifier
        )
        feed.shelves.size == 1 -> YouTubeGrid(state, key, feed, feed.shelves.single(), modifier)
        else -> YouTubeRows(state, key, feed, modifier)
    }
}

@Composable
private fun YouTubeGrid(state: AppState, key: YouTubeFeedKey, feed: YouTubeFeedState, shelf: YouTubeShelf, modifier: Modifier) {
    val yt = state.youtube
    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = grid.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, shelf.videos.size) { if (nearEnd) yt.loadMore(key) }
    Box(modifier) {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(if (shelf.isShorts) ShortCardWidth else 260.dp),
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 28.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(shelf.links, key = { "link|${it.target}" }) { link -> YouTubeLinkCard(link, onClick = { openLink(state, link) }, fillWidth = true) }
            items(shelf.videos, key = { "video|${it.videoId}" }) { video ->
                YouTubeVideoCard(video, portrait = shelf.isShorts, fillWidth = true, onClick = { yt.play(video) }, menu = { videoMenu(state, video, key) })
            }
            if (feed.loadingMore || feed.loadingShelves.isNotEmpty()) fullWidthItem("more") { LoadingMore() }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(grid),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp, horizontal = 4.dp)
        )
    }
}

@Composable
private fun LoadingMore() {
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = FetchPalette.Accent, modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun YouTubeRows(state: AppState, key: YouTubeFeedKey, feed: YouTubeFeedState, modifier: Modifier) {
    val yt = state.youtube
    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearEnd, feed.shelves.size) { if (nearEnd) yt.loadMore(key) }
    ScrollbarBox(list, modifier) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(feed.shelves, key = { index, shelf -> "$index|${shelf.title}" }) { index, shelf ->
                YouTubeShelfRow(
                    title = shelf.title,
                    videos = shelf.videos,
                    links = shelf.links,
                    shorts = shelf.isShorts,
                    loadingMore = index in feed.loadingShelves,
                    onNearEnd = if (shelf.continuation != null) ({ yt.loadMoreInShelf(key, index) }) else null,
                    onViewAll = shelf.viewAll?.let { target -> { state.navigate(Screen.YouTubeBrowse(target.copy(title = target.title ?: shelf.title))) } },
                    onPlay = { yt.play(it) },
                    onLink = { openLink(state, it) },
                    menu = { videoMenu(state, it, key) }
                )
            }
            if (feed.loadingMore) item(key = "more") { LoadingMore() }
        }
    }
}

/** A titled row of videos (and channel or playlist cards), paged with arrows on hover. */
@Composable
fun YouTubeShelfRow(
    title: String?,
    videos: List<YouTubeVideo>,
    links: List<YouTubeLink> = emptyList(),
    shorts: Boolean = false,
    subtitle: String? = null,
    loadingMore: Boolean = false,
    onNearEnd: (() -> Unit)? = null,
    onViewAll: (() -> Unit)? = null,
    onPlay: (YouTubeVideo) -> Unit,
    onLink: (YouTubeLink) -> Unit = {},
    menu: (YouTubeVideo) -> List<ContextMenuItem> = { emptyList() }
) {
    val rowInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowInteraction.collectIsHoveredAsState()
    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, videos.size) { if (nearEnd) onNearEnd?.invoke() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.hoverable(rowInteraction)) {
        if (title != null || onViewAll != null) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = PageGutter)
            ) {
                title?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = FetchPalette.TextPrimary) }
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FetchPalette.TextMuted, modifier = Modifier.padding(bottom = 2.dp)) }
                if (onViewAll != null) {
                    Text(
                        tr("View all"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (rowHovered) FetchPalette.TextPrimary else FetchPalette.TextMuted,
                        modifier = Modifier.padding(bottom = 2.dp).clickable(onClick = onViewAll).pointerHoverIcon(PointerIcon.Hand)
                    )
                }
            }
        }
        ArrowRow(list, rowHovered) {
            LazyRow(
                state = list,
                contentPadding = PaddingValues(horizontal = PageGutter, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(videos, key = { "video|${it.videoId}" }) { video ->
                    YouTubeVideoCard(video, portrait = shorts, onClick = { onPlay(video) }, menu = { menu(video) })
                }
                items(links, key = { "link|${it.target}" }) { link -> YouTubeLinkCard(link, onClick = { onLink(link) }) }
                if (loadingMore) item(key = "more") {
                    Box(Modifier.width(80.dp).height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = FetchPalette.Accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

/** Arrows at either edge on hover, since a mouse wheel scrolls the page, not the row. */
@Composable
private fun ArrowRow(state: LazyListState, hovered: Boolean, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        content()
        AnimatedVisibility(hovered && state.canScrollBackward, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
            Box(Modifier.width(PageGutter), contentAlignment = Alignment.Center) {
                CircleIconButton(FetchIcons.ChevronLeft, "Scroll left", onClick = { scope.launch { state.animateScrollBy(-widthPx * 0.8f) } }, size = 40.dp)
            }
        }
        AnimatedVisibility(hovered && state.canScrollForward, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            Box(Modifier.width(PageGutter), contentAlignment = Alignment.Center) {
                CircleIconButton(FetchIcons.ChevronRight, "Scroll right", onClick = { scope.launch { state.animateScrollBy(widthPx * 0.8f) } }, size = 40.dp)
            }
        }
    }
}

/** A video: its thumbnail with the length (or LIVE) and how far you got, then title and channel. */
@Composable
fun YouTubeVideoCard(
    video: YouTubeVideo,
    onClick: () -> Unit,
    portrait: Boolean = false,
    fillWidth: Boolean = false,
    width: Dp = if (portrait) ShortCardWidth else VideoCardWidth,
    menu: () -> List<ContextMenuItem> = { emptyList() }
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.04f else 1f, tween(160))
    val corner = RoundedCornerShape(10.dp)
    ContextMenuArea(items = menu) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(width))
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (portrait) 9f / 16f else 16f / 9f)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(corner)
                    .background(FetchPalette.SurfaceRaised)
                    .then(if (hovered) Modifier.border(3.dp, Color.White, corner) else Modifier)
            ) {
                AsyncImage(model = video.thumbnailUrl, contentDescription = video.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                val badge = if (video.isLive) "LIVE" else video.duration
                badge?.let {
                    Text(
                        it,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (video.isLive) LiveRed else Color.Black.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                video.watchedPercent?.takeIf { it > 0 }?.let { percent ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(percent.coerceIn(0, 100) / 100f).background(LiveRed))
                    }
                }
            }
            Text(
                video.title,
                color = if (hovered) Color.White else FetchPalette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
            val byline = listOfNotNull(video.channelName, video.detail).joinToString(" · ")
            if (byline.isNotBlank()) {
                Text(byline, color = FetchPalette.TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A channel (round picture), a playlist or a library shortcut. */
@Composable
fun YouTubeLinkCard(link: YouTubeLink, onClick: () -> Unit, fillWidth: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val channel = link.kind == YouTubeLinkKind.CHANNEL
    val title = link.title ?: when (link.kind) {
        YouTubeLinkKind.WATCH_LATER -> "Watch later"
        YouTubeLinkKind.LIKED -> "Liked videos"
        else -> "Playlist"
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = if (channel) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(if (channel) 180.dp else VideoCardWidth))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        val shape = if (channel) CircleShape else RoundedCornerShape(10.dp)
        Box(
            Modifier
                .then(if (channel) Modifier.size(140.dp) else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                .clip(shape)
                .background(FetchPalette.SurfaceRaised)
                .then(if (hovered) Modifier.border(3.dp, Color.White, shape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (link.thumbnailUrl != null) {
                AsyncImage(model = link.thumbnailUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(if (channel) YouTubeIcon else FetchIcons.List, contentDescription = null, tint = FetchPalette.TextSecondary, modifier = Modifier.size(40.dp))
            }
        }
        Text(title, color = if (hovered) Color.White else FetchPalette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        link.subtitle?.let { Text(it, color = FetchPalette.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

private fun openLink(state: AppState, link: YouTubeLink) {
    val title = link.title ?: when (link.kind) {
        YouTubeLinkKind.WATCH_LATER -> "Watch later"
        YouTubeLinkKind.LIKED -> "Liked videos"
        else -> null
    }
    state.navigate(Screen.YouTubeBrowse(link.target.copy(title = link.target.title ?: title)))
}

/** The TV's long-press menu for a video, as a right-click menu. */
private fun videoMenu(state: AppState, video: YouTubeVideo, key: YouTubeFeedKey?): List<ContextMenuItem> = buildList {
    val yt = state.youtube
    add(ContextMenuItem(tr("Play")) { yt.play(video) })
    val channels = video.channels.ifEmpty { listOfNotNull(video.channelId?.let { com.fetch.desktop.youtube.YouTubeChannelRef(it, video.channelName) }) }
    channels.forEach { channel ->
        val name = channel.name ?: video.channelName
        add(ContextMenuItem(if (name != null && channels.size > 1) tr("Go to %1\$s", name) else tr("Go to channel")) {
            state.navigate(Screen.YouTubeBrowse(YouTubeBrowseTarget(channel.channelId, title = name)))
        })
    }
    val onWatchLater = (key as? YouTubeFeedKey.Browse)?.target?.browseId == YouTubeClient.WATCH_LATER_BROWSE_ID
    add(ContextMenuItem(if (onWatchLater) tr("Remove from Watch later") else tr("Save to Watch later")) { yt.setWatchLater(video, save = !onWatchLater) })
    add(ContextMenuItem(tr("Save to playlist…")) { yt.openPlaylistPicker(video) })
    video.removeFromHistoryToken?.let { add(ContextMenuItem(tr("Remove from watch history")) { yt.removeFromHistory(video) }) }
    video.notInterestedToken?.let { add(ContextMenuItem(tr("Not interested")) { yt.markNotInterested(video) }) }
    add(ContextMenuItem(tr("Open on YouTube")) { state.openInBrowser(video.watchUrl) })
}

/** "Save to playlist": the channel's playlists with a mark on those holding the video, and New playlist. */
@Composable
fun YouTubePlaylistDialog(yt: YouTubeState) {
    val picker = yt.playlistPicker ?: return
    var creating by remember(picker.video.videoId) { mutableStateOf(false) }
    var name by remember(picker.video.videoId) { mutableStateOf("") }
    var privacy by remember(picker.video.videoId) { mutableStateOf(YouTubePlaylistPrivacy.PRIVATE) }
    AlertDialog(
        onDismissRequest = yt::closePlaylistPicker,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(tr("Save to playlist"), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.widthIn(min = 360.dp)) {
                Text(picker.video.title, color = FetchPalette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                val options = picker.options
                when {
                    picker.failed -> Text(tr("Couldn't load your playlists. Try again later."), color = FetchPalette.TextMuted)
                    options == null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                        Text(tr("Loading your playlists…"), color = FetchPalette.TextMuted)
                    }
                    options.isEmpty() -> Text(tr("You don't have any playlists yet."), color = FetchPalette.TextMuted)
                    else -> options.forEach { option ->
                        SettingsRow(option.title, onClick = { yt.togglePlaylist(option) }) {
                            if (option.containsVideo) Icon(FetchIcons.Check, contentDescription = tr("Saved"), tint = FetchPalette.Accent)
                        }
                    }
                }
                if (creating) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(FetchPalette.Accent),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FetchPalette.Background).padding(12.dp)) {
                                    if (name.isEmpty()) Text(tr("Name"), color = FetchPalette.TextMuted)
                                    inner()
                                }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            YouTubePlaylistPrivacy.entries.forEach { p -> TabChip(p.label, selected = privacy == p, onClick = { privacy = p }) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                PrimaryButton("Create", icon = null, enabled = name.isNotBlank(), onClick = {
                    yt.createPlaylist(name, privacy)
                    creating = false
                    name = ""
                })
            } else {
                SecondaryButton("New playlist…", onClick = { creating = true })
            }
        },
        dismissButton = { SecondaryButton("Done", onClick = yt::closePlaylistPicker) }
    )
}

/** Home's "YouTube subscriptions" row (Settings › YouTube › YouTube row on Home), after Continue Watching. */
@Composable
fun YouTubeHomeRow(state: AppState) {
    val yt = state.youtube
    if (!yt.homeRowShown) return
    YouTubeShelfRow(
        title = tr("YouTube subscriptions"),
        videos = yt.homeRow,
        onPlay = { yt.play(it) },
        onViewAll = {
            yt.selectFeed(YouTubeFeed.SUBSCRIPTIONS)
            state.openSection(Screen.YouTube)
        },
        menu = { videoMenu(state, it, null) }
    )
}
