package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fetch.desktop.AppState
import com.fetch.desktop.CatalogRow
import com.fetch.desktop.Screen
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.toPreview
import com.fetch.desktop.home.ReadyTonight
import com.fetch.desktop.home.ReadyTonight.bestMatchKey
import com.fetch.desktop.poster.CustomPosterScreen
import com.fetch.desktop.poster.posterPatternFor
import com.fetch.desktop.poster.withCustomPosterUrls
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.ShuffleSurface
import com.fetch.tv.domain.model.catalogRowLegacyKey
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Layout › Focused Poster › Autoplay Trailer with the Hero location: the hero's title's trailer
 * plays over its backdrop after the trailer delay, muted unless "Play Trailer Muted" is off.
 * Never under the player. With the Expanded card location the card plays it instead ([CardStyle.trailer]).
 */
@Composable
fun HeroTrailer(state: AppState, preview: MetaPreview?, height: androidx.compose.ui.unit.Dp) {
    val layout = state.settings.layout
    if (preview == null || !layout.focusedPosterTrailer || !layout.trailersEnabled || state.playerSession != null) return
    if (layout.focusedPosterTrailerTarget != com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA) return
    val yt = preview.trailerYtIds.firstOrNull() ?: return
    androidx.compose.runtime.key(preview.id) {
        BackgroundVideo(
            url = youTubeWatchUrl(yt),
            muted = layout.focusedPosterTrailerMuted,
            crop = layout.trailerCropToFill,
            modifier = Modifier.fillMaxWidth().height(height),
            startDelayMs = layout.trailerDelaySeconds.coerceIn(0, 60) * 1000L,
            gpu = state.settings.player.builtInGpu,
            overlay = { BackdropScrim() }
        )
    }
}

/**
 * A collection folder's hero video (set in the folder editor), looping behind its page, or
 * behind Home's hero while the folder's tile is pointed at, as on TV. Muted with "Play Trailer Muted".
 */
@Composable
fun FolderHeroVideo(state: AppState, folder: com.fetch.tv.domain.model.CollectionFolder?, height: androidx.compose.ui.unit.Dp, startDelayMs: Long = 600) {
    val url = folder?.heroVideoUrl?.trim()?.takeIf { it.startsWith("http") } ?: return
    if (state.playerSession != null) return
    val layout = state.settings.layout
    androidx.compose.runtime.key(folder.id, url) {
        BackgroundVideo(
            url = url,
            muted = layout.focusedPosterTrailerMuted,
            crop = layout.trailerCropToFill,
            modifier = Modifier.fillMaxWidth().height(height),
            startDelayMs = startDelayMs,
            gpu = state.settings.player.builtInGpu,
            overlay = { BackdropScrim() }
        )
    }
}

/** Backdrop's fades, for a video drawn over it. */
@Composable
fun BackdropScrim() {
    val background = MaterialTheme.colorScheme.background
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to background, 0.30f to background.copy(alpha = 0.85f), 0.65f to background.copy(alpha = 0.15f), 1f to Color.Transparent)
        )
    )
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.55f to Color.Transparent, 1f to background))
    )
}

/** A kids profile's Home (the TV's `HomeUiState.kidsHome`). */
val AppState.kidsHome: Boolean get() = limits.kidsProfile

/** The TV's legacy row key for this catalog, which Layout's hero catalogs and custom titles use. */
fun CatalogRow.tvKey(): String = catalogRowLegacyKey(addon.id, catalog.apiType, catalog.id)

/** The row's heading: your own title for it (Home catalogs › Rename), else the catalog's. */
fun AppState.homeRowTitle(row: CatalogRow): String =
    settings.layout.customCatalogTitles[row.tvKey()]?.takeIf { it.isNotBlank() } ?: row.title(settings.showCatalogType && !kidsHome)

/** [row] with Layout's custom poster source applied to its items on Home. */
fun AppState.homeRowItems(row: CatalogRow): CatalogRow {
    val pattern = settings.layout.posterPatternFor(CustomPosterScreen.HOME)
    return if (pattern.isEmpty()) row else row.copy(items = row.items.withCustomPosterUrls(pattern))
}

/**
 * The titles Home's hero cycles through, from Layout's hero catalogs in their order: those with a
 * backdrop, up to 10 a row and 20 in all. Empty when no hero catalog is picked.
 */
fun AppState.heroCarousel(): List<MetaPreview> {
    val keys = settings.layout.heroCatalogKeys
    if (keys.isEmpty()) return emptyList()
    val byKey = homeRows.associateBy { it.tvKey() }
    return keys.mapNotNull { byKey[it] }
        .flatMap { row -> row.items.filter { it.background != null }.take(10) }
        .distinctBy { it.id }
        .take(20)
}

