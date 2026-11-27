package com.fetch.tv.ui.components.hero

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.StableList
import com.fetch.tv.ui.util.formatHeroRuntime
import kotlinx.coroutines.delay
import java.util.Locale

/** How long focus rests on a card before the band follows it, as on Home. */
internal const val BROWSE_HERO_FOCUS_DEBOUNCE_MS = 200L

/**
 * The band's preview for a browse card (#852): the card's own [item], filled in from the full
 * [cached] meta once the focus prefetch has it (backdrop, logo, runtime, synopsis). [typeLabel]
 * is the localized "Movie" / "Series".
 */
fun browseHeroPreview(item: MetaPreview, cached: Meta?, typeLabel: String?): HeroPreview {
    val backdrop = cached?.background?.takeIf { it.isNotBlank() } ?: item.background?.takeIf { it.isNotBlank() }
    val rating = cached?.imdbRating?.takeIf { it > 0f } ?: item.imdbRating
    return HeroPreview(
        title = item.name.ifBlank { cached?.name.orEmpty() },
        logo = cached?.logo?.takeIf { it.isNotBlank() } ?: item.logo?.takeIf { it.isNotBlank() },
        description = item.description?.takeIf { it.isNotBlank() } ?: cached?.description?.takeIf { it.isNotBlank() },
        contentTypeText = typeLabel,
        isSeries = item.type == ContentType.SERIES || item.apiType.equals("series", ignoreCase = true),
        yearText = browseHeroYear(cached?.releaseInfo) ?: browseHeroYear(item.releaseInfo),
        runtimeText = formatHeroRuntime(cached?.runtime?.takeIf { it.isNotBlank() } ?: item.runtime),
        imdbText = rating?.takeIf { it > 0f }?.let { String.format(Locale.getDefault(), "%.1f", it) },
        ageRatingText = cached?.ageRating?.takeIf { it.isNotBlank() } ?: item.ageRating?.takeIf { it.isNotBlank() },
        genres = StableList((item.genres.ifEmpty { cached?.genres.orEmpty() }).take(BROWSE_HERO_MAX_GENRES)),
        poster = item.poster,
        backdrop = backdrop,
        imageUrl = backdrop ?: item.poster
    )
}

private const val BROWSE_HERO_MAX_GENRES = 2
private const val BROWSE_HERO_SYNOPSIS_LINES = 2
private val BROWSE_HERO_YEAR = Regex("""\b(19|20)\d{2}\b""")

/** The year in a release line ("2019", "2019–2023"), or null when there is none. */
internal fun browseHeroYear(releaseInfo: String?): String? =
    releaseInfo?.let { BROWSE_HERO_YEAR.find(it)?.value }

/**
 * The preview the band shows: [candidate] once focus has rested on it for
 * [BROWSE_HERO_FOCUS_DEBOUNCE_MS]. While the D-pad is held, focus keeps moving and the band holds
 * the last title; a grid fast scroll freezes focus, so it holds then too. The first title shows
 * at once.
 */
@Composable
fun rememberBrowseHeroTarget(candidate: HeroPreview?): HeroPreview? {
    var target by remember { mutableStateOf(candidate) }
    LaunchedEffect(candidate) {
        if (candidate == null) return@LaunchedEffect
        if (target != null && target?.title != candidate.title) delay(BROWSE_HERO_FOCUS_DEBOUNCE_MS)
        target = candidate
    }
    return target
}

/**
 * The band's art behind the browse header (#852): the focused title's backdrop, full bleed, moving
 * on with Home's preloaded crossfade (a snap under reduced motion), under Home's scrims plus one
 * along the top for the tabs and the clock. With no backdrop yet, the previous one holds.
 */
@Composable
fun BrowseHeroBackdrop(backdrop: String?, modifier: Modifier = Modifier) {
    val bgColor = FetchTheme.colors.Background
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val fill = Modifier.fillMaxSize()
        ModernHeroMediaLayer(
            heroBackdrop = { backdrop },
            shouldPlayHeroTrailer = { false },
            heroTrailerFirstFrameRendered = { false },
            heroTrailerUrl = { null },
            heroTrailerAudioUrl = { null },
            heroTrailerPlaybackKey = { null },
            muted = { true },
            onTrailerEnded = {},
            onFirstFrameRendered = {},
            modifier = fill,
            requestWidthPx = widthPx,
            requestHeightPx = heightPx,
            snapBackdrops = !FetchTheme.fullMotion || FetchTheme.lowRamDevice
        )
        ModernHeroGradientLayer(bgColor = bgColor, isFullScreen = { true }, modifier = fill)
        Box(
            modifier = fill.drawWithCache {
                val top = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to bgColor.copy(alpha = BROWSE_HERO_TOP_SCRIM_ALPHA),
                        BROWSE_HERO_TOP_SCRIM_END to Color.Transparent
                    )
                )
                onDrawBehind { drawRect(top) }
            }
        )
    }
}

private const val BROWSE_HERO_TOP_SCRIM_ALPHA = 0.7f
private const val BROWSE_HERO_TOP_SCRIM_END = 0.3f

/** The band's text: the logo (or title), the meta line and a short synopsis, fading with the art. */
@Composable
fun BrowseHeroTitle(preview: HeroPreview?, modifier: Modifier = Modifier) {
    HeroTitleBlock(
        previewProvider = { preview },
        modifier = modifier,
        portraitMode = false,
        showImdbRatings = true,
        compact = true,
        descriptionMaxLines = BROWSE_HERO_SYNOPSIS_LINES
    )
}
