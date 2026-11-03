package com.fetch.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.fetch.desktop.CatalogRow
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.launch

/** Horizontal page padding, shared by headings and rows so everything lines up. */
val PageGutter = 48.dp

// --- Scrollbars ---

/** A page with a grabbable scrollbar on its right edge. */
@Composable
fun ScrollbarBox(state: LazyListState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        content()
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp, horizontal = 4.dp)
        )
    }
}

@Composable
fun ScrollbarBox(state: ScrollState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        content()
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp, horizontal = 4.dp)
        )
    }
}

// --- Buttons and chips ---

private fun Modifier.handCursor() = pointerHoverIcon(PointerIcon.Hand)

/** The red pill used for Play. [detail] is a second line, such as the best source's spec line. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = FetchIcons.Play,
    detail: String? = null,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container = when {
        !enabled -> FetchPalette.SurfaceHigh
        hovered -> FetchPalette.Accent.copy(alpha = 0.88f)
        else -> FetchPalette.Accent
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .then(if (hovered && enabled) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .handCursor()
            .padding(start = 22.dp, end = 28.dp, top = if (detail != null) 10.dp else 13.dp, bottom = if (detail != null) 10.dp else 13.dp)
    ) {
        val content = if (enabled) FetchPalette.OnAccent else Color.White
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
        Column {
            Text(text, color = content, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
            if (detail != null) {
                Text(
                    detail,
                    color = content.copy(alpha = 0.92f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A dark translucent pill for secondary actions ("All sources", "More info"). */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (hovered && enabled) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f))
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .handCursor()
            .padding(horizontal = 22.dp, vertical = 13.dp)
            .alpha(if (enabled) 1f else 0.4f)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
    }
}

/** A round icon-only button, like the trailer button on the TV detail page. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (hovered && enabled) Color.White.copy(alpha = 0.24f) else Color.Black.copy(alpha = 0.55f))
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .handCursor()
            .alpha(if (enabled) 1f else 0.35f)
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** A small outlined label such as "4K" or "TV-14". [filled] is for the one that matters most ("Cached"). */
@Composable
fun SpecChip(text: String, filled: Boolean = false) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (filled) Color.Black else FetchPalette.TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (filled) Modifier.background(Color.White)
                else Modifier.border(1.dp, FetchPalette.TextMuted, RoundedCornerShape(6.dp))
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
fun ImdbBadge(rating: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "IMDb",
            color = Color.Black,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(FetchPalette.Imdb).padding(horizontal = 5.dp, vertical = 1.dp)
        )
        Text("%.1f".format(rating), color = FetchPalette.TextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

/** Facts joined by a dot, as on the TV hero: "Drama • 2024 • IMDb 8.1". */
@Composable
fun FactsLine(facts: List<String>, imdbRating: Float?, modifier: Modifier = Modifier) {
    if (facts.isEmpty() && imdbRating == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) Dot()
            Text(fact, color = FetchPalette.TextSecondary, fontSize = 16.sp, maxLines = 1)
        }
        if (imdbRating != null) {
            if (facts.isNotEmpty()) Dot()
            ImdbBadge(imdbRating)
        }
    }
}

@Composable
private fun Dot() {
    Box(Modifier.size(4.dp).clip(CircleShape).background(FetchPalette.TextMuted))
}

// --- Posters and rows ---

data class CardSize(val width: Dp, val height: Dp)

fun cardSize(shape: PosterShape): CardSize = when (shape) {
    PosterShape.POSTER -> CardSize(160.dp, 240.dp)
    PosterShape.LANDSCAPE -> CardSize(300.dp, 169.dp)
    PosterShape.SQUARE -> CardSize(200.dp, 200.dp)
}

/** What a poster's right-click menu offers and whether it's watched; provided by the app. */
interface PosterActions {
    fun menu(item: MetaPreview): List<androidx.compose.foundation.ContextMenuItem>
    fun isWatched(item: MetaPreview): Boolean
}

val LocalPosterActions = androidx.compose.runtime.staticCompositionLocalOf<PosterActions?> { null }

/**
 * A poster with its title and year. Hovering lifts it and draws the white focus ring the TV app
 * uses; [onHover] lets the page show the hovered title in its hero.
 */
@Composable
fun PosterCard(
    item: MetaPreview,
    onClick: () -> Unit,
    onHover: (MetaPreview) -> Unit = {},
    shape: PosterShape = item.posterShape,
    showLabels: Boolean = true,
    /** Fills the cell's width instead of the shape's fixed width, for grids. */
    fillWidth: Boolean = false
) {
    val size = cardSize(shape)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    if (hovered) LaunchedEffect(item.id) { onHover(item) }
    val scale by animateFloatAsState(if (hovered) 1.05f else 1f, tween(160))
    val corner = RoundedCornerShape(10.dp)
    val actions = LocalPosterActions.current
    androidx.compose.foundation.ContextMenuArea(items = { actions?.menu(item).orEmpty() }) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(size.width))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .handCursor()
    ) {
        Box(
            Modifier
                .then(
                    if (fillWidth) Modifier.fillMaxWidth().aspectRatio(size.width / size.height)
                    else Modifier.size(size.width, size.height)
                )
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(corner)
                .background(FetchPalette.SurfaceRaised)
                .then(if (hovered) Modifier.border(3.dp, Color.White, corner) else Modifier)
        ) {
            // The name stays underneath, so a missing or broken image still shows what this is.
            ArtPlaceholder(item.name)
            val image = when (shape) {
                PosterShape.LANDSCAPE -> item.landscapePoster ?: item.background ?: item.poster
                else -> item.poster
            }
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (actions?.isWatched(item) == true) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(FetchIcons.Check, contentDescription = "Watched", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (showLabels) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (hovered) Color.White else FetchPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                item.releaseInfo.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = FetchPalette.TextMuted,
                maxLines = 1
            )
        }
    }
    }
}

