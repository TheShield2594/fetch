package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.TrackingService
import com.fetch.desktop.tracking.MoreLikeThisSourceOption
import com.fetch.desktop.tracking.SimklRelated
import com.fetch.desktop.tracking.TraktComment
import com.fetch.desktop.tracking.TraktPublicItem
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException

/** What a title page shows below the cast from Trakt or Simkl: More like this. */
class TitleExtras(val moreLikeThis: List<MetaPreview>?, val moreLikeThisSource: String?)

fun TraktPublicItem.toPreview(): MetaPreview = MetaPreview(
    id = id,
    type = if (isShow) ContentType.SERIES else ContentType.MOVIE,
    rawType = if (isShow) "series" else "movie",
    name = title,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = fanart,
    logo = logo,
    description = overview,
    releaseInfo = year?.toString(),
    imdbRating = rating?.toFloat(),
    genres = genres,
    runtime = runtime?.let { "$it min" },
    imdbId = id.takeIf { it.startsWith("tt") }
)

fun SimklRelated.toPreview(): MetaPreview = MetaPreview(
    id = id,
    type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
    rawType = if (isMovie) "movie" else "series",
    name = title,
    poster = poster ?: fanart,
    posterShape = PosterShape.POSTER,
    background = fanart ?: poster,
    logo = null,
    description = null,
    releaseInfo = year?.toString(),
    imdbRating = null,
    genres = emptyList(),
    imdbId = imdbId
)

/**
 * Loads [TitleExtras] for the title page, by Settings › Tracking: More like this from Trakt or
 * Simkl when that's the source (TMDB's own row is used otherwise, and when they have nothing),
 * and Trakt comments while "Show comments" is on. Null while it loads or for titles without an
 * IMDb id.
 */
@Composable
fun rememberTitleExtras(state: AppState, meta: Meta?, preview: MetaPreview): TitleExtras? {
    val imdb = (meta?.imdbId ?: preview.imdbId ?: preview.id).substringBefore(':').takeIf { it.startsWith("tt") }
    val isShow = preview.type == ContentType.SERIES || preview.apiType == "series"
    val options = state.settings.tracking
    var extras by remember(imdb) { mutableStateOf<TitleExtras?>(null) }
    LaunchedEffect(imdb, options.moreLikeThisSource) {
        if (imdb == null) return@LaunchedEffect
        suspend fun <T> safely(block: suspend () -> T?): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val tracking = state.tracking
        val (more, source) = when (options.moreLikeThis) {
            MoreLikeThisSourceOption.TRAKT ->
                safely { tracking.withTraktPublic { related(it, imdb, isShow) } }?.map { it.toPreview() } to "Trakt"
            MoreLikeThisSourceOption.SIMKL ->
                (if (tracking.credentials.simklAvailable) safely { tracking.simklRelated(imdb) }?.map { it.toPreview() } else null) to "Simkl"
            MoreLikeThisSourceOption.TMDB -> null to null
        }
        // Comments come from DetailExtras' section (title or episode), shown while "Show comments" is on.
        extras = TitleExtras(more?.takeIf { it.isNotEmpty() }, source)
    }
    return extras
}

/** More like this from [extras] when it has a row, else TMDB's [tmdbRow]. */
fun LazyListScope.titleExtras(state: AppState, extras: TitleExtras?, tmdbRow: List<MetaPreview>?) {
    val row = (extras?.moreLikeThis ?: tmdbRow)?.filter(state::allowed)?.takeIf { it.isNotEmpty() }
    if (row != null) {
        item(key = "more-like-this") {
            MediaRowView(
                title = "More like this",
                subtitle = extras?.moreLikeThis?.let { extras.moreLikeThisSource },
                items = row,
                loading = false,
                error = null,
                onOpen = { state.navigate(Screen.Detail(it)) },
                showLabels = state.settings.showPosterLabels
            )
        }
    }
}

