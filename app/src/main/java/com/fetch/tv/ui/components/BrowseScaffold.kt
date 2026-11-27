package com.fetch.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.dpadVerticalFastScroll
import kotlinx.coroutines.launch
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.core.util.runSuspendCatching

/**
 * One browse layout for Discover, the Library and See all (#850): a header (title, one pill-tab
 * row, actions, the clock), the filter row and a summary line, then the grid. The filters and the
 * summary fold away while [collapsed] (the grid scrolled past its first row), leaving the tab row.
 *
 * [content] is handed the height the grid has with the header open, so cards can be fitted to
 * it ([browseFittedCardWidth]) without changing size when the header folds. [layoutKey] (a tab, a
 * filter set) starts that measurement afresh, for a header that is shorter or taller there.
 */
@Composable
fun BrowseScaffold(
    title: String?,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    layoutKey: Any? = null,
    tabsDescription: String? = null,
    tabs: (@Composable RowScope.() -> Unit)? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    filters: (@Composable () -> Unit)? = null,
    summary: String? = null,
    /** The time at the end of the header, as on Home; off where the app's own top bar has one. */
    showClock: Boolean = title != null,
    /**
     * The hero band (#852): [heroBackdrop] full bleed behind the header and [hero] (the focused
     * title's logo, meta line and synopsis) between the tabs and the filters. It folds with the
     * filters, and is left out of the cards' fitting, so it never shrinks the posters.
     */
    heroBackdrop: String? = null,
    /** The focused title's poster, whose colour washes the band until a backdrop has loaded. */
    heroPoster: String? = null,
    hero: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.(gridViewport: Dp) -> Unit
) {
    val density = LocalDensity.current
    var totalHeightPx by remember { mutableIntStateOf(0) }
    // The open header's height, for this layout: the tallest seen while it is open, so a fold or
    // its animation never shrinks the fitted cards' viewport.
    var openHeaderHeightPx by remember(layoutKey) { mutableIntStateOf(0) }
    var heroHeightPx by remember(layoutKey, hero != null) { mutableIntStateOf(0) }
    val fullMotion = FetchTheme.fullMotion
    val foldEnter = if (fullMotion) expandVertically(FetchMotion.enterTween()) + fadeIn(FetchMotion.enterTween()) else fadeIn(snapSpec())
    val foldExit = if (fullMotion) shrinkVertically(FetchMotion.exitTween()) + fadeOut(FetchMotion.exitTween()) else fadeOut(snapSpec())
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background)
            .onSizeChanged { totalHeightPx = it.height }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        if (hero != null) {
            BrowseHeroArt(visible = !collapsed, backdrop = heroBackdrop, poster = heroPoster, modifier = Modifier.matchParentSize())
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = FetchTheme.spacing.screen.horizontal,
                    end = FetchTheme.spacing.screen.horizontal,
                    top = FetchTheme.spacing.screen.vertical
                )
                .onSizeChanged { if (!collapsed && it.height > openHeaderHeightPx) openHeaderHeightPx = it.height },
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = FetchTheme.textStyles.sectionTitle,
                        color = FetchTheme.colors.TextPrimary,
                        maxLines = 1
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .focusSafeHorizontalScroll()
                        .then(if (tabsDescription != null) Modifier.semantics { contentDescription = tabsDescription } else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs?.invoke(this)
                }
                headerActions()
                if (showClock) ClockText()
            }
            if (filters != null || summary != null || hero != null) {
                AnimatedVisibility(visible = !collapsed, enter = foldEnter, exit = foldExit) {
                    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                        if (hero != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = FetchTheme.spacing.sm)
                                    .onSizeChanged { if (!collapsed && it.height > heroHeightPx) heroHeightPx = it.height }
                            ) {
                                hero()
                            }
                        }
                        filters?.invoke()
                        if (summary != null) BrowseSummaryLine(summary)
                    }
                }
            }
        }
        }
        val viewport = with(density) {
            if (totalHeightPx <= 0 || openHeaderHeightPx <= 0) Dp.Hairline
            // The band (and its padding) isn't counted: it folds away, and the cards keep the
            // size they would have without it.
            else (totalHeightPx - openHeaderHeightPx + heroHeightPx + heroPaddingPx(hero != null)).toDp() - FetchTheme.spacing.xs
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = FetchTheme.spacing.xs)
        ) {
            content(viewport)
        }
    }
}

private fun <T> snapSpec() = androidx.compose.animation.core.snap<T>()

/** The band's art behind the header, fading out as the header folds (#852). */
@Composable
private fun BrowseHeroArt(visible: Boolean, backdrop: String?, poster: String?, modifier: Modifier) {
    val fullMotion = FetchTheme.fullMotion
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(if (fullMotion) FetchMotion.enterTween() else snapSpec()),
        exit = fadeOut(if (fullMotion) FetchMotion.exitTween() else snapSpec())
    ) {
        com.fetch.tv.ui.components.hero.BrowseHeroBackdrop(backdrop = backdrop, poster = poster, modifier = Modifier.fillMaxSize())
    }
}

