package com.fetch.tv.ui.components

import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

@Composable
fun StreamsSkeletonList(
    modifier: Modifier = Modifier,
    showAddonLogo: Boolean = true,
    itemCount: Int = 6
) {
    val shimmer = rememberSkeletonShimmer()
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(FetchTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        contentPadding = PaddingValues(vertical = FetchTheme.spacing.sm)
    ) {
        items(itemCount) {
            StreamCardSkeleton(showAddonLogo = showAddonLogo, shimmer = shimmer)
        }
    }
}

@Composable
fun StreamCardSkeleton(
    modifier: Modifier = Modifier,
    showAddonLogo: Boolean = true,
    shimmer: SkeletonShimmer = rememberSkeletonShimmer()
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FetchTheme.radii.xl))
            .background(FetchTheme.colors.BackgroundElevated)
            .padding(FetchTheme.spacing.lg)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
            ) {
                SkeletonBar(width = 180.dp, height = FetchTheme.spacing.lg, shimmer = shimmer, cornerRadius = 10.dp)
                SkeletonBar(width = 140.dp, height = FetchTheme.spacing.md, shimmer = shimmer, cornerRadius = 10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                    SkeletonBar(width = 64.dp, height = FetchTheme.spacing.lg, shimmer = shimmer, cornerRadius = 10.dp)
                    SkeletonBar(width = 52.dp, height = FetchTheme.spacing.lg, shimmer = shimmer, cornerRadius = 10.dp)
                }
            }

            if (showAddonLogo) {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Box(
                        modifier = Modifier
                            .size(FetchTheme.spacing.xxl)
                            .clip(RoundedCornerShape(FetchTheme.radii.sm))
                            .skeletonShimmer(shimmer)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBar(width = 64.dp, height = 10.dp, shimmer = shimmer, cornerRadius = FetchTheme.spacing.sm)
                }
            }
        }
    }
}

@Composable
fun MetaDetailsSkeleton(backdropAware: Boolean = false) {
    val shimmer = rememberSkeletonShimmer(backdropAware = backdropAware)
    val episodeCardColor = if (backdropAware) {
        FetchTheme.colors.BackgroundCard.copy(alpha = 0.40f)
    } else {
        FetchTheme.colors.BackgroundCard
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (backdropAware) Color.Transparent else FetchTheme.colors.Background)
    ) {
        if (backdropAware) {
            BackdropLoadingScrim()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                MetaHeroSkeleton(shimmer = shimmer)
            }

            item {
                SeasonTabsSkeleton(shimmer = shimmer)
            }

            item {
                EpisodesRowSkeleton(
                    shimmer = shimmer,
                    cardColor = episodeCardColor
                )
            }

            item {
                CastSectionSkeleton(shimmer = shimmer)
            }

            item {
                CompanyLogosSkeleton(titleWidth = 140.dp, shimmer = shimmer)
            }

            item {
                CompanyLogosSkeleton(titleWidth = 110.dp, shimmer = shimmer)
            }
        }
    }
}

@Composable
private fun BackdropLoadingScrim() {
    val backgroundColor = FetchTheme.colors.Background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor.copy(alpha = 0.20f))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = 0.96f),
                        backgroundColor.copy(alpha = 0.84f),
                        backgroundColor.copy(alpha = 0.56f),
                        Color.Transparent
                    )
                )
            )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = 0.08f),
                        Color.Transparent,
                        backgroundColor.copy(alpha = 0.90f)
                    )
                )
            )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(540.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = 0.12f),
                        Color.Transparent,
                        backgroundColor.copy(alpha = 0.42f)
                    )
                )
            )
    )
}

@Composable
private fun MetaHeroSkeleton(shimmer: SkeletonShimmer) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(540.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = FetchTheme.spacing.xxxl, end = FetchTheme.spacing.xxxl, bottom = FetchTheme.spacing.lg),
            verticalArrangement = Arrangement.Bottom
        ) {
            SkeletonBar(
                width = 0.4f,
                height = 100.dp,
                shimmer = shimmer,
                cornerRadius = FetchTheme.spacing.md
            )

            Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))

            Row(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonPill(width = 100.dp, height = FetchTheme.spacing.xxxl, shimmer = shimmer)
                Box(
                    modifier = Modifier
                        .size(FetchTheme.spacing.xxxl)
                        .clip(CircleShape)
                        .skeletonShimmer(shimmer)
                )
            }

            Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))

            SkeletonBar(width = 0.5f, height = 14.dp, shimmer = shimmer, cornerRadius = 10.dp)
            Spacer(modifier = Modifier.height(10.dp))
            SkeletonBar(width = 0.6f, height = FetchTheme.spacing.lg, shimmer = shimmer, cornerRadius = 10.dp)
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonBar(width = 0.55f, height = FetchTheme.spacing.lg, shimmer = shimmer, cornerRadius = 10.dp)

            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
                SkeletonBar(width = 96.dp, height = 14.dp, shimmer = shimmer, cornerRadius = 10.dp)
                SkeletonBar(width = 72.dp, height = 14.dp, shimmer = shimmer, cornerRadius = 10.dp)
                SkeletonBar(width = 84.dp, height = 14.dp, shimmer = shimmer, cornerRadius = 10.dp)
                SkeletonBar(width = 64.dp, height = 14.dp, shimmer = shimmer, cornerRadius = 10.dp)
            }

            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))

            Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
                SkeletonBar(width = 72.dp, height = FetchTheme.spacing.md, shimmer = shimmer, cornerRadius = 10.dp)
                SkeletonBar(width = FetchTheme.spacing.huge, height = FetchTheme.spacing.md, shimmer = shimmer, cornerRadius = 10.dp)
            }
        }
    }
}

