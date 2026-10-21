package com.fetch.tv.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.requestFocus
import com.fetch.tv.core.streams.BestMatchPreview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.core.qr.QrCodeGenerator
import com.fetch.tv.core.server.PhoneHubState
import com.fetch.tv.core.streams.StreamBadgePlacement
import com.fetch.tv.ui.screens.ExperienceModeSelectionContent
import com.fetch.tv.ui.screens.player.LoadingOverlay
import com.fetch.tv.ui.screens.stream.StreamCard
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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

/**
 * Renders the README screenshots from the app's own composables with sample data.
 * Opt-in: `FETCH_SCREENSHOTS=1 ./gradlew :app:testFullDebugUnitTest --tests '*ReadmeScreenshots*'`
 * writes PNGs to docs/screenshots/. Normal test runs skip it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The app ships conscrypt-android, whose JNI library does not exist on the JVM.
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ReadmeScreenshots {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("Set FETCH_SCREENSHOTS=1 to render the README screenshots", System.getenv("FETCH_SCREENSHOTS") != null)
        SampleArtwork.install(compose.activity.applicationContext)
    }

    @Test
    fun home() = render("home") {
        val context = LocalContext.current
        val rows = Samples.catalogRows
        val cw = Samples.continueWatching
        val presentation = remember {
            buildModernHomePresentation(
                input = ModernHomePresentationInput(
                    homeRows = rows.map { HomeRow.Catalog(it) },
                    catalogRows = rows,
                    continueWatchingItems = cw,
                    upcomingItems = emptyList(),
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

    @Test
    fun detail() = render("detail") {
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
            Box(Modifier.fillMaxSize().padding(start = 48.dp, top = 110.dp)) {
                HeroContentSection(
                    meta = meta,
                    nextEpisode = meta.videos.firstOrNull { it.episode == 6 },
                    nextToWatch = NextToWatch(
                        watchProgress = null,
                        isResume = false,
                        nextVideoId = "sample-long-field:2:6",
                        nextSeason = 2,
                        nextEpisode = 6,
                        displayText = "Play S2E6"
                    ),
                    bestMatch = BestMatchPreview(
                        videoId = "sample-long-field:2:6",
                        labels = listOf("4K", "DV P8", "TrueHD Atmos", "18.2 GB"),
                        isCached = true,
                        addonName = "Torrentio",
                        sourceCount = 14,
                        isFinal = true
                    ),
                    onPlayClick = {},
                    isInLibrary = true,
                    onToggleLibrary = {},
                    onLibraryLongPress = {},
                    isMovieWatched = false,
                    isMovieWatchedPending = false,
                    onToggleMovieWatched = {},
                    trailerAvailable = true
                )
            }
        }
    }

    @Test
    fun fetchingPanel() = render("fetching") {
        val meta = Samples.detailMeta
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FetchTheme.colors.Background)
        ) {
            LoadingOverlay(
                visible = true,
                backdropUrl = meta.background,
                logoUrl = meta.logo,
                title = meta.name,
                modifier = Modifier.fillMaxSize()
            )
            FetchingPanel(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp, start = 48.dp, end = 48.dp),
                sourceChips = listOf(
                    SourceChipItem("Jellyfin", SourceChipStatus.SUCCESS),
                    SourceChipItem("Torrentio", SourceChipStatus.SUCCESS),
                    SourceChipItem("Comet", SourceChipStatus.SUCCESS),
                    SourceChipItem("MediaFusion", SourceChipStatus.LOADING),
                    SourceChipItem("Peerflix", SourceChipStatus.LOADING)
                ),
                bestSoFar = BestMatchPreview(
                    videoId = "tt0000001:2:6",
                    labels = listOf("4K", "DV P8", "TrueHD Atmos", "18.2 GB"),
                    isCached = true,
                    addonName = "Torrentio",
                    sourceCount = 14,
                    isFinal = false
                ),
                deadlineElapsedMs = null,
                onPlayNow = {},
                onShowAllSources = {}
            )
        }
    }

    @Test
    fun sources() = render("sources") {
        val meta = Samples.detailMeta
        Box(Modifier.fillMaxSize().background(FetchTheme.colors.Background)) {
            AsyncImage(
                model = meta.background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(FetchTheme.colors.Background.copy(alpha = 0.55f)))
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.6f)
                    .padding(24.dp)
                    .background(FetchTheme.colors.BackgroundElevated.copy(alpha = 0.94f), RoundedCornerShape(FetchTheme.radii.md))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Samples.streams.forEach { stream ->
                    StreamCard(
                        stream = stream,
                        showFileSizeBadges = false,
                        showAddonLogo = true,
                        badgePlacement = StreamBadgePlacement.BOTTOM,
                        onClick = {}
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.4f)
                    .padding(start = 48.dp, end = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AsyncImage(model = meta.logo, contentDescription = null, modifier = Modifier.height(90.dp))
                Text(
                    text = "S2 E6 · Episode 6",
                    style = MaterialTheme.typography.titleMedium,
                    color = FetchTheme.colors.TextSecondary
                )
            }
        }
    }

    @Test
    fun firstRun() = render("first-run") {
        val qr = remember { QrCodeGenerator.generate("http://192.168.1.20:8090/pair?token=sample", 512) }
        Box(Modifier.fillMaxSize().background(FetchTheme.colors.Background)) {
            ExperienceModeSelectionContent(
                showPhoneSetup = true,
                qrCode = qr,
                phoneState = PhoneHubState(url = "http://192.168.1.20:8090", running = true),
                essentialFocusRequester = remember { FocusRequester() },
                onChoose = {}
            )
        }
    }

    private fun render(name: String, focusFirst: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent { FetchTheme { content() } }
        // Let images load and enter animations settle before drawing.
        repeat(6) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        if (focusFirst) {
            // On a TV something always has focus; show the focus ring the way a remote would.
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).onFirst().requestFocus()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        // Draw the window in software: Robolectric has no hardware redraw for captureToImage to wait on.
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        val out = File(System.getProperty("user.dir"), "../docs/screenshots/$name.png").canonicalFile
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Wrote $out (${bitmap.width}x${bitmap.height})")
    }
}