/** The band's own vertical padding and the gap after it, in px, which the fitting also leaves out. */
@Composable
private fun heroPaddingPx(hasHero: Boolean): Int = if (!hasHero) 0 else with(LocalDensity.current) {
    (FetchTheme.spacing.sm * 3).roundToPx()
}

/** "47 titles · Trakt" / "120 titles · 7 catalogs" under the filters. */
@Composable
fun BrowseSummaryLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FetchTheme.colors.TextSecondary,
        maxLines = 1,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/**
 * One section in the header's pill-tab row (Discover's Movies / Series, the Library's Watchlist /
 * In progress): the shared chip, with no check icon. The selected one takes [selectedFocusRequester].
 */
@Composable
fun BrowseTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedFocusRequester: FocusRequester? = null,
    focusEnabled: Boolean = true
) {
    FetchChip(
        label = label,
        selected = selected,
        focusEnabled = focusEnabled,
        onClick = onClick,
        modifier = Modifier
            .then(if (selected && selectedFocusRequester != null) Modifier.focusRequester(selectedFocusRequester) else Modifier)
            .semantics { this.selected = selected }
    )
}

/** Paddings, gaps and sizes every browse grid shares (#850). */
object BrowseDefaults {
    /** The empty and error states' height, on every browse screen. */
    val EmptyStateHeight: Dp = 300.dp

    /** Rows of 2:3 posters, or of the shorter landscape cards, that show when a grid opens. */
    const val POSTER_FITTED_ROWS = 2
    const val LANDSCAPE_FITTED_ROWS = 3

    /** Room over the first row for a focused card's growth and focus ring. */
    val gridTopPadding: Dp @Composable get() = FetchTheme.spacing.md

    /** [gridTopPadding] over the first row, and room under the last for the selection bar. */
    @Composable
    fun gridPadding(extraBottom: Dp = Dp.Hairline): PaddingValues = PaddingValues(
        start = FetchTheme.spacing.screen.horizontal,
        end = FetchTheme.spacing.screen.horizontal,
        top = gridTopPadding,
        bottom = FetchTheme.spacing.xxl + extraBottom
    )

    val columnGap: Dp @Composable get() = FetchTheme.spacing.md
    val rowGap: Dp @Composable get() = FetchTheme.spacing.lg

    /** A card's title (and gap) under its artwork, for fitting rows. */
    val labelAllowance: Dp @Composable get() = FetchTheme.spacing.sm + FetchTheme.spacing.xl
}

/** The grid key and focus key of a title on any browse screen ("movie:tt123"). */
fun browseItemKey(type: String, id: String): String = "$type:$id"

/**
 * The poster width that lets [rows] full rows of cards, with their titles, show below a [header]
 * in a [viewport] of that height: at most [preferred] (the size set in Layout), and no smaller
 * than 70% of it, so a very short window scrolls rather than shrinking posters to stamps. Before
 * the viewport is measured it is [preferred].
 *
 * [cardHeightPerWidth] is how tall a card is for each unit of poster width: 1.5 for a 2:3 poster;
 * a landscape card is drawn as wide as that poster is tall, at 16:9, so it is much shorter (#848).
 */
fun browseFittedCardWidth(
    preferred: Dp,
    viewport: Dp,
    header: Dp = Dp.Hairline,
    topPadding: Dp,
    gap: Dp,
    labelAllowance: Dp,
    rows: Int = BrowseDefaults.POSTER_FITTED_ROWS,
    cardHeightPerWidth: Float = BROWSE_POSTER_HEIGHT_PER_WIDTH
): Dp {
    if (viewport <= Dp.Hairline) return preferred
    // The header is followed by a gap, and each row by its title and a gap.
    val perRow = (viewport - topPadding - header - gap * rows) / rows
    val fitted = (perRow - labelAllowance) / cardHeightPerWidth
    return minOf(preferred, maxOf(fitted, preferred * MIN_FITTED_POSTER_SCALE))
}

/** Card height per unit of the poster width set in Layout, for posters or landscape cards. */
fun browseCardHeightPerWidth(landscape: Boolean): Float =
    if (landscape) {
        // GridContentCard draws a landscape card as wide as the poster would be tall.
        BROWSE_POSTER_HEIGHT_PER_WIDTH / PosterShape.LANDSCAPE.aspectRatio()
    } else {
        BROWSE_POSTER_HEIGHT_PER_WIDTH
    }

private const val BROWSE_POSTER_HEIGHT_PER_WIDTH = 1.5f
private const val MIN_FITTED_POSTER_SCALE = 0.7f

