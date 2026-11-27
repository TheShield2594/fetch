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
import coil3.imageLoader
import coil3.request.allowHardware
import coil3.toBitmap
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
fun BrowseHeroBackdrop(backdrop: String?, modifier: Modifier = Modifier, poster: String? = null) {
    val bgColor = FetchTheme.colors.Background
    // Before any backdrop has loaded (a Library title whose meta isn't cached yet), the band
    // takes its colour from the poster; once art has been shown, the previous art holds instead.
    val posterTint = rememberPosterTint(poster)
    BoxWithConstraints(modifier = modifier) {
        if (posterTint != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val wash = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to bgColor,
                                BROWSE_HERO_TINT_START to posterTint,
                                1.0f to posterTint
                            )
                        )
                        onDrawBehind { drawRect(wash) }
                    }
            )
        }
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
private const val BROWSE_HERO_TINT_START = 0.45f

/** How much of the poster's own colour the wash keeps, over the background, so text stays legible. */
private const val BROWSE_HERO_TINT_STRENGTH = 0.35f

/** Side of the copy of the poster averaged for its colour: tiny, so it is cheap to decode. */
private const val BROWSE_HERO_TINT_SAMPLE_PX = 8

/**
 * The poster's average colour, blended into the background, for the band's wash while no backdrop
 * has loaded (#852). Null until it is known, without a poster, and on low-RAM devices.
 */
@Composable
private fun rememberPosterTint(poster: String?): Color? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val background = FetchTheme.colors.Background
    val enabled = !FetchTheme.lowRamDevice
    var tint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(poster, enabled, background) {
        if (!enabled || poster.isNullOrBlank()) return@LaunchedEffect
        val request = coil3.request.ImageRequest.Builder(context)
            .data(poster)
            .size(BROWSE_HERO_TINT_SAMPLE_PX, BROWSE_HERO_TINT_SAMPLE_PX)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? coil3.request.SuccessResult ?: return@LaunchedEffect
        val average = averageColor(result.image.toBitmap()) ?: return@LaunchedEffect
        tint = androidx.compose.ui.graphics.lerp(background, average, BROWSE_HERO_TINT_STRENGTH)
    }
    return tint
}

/** The mean colour of [bitmap]'s pixels, or null for an empty one. */
private fun averageColor(bitmap: android.graphics.Bitmap): Color? {
    val count = bitmap.width * bitmap.height
    if (count == 0) return null
    val pixels = IntArray(count)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var r = 0L
    var g = 0L
    var b = 0L
    pixels.forEach { p ->
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    return Color(red = (r / count).toInt(), green = (g / count).toInt(), blue = (b / count).toInt())
}
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
