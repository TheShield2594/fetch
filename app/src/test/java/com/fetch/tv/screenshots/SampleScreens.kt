package com.fetch.tv.screenshots

import com.fetch.tv.ui.screens.home.bestMatchKey
import com.fetch.tv.ui.screens.home.LocalReadyBestMatchKeys
import androidx.compose.runtime.CompositionLocalProvider
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.requestFocus
import com.fetch.tv.core.streams.BestMatchPreview
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.tv.material3.Icon
import com.fetch.tv.R
import com.fetch.tv.ui.components.FloatingSidebarButton
import com.fetch.tv.ui.components.FetchWordmark
import com.fetch.tv.ui.components.sidebarScrimBrush
import com.fetch.tv.ui.components.ProfileAvatarCircle
import com.fetch.tv.ui.theme.FetchComponents
import com.fetch.tv.ui.util.rememberRawSvgPainter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.core.qr.QrCodeGenerator
import com.fetch.tv.core.server.PhoneHubState
import com.fetch.tv.core.streams.StreamBadgePlacement
import com.fetch.tv.ui.screens.setup.FirstRunStatus
import com.fetch.tv.ui.screens.setup.FirstRunStep
import com.fetch.tv.ui.screens.setup.FirstRunWizardContent
import com.fetch.tv.ui.screens.ambient.AmbientClock
import com.fetch.tv.ui.screens.player.LoadingOverlay
import com.fetch.tv.ui.screens.stream.StreamCard
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.DEFAULT_APP_THEME
import com.fetch.tv.domain.model.NextToWatch
import com.fetch.tv.ui.screens.detail.HeroContentSection
import com.fetch.tv.ui.screens.home.HomeRow
import com.fetch.tv.ui.screens.home.HomeScreenFocusState
import com.fetch.tv.ui.screens.home.HomeUiState
import com.fetch.tv.ui.screens.home.ModernCarouselRowBuildCache
import com.fetch.tv.ui.screens.home.ModernHomeContent
import com.fetch.tv.ui.screens.home.ModernHomePresentationInput
import com.fetch.tv.ui.screens.home.buildModernHomePresentation
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus
import com.fetch.tv.ui.screens.stream.FetchingPanel
import com.fetch.tv.ui.theme.FetchTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File


/** The Home screen with sample rows, as the README and the screen matrix render it. */
@Composable
internal fun SampleHome() {
    val context = LocalContext.current
    val rows = Samples.catalogRows
    val cw = Samples.continueWatching
    // The first two titles have a cached Best match: they get the ready dot and "Ready tonight".
    val ready = cw.take(2)
    // A movie from the watchlist with a ready pick follows them in Ready tonight.
    val readyMovie = rows.first().items.first().let { movie ->
        com.fetch.tv.domain.model.LibraryEntry(
            id = movie.id, type = "movie", name = movie.name, poster = movie.poster,
            background = movie.background, logo = movie.logo, description = movie.description,
            releaseInfo = movie.releaseInfo, imdbRating = movie.imdbRating, genres = movie.genres,
            addonBaseUrl = null, landscapePoster = movie.landscapePoster
        )
    }
    val readyKeys = ready.mapNotNull { it.bestMatchKey() }.toSet() + readyMovie.bestMatchKey()
    val presentation = remember {
        buildModernHomePresentation(
            input = ModernHomePresentationInput(
                homeRows = rows.map { HomeRow.Catalog(it) },
                catalogRows = rows,
                continueWatchingItems = cw,
                upcomingItems = emptyList(),
                readyTonightItems = ready,
                readyWatchlistMovies = listOf(readyMovie),
                useLandscapePosters = true,
                showCatalogTypeSuffix = true,
                showFullReleaseDate = false,
                showImdbRatings = true,
                localeTag = "en"
            ),
            cache = ModernCarouselRowBuildCache(),
            context = context
        )
    }
    CompositionLocalProvider(LocalReadyBestMatchKeys provides readyKeys) {
    Box(Modifier.fillMaxSize().background(FetchTheme.colors.Background)) {
        ModernHomeContent(
            uiState = HomeUiState(
                catalogRows = rows,
                homeRows = rows.map { HomeRow.Catalog(it) },
                continueWatchingItems = cw,
                isLoading = false,
                layoutPreferencesReady = true,
                modernLandscapePostersEnabled = true,
                heroSectionEnabled = false,
                posterLabelsEnabled = false
            ),
            modernPresentation = presentation,
            focusState = HomeScreenFocusState(),
            onNavigateToDetail = { _, _, _ -> },
            onContinueWatchingClick = {},
            onRequestTrailerPreview = { _, _, _, _ -> },
            onLoadMoreCatalog = { _, _, _ -> },
            onRemoveContinueWatching = { _, _, _, _ -> },
            onSaveFocusState = { _, _, _, _, _, _, _, _ -> }
        )
    }
    }
}

/** The detail page with a sample series, as the README and the screen matrix render it. */
@Composable
internal fun SampleDetail() {
    val meta = Samples.detailMeta
    Box(Modifier.fillMaxSize().background(FetchTheme.colors.Background)) {
        AsyncImage(
            model = meta.background,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to FetchTheme.colors.Background,
                    0.55f to FetchTheme.colors.Background.copy(alpha = 0.55f),
                    1f to Color.Transparent
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to FetchTheme.colors.Background)
            )
        )
        // Same order as the page: the short series hero, then the season's episodes with
        // the next one marked.
        val bestMatch = BestMatchPreview(
            videoId = "sample-long-field:2:6",
            labels = listOf("4K", "DV P8", "TrueHD Atmos", "18.2 GB"),
            buttonLabels = listOf("4K", "Dolby Vision", "Atmos"),
            isCached = true,
            addonName = "Torrentio",
            sourceCount = 14,
            isFinal = true
        )
        Column(Modifier.fillMaxWidth()) {
            HeroContentSection(
                meta = meta,
                nextEpisode = meta.videos.firstOrNull { it.episode == 6 },
                nextToWatch = NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = "sample-long-field:2:6",
                    nextSeason = 2,
                    nextEpisode = 6,
                    displayText = "Play S2 E6"
                ),
                bestMatch = bestMatch,
                onPlayClick = {},
                isSeries = true,
                onSourcesClick = {},
                isInLibrary = true,
                onToggleLibrary = {},
                onLibraryLongPress = {},
                isMovieWatched = false,
                isMovieWatchedPending = false,
                onToggleMovieWatched = {},
                trailerAvailable = true
            )
            com.fetch.tv.ui.screens.detail.EpisodesRow(
                episodes = meta.videos.filter { it.season == 2 },
                onEpisodeClick = {},
                onToggleEpisodeWatched = {},
                upFocusRequester = androidx.compose.ui.focus.FocusRequester.Default,
                nextEpisodeId = "sample-long-field:2:6",
                scrollToEpisodeId = "sample-long-field:2:6"
            )
        }
    }
}