/**
 * The card style of a browse grid: the size set in Layout, fitted to [gridViewport] so two rows of
 * posters (three of landscape cards) show on entry, with the shared corners and resting border.
 */
@Composable
fun rememberBrowseCardStyle(preferredWidthDp: Int, cornerRadiusDp: Int, gridViewport: Dp): PosterCardStyle {
    val landscape = LocalLandscapePosterMode.current
    val width = browseFittedCardWidth(
        preferred = preferredWidthDp.dp,
        viewport = gridViewport,
        topPadding = BrowseDefaults.gridTopPadding,
        gap = BrowseDefaults.rowGap,
        labelAllowance = BrowseDefaults.labelAllowance,
        rows = if (landscape) BrowseDefaults.LANDSCAPE_FITTED_ROWS else BrowseDefaults.POSTER_FITTED_ROWS,
        cardHeightPerWidth = browseCardHeightPerWidth(landscape)
    )
    val radius = com.fetch.tv.ui.theme.cardCornerRadius(cornerRadiusDp)
    return remember(width, radius) {
        PosterCardDefaults.Style.copy(
            width = width,
            height = width * 3 / 2,
            cornerRadius = radius,
            showRestingBorder = true
        )
    }
}

/**
 * Whether the header should fold to its tab row: the grid has scrolled at all. A TV grid scrolls
 * only as far as the focused row needs, so the first row's titles can stay in view long after
 * focus has left it; the header opens again once the grid is back at its very top.
 */
@Composable
fun rememberBrowseHeaderCollapsed(gridState: LazyGridState): Boolean {
    val collapsed by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
    }
    return collapsed
}

/**
 * The browse screens' Back ladder (#850): with the grid scrolled, back to its top and first card;
 * from a card at the top, to the tab row (or the filters where there are no tabs); then the screen
 * leaves as usual. [enabled] is false while something on top (a panel, selection mode) owns Back.
 */
@Composable
fun BrowseBackHandler(
    gridState: LazyGridState,
    gridHasFocus: Boolean,
    onFocusFirstCard: suspend () -> Boolean,
    onFocusHeader: suspend () -> Unit,
    enabled: Boolean = true
) {
    val scrolled by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
    }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = enabled && (scrolled || gridHasFocus)) {
        scope.launch {
            if (scrolled) {
                runSuspendCatching { gridState.scrollToItem(0) }
                if (!onFocusFirstCard()) onFocusHeader()
            } else {
                onFocusHeader()
            }
        }
    }
}

/**
 * The browse screens' one focus-restore step (#850): brings the card at [index] into view if it
 * isn't, then gives it focus once it is composed and laid out. Each screen decides which card by
 * its key; this is how it gets there. False when the card couldn't take focus.
 */
suspend fun LazyGridState.restoreBrowseFocus(index: Int, requester: FocusRequester): Boolean {
    if (index < 0) return false
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) runSuspendCatching { scrollToItem(index) }
    return requester.requestFocusAfterFrames(frames = 1)
}

/**
 * Held Up / Down on a browse grid (#850): Home's fast scroll, landing in the column the press
 * started in. [requesterFor] gives a card's focus requester by its grid key, null for cells that
 * are not cards (headers, Load more), which are never landed on.
 */
fun Modifier.browseGridFastScroll(
    gridState: LazyGridState,
    focusedKey: () -> Any?,
    requesterFor: (key: Any) -> FocusRequester?
): Modifier = composed {
    // Taken as the drag starts: by the time it lands, the card it started on has scrolled away.
    val startColumn = remember { intArrayOf(-1) }
    dpadVerticalFastScroll(
        scrollableState = gridState,
        onFastScrollingChanged = { active ->
            // The modifier reports the end before it asks for the landing, so only the start counts.
            if (active) {
                val key = focusedKey()
                startColumn[0] = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.column ?: -1
            }
        },
        resolveVerticalLanding = { sign ->
            val cards = gridState.layoutInfo.visibleItemsInfo.filter { requesterFor(it.key) != null }
            browseFastScrollLanding(cards.map { it.key to it.column }, startColumn[0], sign)
                ?.let(requesterFor)
                ?.let { requester -> runCatching { requester.requestFocus() } }
            null
        }
    )
}

/**
 * Where a fast scroll lands among the visible cards ([cards]: key and column, in grid order): the
 * last (going down) or first (going up) card in [startColumn], else the last or first card.
 */
internal fun browseFastScrollLanding(cards: List<Pair<Any, Int>>, startColumn: Int, sign: Int): Any? {
    if (cards.isEmpty()) return null
    val sameColumn = cards.filter { it.second == startColumn }
    val pool = sameColumn.ifEmpty { cards }
    return (if (sign > 0) pool.last() else pool.first()).first
}
