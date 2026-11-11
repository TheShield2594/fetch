package com.fetch.tv.screenshots

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import coil3.compose.AsyncImage
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.MetaCastMember
import com.fetch.tv.ui.components.PosterCardDefaults
import com.fetch.tv.ui.screens.library.LibraryActivityCardActions
import com.fetch.tv.ui.screens.library.LibraryActivityItem
import com.fetch.tv.ui.screens.library.libraryInProgressContent
import com.fetch.tv.ui.screens.player.PauseOverlay
import com.fetch.tv.ui.screens.search.DiscoverGrid
import com.fetch.tv.ui.screens.search.MergedSearchResult
import com.fetch.tv.ui.screens.search.SearchTopResultCard
import com.fetch.tv.ui.screens.search.SearchTypeFilter
import com.fetch.tv.ui.screens.search.SearchTypeFilterRow
import com.fetch.tv.ui.theme.FetchTheme
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The five screens people live in (Home, Detail, Search, Player, Library), each rendered in a dark
 * and a light-accent theme, with and without "Increase contrast", at the system's largest font
 * scale (1.3). Roadmap F4.
 *
 * Every run checks that the screen composes, lays out and draws, and that no focusable element
 * announces the same words twice to TalkBack (the double-read poster titles of roadmap F5).
 *
 * Pictures: with `FETCH_RECORD_SCREENSHOTS=1` each combination is written to
 * `src/test/screenshots/matrix/`, and once a picture is there later runs compare against it and
 * fail on a visible change. Re-record after an intended visual change and review the PNG diff.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CoreScreenMatrixTest(private val variant: Variant) {

    data class Variant(val theme: AppTheme, val highContrast: Boolean, val fontScale: Float) {
        val slug = "${theme.name.lowercase()}${if (highContrast) "-hc" else ""}-font${(fontScale * 10).toInt()}"
        override fun toString() = slug
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = buildList {
            // The default dark theme and the lightest accent, where focus and contrast are hardest.
            listOf(AppTheme.FETCH, AppTheme.WHITE).forEach { theme ->
                listOf(false, true).forEach { highContrast ->
                    add(arrayOf<Any>(Variant(theme, highContrast, fontScale = 1.3f)))
                }
            }
        }

        private val GOLDEN_DIR = File("src/test/screenshots/matrix")

        /** A pixel counts as changed past this per-channel difference (anti-aliasing noise). */
        private const val CHANNEL_TOLERANCE = 24

        /** A picture fails when more than this share of its pixels changed. */
        private const val MAX_CHANGED_FRACTION = 0.005
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun artwork() {
        SampleArtwork.install(compose.activity.applicationContext)
    }

    @Test
    fun home() = check("home") { SampleHome() }

    @Test
    fun detail() = check("detail") { SampleDetail() }

    @Test
    fun search() = check("search") { SampleSearch() }

    @Test
    fun player() = check("player") { SamplePlayerPause() }

    @Test
    fun library() = check("library") { SampleLibrary() }

    private fun check(screen: String, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                com.fetch.tv.ui.components.LocalWallClock provides { SAMPLE_TIME },
                LocalDensity provides Density(density.density, fontScale = variant.fontScale)
            ) {
                FetchTheme(appTheme = variant.theme, highContrast = variant.highContrast) { content() }
            }
        }
        repeat(4) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).fetchSemanticsNodes()
            .takeIf { it.isNotEmpty() }
            ?.let {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).onFirst().requestFocus()
                compose.mainClock.advanceTimeBy(1_000)
                compose.waitForIdle()
            }

        assertNoDuplicateAnnouncements(screen)
        comparePicture("$screen-${variant.slug}")
    }

    /** TalkBack reads a focusable element's merged descriptions and text; none should repeat. */
    private fun assertNoDuplicateAnnouncements(screen: String) {
        val clickable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        val problems = clickable.mapNotNull { node ->
            val spoken = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            val repeated = spoken.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            repeated.takeIf { it.isNotEmpty() }?.let { "\"${it.joinToString()}\" read more than once in ${spoken.joinToString(" | ")}" }
        }.distinct()
        if (problems.isNotEmpty()) fail("$screen (${variant.slug}):\n" + problems.joinToString("\n"))
    }

    private fun comparePicture(name: String) {
        val golden = File(GOLDEN_DIR, "$name.png")
        val record = System.getenv("FETCH_RECORD_SCREENSHOTS") != null
        if (!record && !golden.exists()) return
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        // Half size keeps the recorded set small; layout breaks still show at this scale.
        val picture = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
        if (record) {
            golden.parentFile?.mkdirs()
            golden.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("Recorded ${golden.canonicalPath}")
            return
        }
        val expected = BitmapFactory.decodeFile(golden.path)
            ?: return fail("$name: ${golden.path} is not a readable PNG")
        if (expected.width != picture.width || expected.height != picture.height) {
            fail("$name: ${picture.width}x${picture.height}, recorded ${expected.width}x${expected.height}")
        }
        var changed = 0
        for (y in 0 until picture.height) {
            for (x in 0 until picture.width) {
                val a = picture.getPixel(x, y)
                val b = expected.getPixel(x, y)
                val differs = (0..3).any { shift -> abs(((a shr (shift * 8)) and 0xFF) - ((b shr (shift * 8)) and 0xFF)) > CHANNEL_TOLERANCE }
                if (differs) changed++
            }
        }
        val fraction = changed.toDouble() / (picture.width * picture.height)
        if (fraction > MAX_CHANGED_FRACTION) {
            fail("$name changed in ${"%.2f".format(fraction * 100)}% of pixels; re-record with FETCH_RECORD_SCREENSHOTS=1 if intended")
        }
    }
}

