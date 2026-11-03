package com.fetch.desktop.ui

import com.fetch.desktop.poster.withCustomPosterUrls
import com.fetch.desktop.poster.posterPatternFor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.collections.FolderTab
import com.fetch.desktop.collections.FolderView
import com.fetch.desktop.isUnreleased
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.FolderViewMode
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// --- Home: a collection's row of folder tiles ---

/** A folder tile's size: the TV's shapes on the poster width (landscape is 16:9, square 1:1). */
fun folderTileSize(shape: PosterShape): CardSize {
    val width = cardSize(PosterShape.POSTER).width
    return when (shape) {
        PosterShape.POSTER -> cardSize(PosterShape.POSTER)
        PosterShape.LANDSCAPE -> CardSize(width * (16f / 9f), width)
        PosterShape.SQUARE -> CardSize(width, width)
    }
}

/** A collection on Home: its title and a row of its folders, as the TV's `CollectionRowSection`. */
@Composable
fun CollectionRowView(collection: Collection, onOpen: (CollectionFolder) -> Unit, onHover: (CollectionFolder) -> Unit = {}) {
    val rowInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowInteraction.collectIsHoveredAsState()
    val tallest = collection.folders.maxOfOrNull { folderTileSize(it.tileShape).height } ?: 0.dp
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.hoverable(rowInteraction)) {
        Text(
            collection.title,
            style = MaterialTheme.typography.titleLarge,
            color = FetchPalette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = PageGutter)
        )
        FolderRow(collection, rowHovered, tallest, onOpen, onHover)
    }
}

@Composable
private fun FolderRow(collection: Collection, rowHovered: Boolean, tallest: Dp, onOpen: (CollectionFolder) -> Unit, onHover: (CollectionFolder) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            itemsIndexed(collection.folders, key = { i, f -> "${f.id}#$i" }) { _, folder ->
                FolderTile(folder, onClick = { onOpen(folder) }, onHover = onHover)
            }
        }
        val arrow = Modifier.padding(top = 8.dp).height(tallest).width(PageGutter)
        AnimatedVisibility(rowHovered && listState.canScrollBackward, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Box(arrow, contentAlignment = Alignment.Center) {
                CircleIconButton(FetchIcons.ChevronLeft, "Scroll left", onClick = { scope.launch { listState.animateScrollBy(-widthPx * 0.8f) } }, size = 40.dp)
            }
        }
        AnimatedVisibility(rowHovered && listState.canScrollForward, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            Box(arrow, contentAlignment = Alignment.Center) {
                CircleIconButton(FetchIcons.ChevronRight, "Scroll right", onClick = { scope.launch { listState.animateScrollBy(widthPx * 0.8f) } }, size = 40.dp)
            }
        }
    }
}

