package com.fetch.tv.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.ui.components.SkeletonBar
import com.fetch.tv.ui.components.rememberSkeletonShimmer
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.cardCornerRadius

/**
 * The sizes Home lays out with: card sizes from the poster settings, and the height of the rows
 * viewport at the bottom of the screen that the hero text sits on. Shared by the content and its
 * loading skeleton so the skeleton has the same shape as what replaces it.
 */
@Immutable
internal data class ModernHomeLayout(
    val portraitCatalogCardWidth: Dp,
    val portraitCatalogCardHeight: Dp,
    val landscapeCatalogCardWidth: Dp,
    val landscapeCatalogCardHeight: Dp,
    val continueWatchingCardWidth: Dp,
    val continueWatchingCardHeight: Dp,
    /** Card size of a catalog row with the current poster orientation. */
    val entryRowCardWidth: Dp,
    val entryRowCardHeight: Dp,
    /** Continue Watching leads Home only while it is on and has something in it. */
    val continueWatchingLeads: Boolean,
    val rowTitleHeight: Dp,
    val rowSpacing: Dp,
    val rowsViewportHeight: Dp,
    /** The rows grew into the hero to show two whole rows, so the hero's text is shortened. */
    val compactHeroText: Boolean
)

@Composable
internal fun rememberModernHomeLayout(uiState: HomeUiState): ModernHomeLayout {
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val portraitBaseWidth = uiState.posterCardWidthDp.dp
    val portraitBaseHeight = uiState.posterCardHeightDp.dp
    val continueWatchingStyle = uiState.continueWatchingCardStyle
    val continueWatchingLeads = uiState.continueWatchingEnabled && uiState.continueWatchingItems.isNotEmpty()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current
    val rowTitleLineHeight = MaterialTheme.typography.titleMedium.lineHeight
    val rowSpacing = FetchTheme.spacing.xl
    val fallbackTitleHeight = FetchTheme.spacing.xl
    return remember(
        useLandscapePosters,
        portraitBaseWidth,
        portraitBaseHeight,
        continueWatchingStyle,
        continueWatchingLeads,
        screenHeight,
        density,
        rowTitleLineHeight,
        rowSpacing,
        fallbackTitleHeight
    ) {
        val portraitModernPosterScale = 1.08f
        val landscapeModernPosterScale = 1.34f
        val portraitCatalogCardWidth = portraitBaseWidth * 0.84f * portraitModernPosterScale
        val portraitCatalogCardHeight = portraitBaseHeight * 0.84f * portraitModernPosterScale
        val landscapeCatalogCardWidth = portraitBaseWidth * 1.24f * landscapeModernPosterScale
        val landscapeCatalogCardHeight = landscapeCatalogCardWidth / 1.77f
        // Poster style reuses the portrait catalog dimensions so its artwork matches the catalogs below it.
        val continueWatchingScale = 1.34f
        val continueWatchingCardWidth = when (continueWatchingStyle) {
            ContinueWatchingCardStyle.POSTER -> portraitCatalogCardWidth
            // Wide still scales with the poster width setting so it matches the rest of the row.
            ContinueWatchingCardStyle.WIDE -> portraitBaseWidth * 2.1f
            ContinueWatchingCardStyle.CARD -> portraitBaseWidth * 1.24f * continueWatchingScale
        }
        val continueWatchingCardHeight = when (continueWatchingStyle) {
            ContinueWatchingCardStyle.POSTER -> portraitCatalogCardHeight
            ContinueWatchingCardStyle.WIDE -> continueWatchingCardWidth * WIDE_CARD_HEIGHT_RATIO
            ContinueWatchingCardStyle.CARD -> continueWatchingCardWidth / 1.77f
        }
        val rowTitleHeight = with(density) {
            runCatching { rowTitleLineHeight.toDp() }.getOrDefault(fallbackTitleHeight)
        }
        val rowsViewportHeightFraction = if (useLandscapePosters) 0.49f else 0.52f
        // Two whole rows on entry where the cards allow it (landscape cards at the default
        // size do): the rows grow into the hero up to MODERN_ROWS_VIEWPORT_MAX_FRACTION, and
        // the hero's text block is shortened to match (compactHeroText).
        val entryRowCardWidth = if (useLandscapePosters) landscapeCatalogCardWidth else portraitCatalogCardWidth
        val entryRowCardHeight = if (useLandscapePosters) landscapeCatalogCardHeight else portraitCatalogCardHeight
        val firstRowCardHeight = if (continueWatchingLeads) {
            maxOf(entryRowCardHeight, continueWatchingCardHeight)
        } else {
            entryRowCardHeight
        }
        val twoRowsHeight = rowTitleHeight * 2 + MODERN_ROW_TITLE_BOTTOM * 2 +
            firstRowCardHeight + entryRowCardHeight + rowSpacing + MODERN_ROW_FOCUS_ALLOWANCE
        val rowsViewportHeight = maxOf(screenHeight * rowsViewportHeightFraction, twoRowsHeight)
            .coerceAtMost(screenHeight * MODERN_ROWS_VIEWPORT_MAX_FRACTION)
        ModernHomeLayout(
            portraitCatalogCardWidth = portraitCatalogCardWidth,
            portraitCatalogCardHeight = portraitCatalogCardHeight,
            landscapeCatalogCardWidth = landscapeCatalogCardWidth,
            landscapeCatalogCardHeight = landscapeCatalogCardHeight,
            continueWatchingCardWidth = continueWatchingCardWidth,
            continueWatchingCardHeight = continueWatchingCardHeight,
            entryRowCardWidth = entryRowCardWidth,
            entryRowCardHeight = entryRowCardHeight,
            continueWatchingLeads = continueWatchingLeads,
            rowTitleHeight = rowTitleHeight,
            rowSpacing = rowSpacing,
            rowsViewportHeight = rowsViewportHeight,
            compactHeroText = rowsViewportHeight > screenHeight * rowsViewportHeightFraction
        )
    }
}