/** Search with merged results: the type filters, the top result and the results grid. */
@Composable
internal fun SampleSearch() {
    val items = remember { Samples.catalogRows.flatMap { it.items } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background)
            .padding(horizontal = FetchTheme.spacing.screen.horizontal, vertical = FetchTheme.spacing.screen.vertical),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
    ) {
        SearchTypeFilterRow(
            selected = SearchTypeFilter.ALL,
            available = SearchTypeFilter.entries,
            onSelect = {},
            upFocusRequester = null
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)) {
            SearchTopResultCard(
                result = MergedSearchResult(items.first(), listOf("Cinemeta", "TMDB")),
                focusRequester = remember { FocusRequester() },
                upFocusRequester = null,
                onFocused = {},
                onClick = {},
                onLongClick = {}
            )
            Box(Modifier.weight(1f)) {
                DiscoverGrid(
                    items = items.drop(1),
                    posterCardStyle = PosterCardDefaults.Style,
                    focusResults = false,
                    firstItemFocusRequester = remember { FocusRequester() },
                    focusedItemIndex = 0,
                    shouldRestoreFocusedItem = false,
                    onRestoreFocusedItemHandled = {},
                    onItemFocused = {},
                    canLoadMore = false,
                    isLoadingMore = false,
                    onLoadMore = {},
                    onItemClick = { _, _ -> }
                )
            }
        }
    }
}

/** The player's pause overlay over a frame of the sample series. */
@Composable
internal fun SamplePlayerPause() {
    val meta = Samples.detailMeta
    Box(Modifier.fillMaxSize().background(FetchTheme.colors.Background)) {
        AsyncImage(
            model = meta.background,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        PauseOverlay(
            visible = true,
            onClose = {},
            title = meta.name,
            logo = meta.logo,
            episodeTitle = "Hollow Ground",
            season = 2,
            episode = 6,
            year = meta.releaseInfo,
            type = "series",
            description = meta.description,
            cast = listOf(
                MetaCastMember(name = "Ada Brennan", character = "Mara Vey"),
                MetaCastMember(name = "Tomás Keller", character = "Jonah Reed")
            ),
            showClock = false
        )
    }
}

/** Library's In progress tab: the activity grid with progress on each card. */
@Composable
internal fun SampleLibrary() {
    val items = remember {
        Samples.catalogRows.flatMap { it.items }.take(8).mapIndexed { i, preview ->
            LibraryActivityItem(
                id = preview.id,
                type = preview.apiType,
                title = preview.name,
                poster = preview.poster,
                season = null,
                episode = null,
                at = SAMPLE_TIME - i * 3_600_000L,
                progressPercent = 15 + i * 9
            )
        }
    }
    val actions = remember {
        LibraryActivityCardActions(
            focusRequesterFor = { null },
            onFocused = { _, _ -> },
            onClick = { _, _ -> },
            onLongPress = { _, _, _ -> },
            isWatched = { false }
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PosterCardDefaults.Style.width),
        modifier = Modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background)
            .padding(horizontal = FetchTheme.spacing.screen.horizontal, vertical = FetchTheme.spacing.screen.vertical),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
    ) {
        libraryInProgressContent(items, PosterCardDefaults.Style, actions, onFindSomething = {})
    }
}

/** A fixed moment, so clocks and "2 hours ago" lines don't change between runs. */
private val SAMPLE_TIME: Long = java.time.LocalDateTime.of(2026, 10, 29, 21, 47, 0)
    .atZone(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli()