/** One folder: its cover image, else its emoji, else its initials; the title on it unless hidden. */
@Composable
fun FolderTile(folder: CollectionFolder, onClick: () -> Unit, modifier: Modifier = Modifier, onHover: (CollectionFolder) -> Unit = {}) {
    val size = folderTileSize(folder.tileShape)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    if (hovered) LaunchedEffect(folder.id) { onHover(folder) }
    val scale by animateFloatAsState(if (hovered) 1.05f else 1f, tween(160))
    val corner = RoundedCornerShape(10.dp)
    Box(
        modifier
            .size(size.width, size.height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(corner)
            .background(FetchPalette.SurfaceRaised)
            .then(if (hovered) Modifier.border(3.dp, Color.White, corner) else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        val image = folder.coverImageUrl?.takeIf { it.isNotBlank() }?.trim()
        // A focus GIF plays on hover, over the cover, as on TV.
        val gif = folder.focusGifUrl?.takeIf { hovered && folder.focusGifEnabled && it.isNotBlank() }
        when {
            image != null -> AsyncImage(image, contentDescription = folder.title, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            !folder.coverEmoji.isNullOrBlank() -> Text(folder.coverEmoji.orEmpty(), fontSize = 48.sp, modifier = Modifier.align(Alignment.Center))
            else -> Text(
                folder.title.take(2).uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                color = FetchPalette.TextSecondary,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        if (gif != null) AsyncImage(gif, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        if (!folder.hideTitle) {
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    folder.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// --- A folder's page ---

/** The titles a profile may see, minus unreleased ones when Hide unreleased is on. */
private fun AppState.visible(items: List<MetaPreview>): List<MetaPreview> =
    items.filter { allowed(it) && !(settings.hideUnreleased && isUnreleased(it)) }
        .withCustomPosterUrls(settings.layout.posterPatternFor(com.fetch.desktop.poster.CustomPosterScreen.COLLECTIONS))

/**
 * A folder's page, by its collection's view mode, as the TV's `FolderDetailScreen`: Tabbed grid
 * (an "All" tab and one per source, each loading more as you scroll), Rows (one row per source),
 * or Follow layout (rows under a Home-style hero).
 */
@Composable
fun FolderScreen(state: AppState, screen: Screen.Folder) {
    val view = state.collections.folder
    if (view == null || view.collection.id != screen.collectionId || view.folder.id != screen.folderId) {
        if (state.collections.find(screen.collectionId)?.folders?.any { it.id == screen.folderId } == true) {
            LaunchedEffect(screen) { state.collections.openFolder(screen.collectionId, screen.folderId) }
            CenteredProgress()
        } else {
            EmptyState("This folder is gone", "Its collection was changed or deleted.")
        }
        return
    }
    // A source's "See all" from Rows shows that tab as a grid.
    var gridTab by remember(screen) { mutableStateOf<Int?>(null) }
    when {
        gridTab != null -> FolderGrid(state, view, gridTab!!, onBack = { gridTab = null })
        view.collection.viewMode == FolderViewMode.TABBED_GRID -> FolderGrid(state, view, view.selected, onBack = null)
        else -> FolderRows(state, view, hero = view.collection.viewMode == FolderViewMode.FOLLOW_LAYOUT, onSeeAll = { gridTab = it })
    }
}

@Composable
private fun FolderHeading(state: AppState, view: FolderView, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircleIconButton(FetchIcons.Back, "Back", onClick = onBack, size = 40.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (view.collection.title.isNotBlank()) Text(view.collection.title.uppercase(), style = OverlineStyle, color = FetchPalette.TextMuted)
            val logo = view.folder.titleLogoUrl?.takeIf { it.isNotBlank() }
            if (logo != null) TitleArt(view.folder.title, logo, maxLogoHeight = 72.dp, fontSize = 32)
            else Text(view.folder.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
    }
}

@Composable
private fun FolderTabs(view: FolderView, selected: Int, onSelect: (Int) -> Unit) {
    if (view.tabs.size < 2) return
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 18.dp)
    ) {
        view.tabs.forEachIndexed { i, tab ->
            val on = i == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Text(
                tab.label,
                fontSize = 14.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) FetchPalette.OnAccent else FetchPalette.TextPrimary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) FetchPalette.Accent else if (hovered) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f))
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(i) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun FolderGrid(state: AppState, view: FolderView, index: Int, onBack: (() -> Unit)?) {
    val tab = view.tabs.getOrNull(index) ?: view.tabs.firstOrNull() ?: return
    val grid = remember(view.folder.id, index) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    val items = state.visible(tab.items)
    LaunchedEffect(grid, index, view.folder.id) {
        snapshotFlow {
            val info = grid.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) state.collections.loadMore(index)
        }
    }
    val backdrop = view.folder.heroBackdropUrl?.takeIf { it.isNotBlank() } ?: view.collection.backdropImageUrl?.takeIf { it.isNotBlank() }
    Box(Modifier.fillMaxSize()) {
        if (backdrop != null) Backdrop(backdrop, height = 420.dp)
        FolderHeroVideo(state, view.folder, 420.dp)
        PosterGrid(
            items = items,
            state = grid,
            onOpen = { state.navigate(Screen.Detail(it)) },
            showLabels = state.settings.showPosterLabels,
            modifier = Modifier.fillMaxSize(),
            header = {
                fullWidthItem("folder-heading") {
                    Column {
                        FolderHeading(state, view, onBack = onBack ?: state::back)
                        if (onBack == null) FolderTabs(view, index, onSelect = state.collections::selectTab)
                    }
                }
                if (items.isEmpty()) fullWidthItem("folder-state") { TabState(tab) }
            },
            footer = {
                if (items.isNotEmpty()) fullWidthItem("folder-more") {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            tab.loading -> CircularProgressIndicator(color = FetchPalette.Accent, modifier = Modifier.size(28.dp))
                            tab.hasMore -> SecondaryButton("Load more", onClick = { state.collections.loadMore(index) })
                            else -> Unit
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun TabState(tab: FolderTab) {
    when {
        tab.loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FetchPalette.Accent)
        }
        tab.error != null -> EmptyState("Couldn't load ${tab.label}", tab.error, Modifier.fillMaxWidth().padding(top = 60.dp))
        else -> EmptyState("Nothing here", "This source has no titles right now.", Modifier.fillMaxWidth().padding(top = 60.dp))
    }
}

@Composable
private fun FolderRows(state: AppState, view: FolderView, hero: Boolean, onSeeAll: (Int) -> Unit) {
    val listState = rememberLazyListState()
    var hovered by remember(view.folder.id) { mutableStateOf<MetaPreview?>(null) }
    val sources = view.tabs.withIndex().filter { !it.value.isAll }
    val first = sources.asSequence().flatMap { state.visible(it.value.items) }.firstOrNull { it.background != null }
    val featured = if (hero) hovered ?: first else null
    val backdrop = featured?.backdropUrl
        ?: view.folder.heroBackdropUrl?.takeIf { it.isNotBlank() }
        ?: view.collection.backdropImageUrl?.takeIf { it.isNotBlank() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * 0.5f).coerceIn(300.dp, 540.dp)
        Backdrop(backdrop, height = if (hero) heroHeight + 120.dp else 420.dp)
        // The folder's hero video, until a title is pointed at.
        if (hovered == null) FolderHeroVideo(state, view.folder, if (hero) heroHeight + 120.dp else 420.dp)
        ScrollbarBox(listState, Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 40.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "folder-heading") {
                    Box(Modifier.padding(horizontal = PageGutter)) { FolderHeading(state, view, onBack = state::back) }
                }
                if (hero) item(key = "folder-hero") { FolderHero(state, featured, heroHeight - 120.dp) }
                sources.forEach { (index, tab) ->
                    item(key = "folder-row-$index") {
                        MediaRowView(
                            title = tab.label,
                            subtitle = tab.typeLabel.takeIf { state.settings.showAddonName },
                            items = state.visible(tab.items),
                            loading = tab.loading && tab.items.isEmpty(),
                            error = tab.error,
                            onOpen = { state.navigate(Screen.Detail(it)) },
                            onHover = { hovered = it },
                            showLabels = state.settings.showPosterLabels,
                            onSeeAll = { onSeeAll(index) }
                        )
                    }
                }
            }
        }
    }
}

/** Follow layout's hero: the hovered title's art, facts and Play, as on Home. */
@Composable
private fun FolderHero(state: AppState, item: MetaPreview?, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height.coerceAtLeast(160.dp))) {
        if (item == null) return@Box
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = PageGutter).widthIn(max = 760.dp)
        ) {
            TitleArt(item.name, item.logo, maxLogoHeight = 100.dp, fontSize = 44)
            FactsLine(listOfNotNull(item.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() }, item.releaseInfo), item.imdbRating)
            item.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = FetchPalette.TextPrimary.copy(alpha = 0.9f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item.type == ContentType.SERIES) {
                    PrimaryButton("Episodes", onClick = { state.navigate(Screen.Detail(item)) })
                } else {
                    PrimaryButton("Play", onClick = { state.navigate(Screen.Detail(item, autoPlay = true)) })
                    SecondaryButton("More info", onClick = { state.navigate(Screen.Detail(item)) }, icon = FetchIcons.Info)
                }
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}