/** "Mar 14, 2024" for a movie with a full date and "Show full release date" on, else the addon's release info. */
fun releaseLabel(preview: MetaPreview, full: Boolean): String? {
    if (full && preview.apiType == "movie") {
        val date = preview.released?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (date != null) return date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH))
    }
    return preview.releaseInfo
}

/** Movies from the watchlist Ready tonight considers: Trakt's when connected, else the local library. */
fun AppState.readyWatchlist(): List<LibraryEntry> = ReadyTonight.watchlistMovies(lists.traktWatchlist ?: library.toList())

/** The Ready tonight row's contents: Continue Watching's ready titles, then ready watchlist movies. */
fun AppState.readyTonight(): Pair<List<ProgressEntry>, List<LibraryEntry>> {
    if (kidsHome || !settings.showContinueWatching) return emptyList<ProgressEntry>() to emptyList()
    val keys = homeExtras.readyKeys()
    if (keys.isEmpty()) return emptyList<ProgressEntry>() to emptyList()
    val entries = ReadyTonight.readyEntries(continueWatching, keys)
    val except = continueWatching.mapNotNull { it.bestMatchKey() }.toSet()
    val movies = ReadyTonight.readyMovies(readyWatchlist(), keys, except)
    return if (entries.size + movies.size >= ReadyTonight.MIN_ITEMS) entries to movies else emptyList<ProgressEntry>() to emptyList()
}

/**
 * "Ready tonight": what will start at once, Continue Watching's first, then watchlist movies,
 * as on TV (only with two or more).
 */
@Composable
fun ReadyTonightRow(
    entries: List<ProgressEntry>,
    movies: List<LibraryEntry>,
    actions: ContinueWatchingActions,
    onHoverEntry: (ProgressEntry) -> Unit,
    onHoverTitle: (MetaPreview) -> Unit,
    onOpenMovie: (MetaPreview) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(tr("Ready tonight"), style = MaterialTheme.typography.titleLarge, color = FetchPalette.TextPrimary, modifier = Modifier.padding(horizontal = PageGutter))
        LazyRow(
            contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(entries, key = { "cw|${it.contentId}" }) { entry -> ContinueCard(entry, actions, onHoverEntry) }
            items(movies, key = { "movie|${it.id}" }) { movie ->
                val preview = movie.toPreview()
                PosterCard(preview, onClick = { onOpenMovie(preview) }, onHover = onHoverTitle, shape = PosterShape.LANDSCAPE)
            }
        }
    }
}

/**
 * Plays a random episode of [entry]'s show from Home, as the TV's Home shuffle: the pick stays the
 * same until it's played, and turning shuffle on for the show keeps it on there.
 */
fun AppState.shuffleFromHome(entry: ProgressEntry, launch: (suspend () -> Unit) -> Unit) {
    launch {
        val videos = homeExtras.videos(entry.contentType, entry.contentId)
        val settings = homeExtras.shuffleFor(entry.contentId).let { if (it.enabled) it else it.copy(enabled = true) }
        val watched = videos.filter { isWatched(it.id) }.mapNotNull { v -> v.season?.let { s -> v.episode?.let { s to it } } }.toSet()
        val pick = homeExtras.shuffle.select(
            profile.id, entry.contentId, videos, settings.includeWatched, watched, surface = ShuffleSurface.HOME, visit = System.nanoTime()
        )
        if (pick == null) {
            message = "No episodes to shuffle. Choose All episodes on the title page to watch again."
            return@launch
        }
        homeExtras.saveShuffle(entry.contentId, settings)
        homeExtras.shuffle.clearSelection(profile.id, entry.contentId, ShuffleSurface.HOME)
        navigate(Screen.Detail(entry.toPreviewForShuffle(), autoPlay = true, videoId = pick.id))
    }
}

private fun ProgressEntry.toPreviewForShuffle(): MetaPreview = MetaPreview(
    id = contentId,
    type = com.fetch.tv.domain.model.ContentType.fromString(contentType),
    rawType = contentType,
    name = name,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = backdrop,
    logo = logo,
    description = null,
    releaseInfo = null,
    imdbRating = null,
    genres = emptyList()
)

/** Continue Watching actions with Shuffle when Layout's Shuffle is on. */
@Composable
fun AppState.withHomeShuffle(actions: ContinueWatchingActions): ContinueWatchingActions {
    if (!settings.layout.randomEpisodeEnabled) return actions
    val scope = rememberCoroutineScope()
    return ContinueWatchingActions(
        onPlay = { entry ->
            // A show with shuffle on plays its pick instead of the next episode, as on TV.
            if (entry.upNext && homeExtras.shuffleFor(entry.contentId).enabled) shuffleFromHome(entry) { scope.launch { it() } }
            else actions.onPlay(entry)
        },
        onDetails = actions.onDetails,
        onStartOver = actions.onStartOver,
        onRemove = actions.onRemove,
        onShuffle = { entry -> shuffleFromHome(entry) { scope.launch { it() } } }
    )
}