@Composable
private fun SeasonTabsSkeleton(shimmer: SkeletonShimmer) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FetchTheme.spacing.xxxl, vertical = FetchTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        items(5) {
            SkeletonPill(width = 96.dp, height = 36.dp, shimmer = shimmer)
        }
    }
}

@Composable
private fun EpisodesRowSkeleton(
    shimmer: SkeletonShimmer,
    cardColor: Color
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FetchTheme.spacing.xxxl, vertical = FetchTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
    ) {
        items(5) {
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .clip(RoundedCornerShape(FetchTheme.radii.md))
                    .background(cardColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(158.dp)
                        .clip(RoundedCornerShape(topStart = FetchTheme.spacing.md, topEnd = FetchTheme.spacing.md))
                        .skeletonShimmer(shimmer)
                )
                Column(modifier = Modifier.padding(FetchTheme.spacing.md)) {
                    SkeletonBar(width = 160.dp, height = 14.dp, shimmer = shimmer, cornerRadius = 10.dp)
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                    SkeletonBar(width = 120.dp, height = FetchTheme.spacing.md, shimmer = shimmer, cornerRadius = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun CastSectionSkeleton(shimmer: SkeletonShimmer) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = FetchTheme.spacing.sm)
    ) {
        Box(modifier = Modifier.padding(horizontal = FetchTheme.spacing.xxxl)) {
            SkeletonBar(width = 120.dp, height = 20.dp, shimmer = shimmer, cornerRadius = 10.dp)
        }

        Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = FetchTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
        ) {
            items(7) {
                Column(
                    modifier = Modifier.width(120.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .skeletonShimmer(shimmer)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SkeletonBar(width = 90.dp, height = FetchTheme.spacing.md, shimmer = shimmer, cornerRadius = 10.dp)
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBar(width = 70.dp, height = 10.dp, shimmer = shimmer, cornerRadius = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun CompanyLogosSkeleton(
    titleWidth: Dp,
    shimmer: SkeletonShimmer
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = FetchTheme.spacing.sm)
    ) {
        Box(modifier = Modifier.padding(horizontal = FetchTheme.spacing.xxxl)) {
            SkeletonBar(width = titleWidth, height = 20.dp, shimmer = shimmer, cornerRadius = 10.dp)
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = FetchTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            items(6) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(FetchTheme.spacing.huge)
                        .clip(RoundedCornerShape(FetchTheme.radii.md))
                        .skeletonShimmer(shimmer)
                )
            }
        }
    }
}

@Composable
fun SkeletonBar(
    width: Dp,
    height: Dp,
    shimmer: SkeletonShimmer,
    cornerRadius: Dp = FetchTheme.spacing.sm
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .skeletonShimmer(shimmer)
    )
}

@Composable
fun SkeletonBar(
    width: Float,
    height: Dp,
    shimmer: SkeletonShimmer,
    cornerRadius: Dp = FetchTheme.spacing.sm
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(width)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .skeletonShimmer(shimmer)
    )
}

@Composable
fun SkeletonPill(
    width: Dp,
    height: Dp,
    shimmer: SkeletonShimmer
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(FetchTheme.spacing.xxl))
            .skeletonShimmer(shimmer)
    )
}

/** Sweep distance of the skeleton gradient, in px of each placeholder's own coordinates. */
private const val SKELETON_SHIMMER_DISTANCE_PX = 1000f

/**
 * The shared shimmer for one skeleton tree. Create it once with [rememberSkeletonShimmer] and pass
 * the same instance to every bar; [skeletonShimmer] reads its clock only while drawing, so the
 * sweep redraws the bars without recomposing them.
 */
