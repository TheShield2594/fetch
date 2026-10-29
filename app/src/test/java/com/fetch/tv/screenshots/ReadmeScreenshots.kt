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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
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
import com.fetch.tv.ui.components.sidebarScrimBrush
import com.fetch.tv.ui.components.ProfileAvatarCircle
import com.fetch.tv.ui.theme.FetchComponents
import com.fetch.tv.ui.theme.FetchLayout
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
    fun home() = render("home") { SampleHome() }

    @Test
    fun sidebar() = render("sidebar", focusOn = "Discover") {
        Box(Modifier.fillMaxSize().background(FetchTheme.colors.Background)) {
            // The scaffold pads root screens by this much so the icon column has its own lane.
            Box(Modifier.fillMaxSize().padding(start = FetchLayout.tokens.sidebarContentOffset)) { SampleHome() }
            // Same fade and column as FetchSidebarScaffold while the sidebar has focus.
            Box(Modifier.fillMaxSize().background(sidebarScrimBrush(FetchTheme.colors.Background)))
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = FetchComponents.tokens.sidebar.floatingInset),
                verticalArrangement = Arrangement.spacedBy(FetchComponents.tokens.sidebar.floatingGap)
            ) {
                SidebarSampleItem("Home", selected = true) { tint ->
                    Icon(Icons.Default.Home, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
                SidebarSampleItem("Search") { tint ->
                    Icon(rememberRawSvgPainter(R.raw.sidebar_search), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
                SidebarSampleItem("Discover") { tint ->
                    Icon(Icons.Default.Explore, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
                SidebarSampleItem("Library") { tint ->
                    Icon(rememberRawSvgPainter(R.raw.sidebar_library), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
                SidebarSampleItem("Settings") { tint ->
                    Icon(rememberRawSvgPainter(R.raw.sidebar_settings), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(FetchComponents.tokens.sidebar.floatingGap))
                SidebarSampleItem("Alex") { _ ->
                    ProfileAvatarCircle(name = "Alex", colorHex = "#C26A3D", size = 40.dp)
                }
            }
        }
    }

    @Composable
    private fun SidebarSampleItem(label: String, selected: Boolean = false, leading: @Composable (Color) -> Unit) {
        FloatingSidebarButton(label = label, selected = selected, expanded = true, onClick = {}, leading = leading)
    }

    @Composable
    private fun SampleHome() {
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

    @Test
    fun detail() = render("detail") { SampleDetail() }

    @Composable
    private fun SampleDetail() {
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
                        displayText = "Play S2 E6"
                    ),
                    bestMatch = BestMatchPreview(
                        videoId = "sample-long-field:2:6",
                        labels = listOf("4K", "DV P8", "TrueHD Atmos", "18.2 GB"),
                        buttonLabels = listOf("4K", "Dolby Vision", "Atmos"),
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

    // The Signal theme: the same screens in monochrome and dots (written next to the README ones).
    @Test
    fun signalHome() = render("signal-home", appTheme = AppTheme.SIGNAL) { SampleHome() }

    // Down twice from the first card (Continue Watching, Ready tonight) lands on the first
    // "At a glance" tile: the row scrolls up and the hero shows its title.
    @Test
    fun signalAtAGlance() = render("signal-glance", pressDown = 2, appTheme = AppTheme.SIGNAL) {
        CompositionLocalProvider(com.fetch.tv.ui.screens.home.LocalHomeClock provides { SAMPLE_EVENING }) { SampleHome() }
    }

    @Test
    fun signalFetching() = render("signal-fetching", appTheme = AppTheme.SIGNAL) { SampleFetching() }

    @Test
    fun signalDetail() = render("signal-detail", appTheme = AppTheme.SIGNAL) { SampleDetail() }

    @Test
    fun signalAmbient() = render("signal-ambient", focusFirst = false, appTheme = AppTheme.SIGNAL) {
        val resume = (Samples.continueWatching.first() as com.fetch.tv.ui.screens.home.ContinueWatchingItem.InProgress).progress
        AmbientClock(resume = resume, now = { SAMPLE_EVENING })
    }

    @Test
    fun fetchingPanel() = render("fetching") { SampleFetching() }

    @Composable
    private fun SampleFetching() {
        val meta = Samples.detailMeta
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FetchTheme.colors.Background)
        ) {
            // Same layout as StreamScreen: the logo centres above the measured panel.
            var panelHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            LoadingOverlay(
                visible = true,
                backdropUrl = meta.background,
                logoUrl = meta.logo,
                title = meta.name,
                modifier = Modifier.fillMaxSize(),
                bottomInset = if (panelHeight > 0.dp) panelHeight + 48.dp else 0.dp
            )
            FetchingPanel(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp, start = 48.dp, end = 48.dp)
                    .onSizeChanged { panelHeight = with(density) { it.height.toDp() } },
                sourceChips = listOf(
                    SourceChipItem("Jellyfin", SourceChipStatus.SUCCESS),
                    SourceChipItem("Torrentio", SourceChipStatus.SUCCESS),
                    SourceChipItem("Comet", SourceChipStatus.SUCCESS),
                    SourceChipItem("MediaFusion", SourceChipStatus.LOADING),
                    SourceChipItem("Peerflix", SourceChipStatus.LOADING)
                ),
                bestSoFar = BestMatchPreview(
                    videoId = "tt0000001:2:6",
                    // The three cuts BestMatchPreviewer makes of one file.
                    labels = listOf("4K", "DV P8", "TrueHD Atmos", "18.2 GB"),
                    specLabels = listOf("4K", "DV P8", "TrueHD Atmos"),
                    buttonLabels = listOf("4K", "Dolby Vision", "Atmos"),
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
            // Same split as StreamScreen: the real title column, then the source list.
            Row(Modifier.fillMaxSize()) {
                com.fetch.tv.ui.screens.stream.LeftContentSection(
                    title = meta.name,
                    logo = meta.logo,
                    isEpisode = true,
                    season = 2,
                    episode = 6,
                    episodeName = "Hollow Ground",
                    runtime = 52,
                    genres = null,
                    year = null,
                    modifier = Modifier.weight(0.36f).fillMaxHeight()
                )
                Column(
                    modifier = Modifier
                        .weight(0.64f)
                        .fillMaxHeight()
                        .padding(24.dp)
                        .background(FetchTheme.colors.BackgroundElevated.copy(alpha = 0.94f), RoundedCornerShape(FetchTheme.radii.md))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // The real list, so the screenshot shows Best match's pick and the quality sections.
                    com.fetch.tv.ui.screens.stream.StreamsList(
                        streams = Samples.streams,
                        streamExclusions = emptyMap(),
                        sections = com.fetch.tv.ui.screens.stream.sourceSections(
                            Samples.streams, emptyMap(), bestMatchFirst = true, addonFilter = null
                        ),
                        sharedStream = null,
                        partySource = null,
                        onSharedStreamSelected = {},
                        onStreamSelected = {},
                        showFileSizeBadges = false,
                        badgePlacement = StreamBadgePlacement.BOTTOM
                    )
                }
            }
        }
    }

    @Test
    fun firstRun() = render("first-run", focusFirst = false) {
        val qr = remember { QrCodeGenerator.generate("http://192.168.1.20:8090/pair?token=sample", 512) }
        Box(Modifier.fillMaxSize().background(FetchTheme.colors.Background)) {
            FirstRunWizardContent(
                step = FirstRunStep.SOURCES,
                steps = FirstRunStep.entries,
                status = FirstRunStatus(addonCount = 2, streamAddonCount = 1, mediaServers = listOf("Jellyfin")),
                qrCode = qr,
                phoneState = PhoneHubState(url = "http://192.168.1.20:8090", running = true),
                tmdbAvailable = true,
                websiteSignedIn = null,
                serverPairing = {},
                onBack = {},
                onNext = {},
                onToggleTmdb = {},
                onChoose = {}
            )
        }
    }

    private fun render(
        name: String,
        focusFirst: Boolean = true,
        focusOn: String? = null,
        pressDown: Int = 0,
        appTheme: AppTheme = DEFAULT_APP_THEME,
        content: @Composable () -> Unit
    ) {
        compose.setContent { FetchTheme(appTheme = appTheme) { content() } }
        // Let images load and enter animations settle before drawing.
        repeat(6) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        if (focusOn != null) {
            compose.onNodeWithContentDescription(focusOn).requestFocus()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        } else if (focusFirst) {
            // On a TV something always has focus; show the focus ring the way a remote would.
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).onFirst().requestFocus()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        repeat(pressDown) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
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

/** 9:47 pm on a Thursday, at an even second so the clock's colon is lit. */
private val SAMPLE_EVENING: Long = java.time.LocalDateTime.of(2026, 10, 29, 21, 47, 0)
    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