/**
 * Home's loading placeholder, laid out from the same [ModernHomeLayout] as the content: the hero
 * text above a bottom-anchored rows viewport, the user's card size, orientation and corner
 * radius, and a wider Continue Watching row first when it leads. The hero has no button, so
 * neither does this.
 */
@Composable
internal fun ModernHomeSkeleton(
    uiState: HomeUiState,
    modifier: Modifier = Modifier
) {
    val layout = rememberModernHomeLayout(uiState)
    val shimmer = rememberSkeletonShimmer()
    val cornerRadius = cardCornerRadius(uiState.posterCardCornerRadiusDp)
    val horizontalPadding = FetchTheme.spacing.screen.horizontal
    Box(modifier = modifier.fillMaxSize()) {
        // Hero text: the same anchor as HeroTitleBlock, just above the rows viewport.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = horizontalPadding,
                    end = FetchTheme.spacing.xxxl,
                    bottom = layout.rowsViewportHeight + FetchTheme.spacing.lg
                )
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            SkeletonBar(width = 360.dp, height = FetchTheme.spacing.xxxl, shimmer = shimmer)
            SkeletonBar(width = 280.dp, height = FetchTheme.spacing.lg, shimmer = shimmer)
            SkeletonBar(width = 420.dp, height = FetchTheme.spacing.md, shimmer = shimmer)
            if (!layout.compactHeroText) {
                SkeletonBar(width = 340.dp, height = FetchTheme.spacing.md, shimmer = shimmer)
            }
        }
        // Rows: a viewport of the same height at the bottom, filled from its top like the list.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(layout.rowsViewportHeight)
                .clipToBounds(),
            verticalArrangement = Arrangement.spacedBy(layout.rowSpacing)
        ) {
            repeat(MODERN_HOME_SKELETON_ROWS) { rowIndex ->
                val isContinueWatching = rowIndex == 0 && layout.continueWatchingLeads
                val cardWidth = if (isContinueWatching) layout.continueWatchingCardWidth else layout.entryRowCardWidth
                val cardHeight = if (isContinueWatching) layout.continueWatchingCardHeight else layout.entryRowCardHeight
                Column {
                    Box(modifier = Modifier.padding(start = horizontalPadding, bottom = MODERN_ROW_TITLE_BOTTOM)) {
                        SkeletonBar(width = 180.dp, height = layout.rowTitleHeight, shimmer = shimmer)
                    }
                    Row(
                        modifier = Modifier
                            .padding(start = horizontalPadding)
                            .wrapContentWidth(Alignment.Start, unbounded = true),
                        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
                    ) {
                        repeat(MODERN_HOME_SKELETON_ROW_CARDS) {
                            SkeletonBar(
                                width = cardWidth,
                                height = cardHeight,
                                shimmer = shimmer,
                                cornerRadius = cornerRadius
                            )
                        }
                    }
                }
            }
        }
    }
}

internal const val WIDE_CARD_HEIGHT_RATIO = 0.4f

/** Enough rows to fill the tallest viewport; the column clips what does not fit. */
private const val MODERN_HOME_SKELETON_ROWS = 3

/** Enough cards to run past the right edge of a 1080p screen at the smallest card size. */
private const val MODERN_HOME_SKELETON_ROW_CARDS = 12