@Stable
class SkeletonShimmer internal constructor(
    internal val colors: List<Color>,
    /** Null under reduced motion: the bars hold their resting colour. */
    internal val offset: State<Float>?
)

@Composable
fun rememberSkeletonShimmer(backdropAware: Boolean = false): SkeletonShimmer {
    val colors = if (backdropAware) {
        listOf(
            FetchTheme.colors.TextPrimary.copy(alpha = 0.08f),
            FetchTheme.colors.TextPrimary.copy(alpha = 0.20f),
            FetchTheme.colors.TextPrimary.copy(alpha = 0.08f)
        )
    } else {
        listOf(
            FetchTheme.colors.SurfaceVariant.copy(alpha = 0.30f),
            FetchTheme.colors.SurfaceVariant.copy(alpha = 0.60f),
            FetchTheme.colors.SurfaceVariant.copy(alpha = 0.30f)
        )
    }
    // Reduced motion keeps the placeholder's resting colour without the sweep.
    if (!FetchTheme.fullMotion) return remember(colors) { SkeletonShimmer(colors, offset = null) }
    val offset = rememberInfiniteTransition(label = "skeletonShimmer").animateFloat(
        initialValue = 0f,
        targetValue = SKELETON_SHIMMER_DISTANCE_PX,
        animationSpec = infiniteRepeatable(
            animation = tween(FetchMotion.tokens.durations.shimmer, easing = LinearEasing)
        ),
        label = "skeletonShimmerOffset"
    )
    return remember(colors, offset) { SkeletonShimmer(colors, offset) }
}

/** Fills the element with [shimmer]; put it after `clip(...)` exactly where `background(brush)` was. */
fun Modifier.skeletonShimmer(shimmer: SkeletonShimmer): Modifier = drawBehind {
    val offset = shimmer.offset
    if (offset == null) {
        drawRect(color = shimmer.colors.first())
    } else {
        val t = offset.value
        drawRect(
            brush = Brush.linearGradient(
                colors = shimmer.colors,
                start = Offset(t - SKELETON_SHIMMER_DISTANCE_PX, 0f),
                end = Offset(t, 0f)
            )
        )
    }
}

/**
 * Placeholder rows of cards for screens built from catalog rows, such as Home, while their first
 * catalogs load. [showHero] adds the title, metadata line and button that sit above Home's rows.
 */
@Composable
fun CatalogRowsSkeleton(
    modifier: Modifier = Modifier,
    rowCount: Int = 3,
    showHero: Boolean = false,
    startPadding: Dp = FetchTheme.spacing.screen.horizontal,
    cardWidth: Dp = FetchTheme.sizes.cards.posterWidth,
    cardHeight: Dp = FetchTheme.sizes.cards.posterHeight
) {
    val shimmer = rememberSkeletonShimmer()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .padding(start = startPadding, top = FetchTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)
    ) {
        if (showHero) {
            Column(
                modifier = Modifier.padding(top = FetchTheme.spacing.huge, bottom = FetchTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
            ) {
                SkeletonBar(width = 360.dp, height = FetchTheme.spacing.xxxl, shimmer = shimmer)
                SkeletonBar(width = 280.dp, height = FetchTheme.spacing.lg, shimmer = shimmer)
                Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))
                SkeletonPill(width = 160.dp, height = FetchTheme.spacing.xxxl, shimmer = shimmer)
            }
        }
        repeat(rowCount) {
            Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
                SkeletonBar(width = 180.dp, height = FetchTheme.spacing.lg, shimmer = shimmer)
                Row(
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
                ) {
                    repeat(SKELETON_ROW_CARDS) {
                        SkeletonBar(
                            width = cardWidth,
                            height = cardHeight,
                            shimmer = shimmer,
                            cornerRadius = FetchTheme.radii.sm
                        )
                    }
                }
            }
        }
    }
}

/** Placeholder posters for grid screens, such as the library and "See all", while they load. */
@Composable
fun PosterGridSkeleton(
    modifier: Modifier = Modifier,
    rowCount: Int = 3,
    cardWidth: Dp = FetchTheme.sizes.cards.posterWidth,
    cardHeight: Dp = FetchTheme.sizes.cards.posterHeight
) {
    val shimmer = rememberSkeletonShimmer()
    Column(
        modifier = modifier.fillMaxWidth().clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
    ) {
        repeat(rowCount) {
            Row(
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
            ) {
                repeat(SKELETON_ROW_CARDS) {
                    SkeletonBar(
                        width = cardWidth,
                        height = cardHeight,
                        shimmer = shimmer,
                        cornerRadius = FetchTheme.radii.sm
                    )
                }
            }
        }
    }
}

/** Enough cards to run past the right edge of a 1080p screen at the smallest card size. */
private const val SKELETON_ROW_CARDS = 12