/** What a card shows with no artwork: a soft gradient, an icon and the name. */
@Composable
fun ArtPlaceholder(name: String) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF2B2B33), Color(0xFF18181D)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Icon(FetchIcons.Movie, contentDescription = null, tint = FetchPalette.TextMuted, modifier = Modifier.size(28.dp))
            Text(
                name,
                color = FetchPalette.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One catalog as a titled, horizontally scrolling row. Hovering it shows arrows at either edge
 * that page through it, since a mouse wheel only scrolls the page.
 */
@Composable
fun CatalogRowView(
    row: CatalogRow,
    onOpen: (MetaPreview) -> Unit,
    onHover: (MetaPreview) -> Unit = {},
    title: String = row.title,
    subtitle: String? = row.addon.displayName,
    showLabels: Boolean = true,
    onSeeAll: (() -> Unit)? = null
) = MediaRowView(title, subtitle, row.items, row.loading, row.error, onOpen, onHover, showLabels, onSeeAll)

/** A titled row of posters from anywhere: an addon catalog, or a media server library. */
@Composable
fun MediaRowView(
    title: String,
    subtitle: String?,
    items: List<MetaPreview>,
    loading: Boolean,
    error: String?,
    onOpen: (MetaPreview) -> Unit,
    onHover: (MetaPreview) -> Unit = {},
    showLabels: Boolean = true,
    /** Opens the whole catalog as a grid ("See all", as on TV). */
    onSeeAll: (() -> Unit)? = null
) {
    val rowInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowInteraction.collectIsHoveredAsState()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.hoverable(rowInteraction)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = PageGutter)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = FetchPalette.TextPrimary)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchPalette.TextMuted,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            if (onSeeAll != null && items.isNotEmpty()) {
                Text(
                    "See all",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rowHovered) FetchPalette.TextPrimary else FetchPalette.TextMuted,
                    modifier = Modifier.padding(bottom = 2.dp).clickable(onClick = onSeeAll).pointerHoverIcon(PointerIcon.Hand)
                )
            }
        }
        when {
            loading -> SkeletonRow()
            error != null -> Text(
                "Couldn't load this row ($error)",
                color = FetchPalette.TextMuted,
                modifier = Modifier.padding(horizontal = PageGutter)
            )
            items.isEmpty() -> Text(
                "Nothing here",
                color = FetchPalette.TextMuted,
                modifier = Modifier.padding(horizontal = PageGutter)
            )
            else -> {
                // Every card in a row has the row's shape, so a stray landscape item doesn't break the line.
                val shape = items.groupingBy { it.posterShape }.eachCount().maxByOrNull { it.value }?.key
                    ?: PosterShape.POSTER
                PagedRow(hovered = rowHovered, cardHeight = cardSize(shape).height) { state ->
                    LazyRow(
                        state = state,
                        contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            PosterCard(item, onClick = { onOpen(item) }, onHover = onHover, shape = shape, showLabels = showLabels)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PagedRow(hovered: Boolean, cardHeight: Dp, content: @Composable (LazyListState) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        content(state)
        val arrowModifier = Modifier.padding(top = 8.dp).height(cardHeight).width(PageGutter)
        AnimatedVisibility(
            visible = hovered && state.canScrollBackward,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Box(arrowModifier, contentAlignment = Alignment.Center) {
                CircleIconButton(FetchIcons.ChevronLeft, "Scroll left", onClick = {
                    scope.launch { state.animateScrollBy(-widthPx * 0.8f) }
                }, size = 40.dp)
            }
        }
        AnimatedVisibility(
            visible = hovered && state.canScrollForward,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Box(arrowModifier, contentAlignment = Alignment.Center) {
                CircleIconButton(FetchIcons.ChevronRight, "Scroll right", onClick = {
                    scope.launch { state.animateScrollBy(widthPx * 0.8f) }
                }, size = 40.dp)
            }
        }
    }
}

/** Grey cards standing in for a row that is still loading. */
@Composable
fun SkeletonRow(count: Int = 10) {
    val size = cardSize(PosterShape.POSTER)
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.padding(horizontal = PageGutter, vertical = 8.dp)
    ) {
        repeat(count) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(size.width, size.height).clip(RoundedCornerShape(10.dp)).background(FetchPalette.Surface))
                Box(Modifier.size(size.width * 0.7f, 14.dp).clip(RoundedCornerShape(4.dp)).background(FetchPalette.Surface))
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, color = FetchPalette.TextSecondary, textAlign = TextAlign.Center)
        }
    }
}
