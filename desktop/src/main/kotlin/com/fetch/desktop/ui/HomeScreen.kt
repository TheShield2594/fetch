package com.fetch.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.episodeTag
import com.fetch.desktop.data.remainingLabel
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.delay

/**
 * Home, laid out like the TV app: a hero with the highlighted title's backdrop, then the catalog
 * rows. Hovering a poster puts it in the hero.
 */
@Composable
fun HomeScreen(state: AppState) {
    when {
        state.addonsLoading && state.continueWatching.isEmpty() -> CenteredProgress()
        state.rows.isEmpty() && state.servers.rows.isEmpty() && state.continueWatching.isEmpty() && state.collections.collections.isEmpty() -> EmptyState(
            title = "Nothing to show yet",
            body = "Install an addon with catalogs in Settings › Content & Discovery › Addons."
        )
        else -> HomeContent(state)
    }
}

/** What the hero is showing: a catalog title, or a Continue Watching entry with its resume line. */
private sealed interface HeroSubject {
    val backdrop: String?

    data class Title(val preview: MetaPreview) : HeroSubject {
        override val backdrop: String? get() = preview.backdropUrl
    }

    data class Resume(val entry: ProgressEntry) : HeroSubject {
        override val backdrop: String? get() = entry.backdrop ?: entry.poster
    }
}

@Composable
private fun HomeContent(state: AppState) {
    val settings = state.settings
    val continueWatching = if (settings.showContinueWatching) state.continueWatching else emptyList()
    val homeRows = state.homeRows
    // Continue Watching leads, as on TV; otherwise the first title with a backdrop, until the
    // pointer picks another.
    val fallback: HeroSubject? = continueWatching.firstOrNull()?.let { HeroSubject.Resume(it) }
        ?: (
            homeRows.asSequence().flatMap { it.items }.firstOrNull { it.background != null }
                ?: homeRows.asSequence().flatMap { it.items }.firstOrNull()
            )?.let { HeroSubject.Title(it) }
    var hovered by remember { mutableStateOf<HeroSubject?>(null) }
    var featured by remember { mutableStateOf<HeroSubject?>(null) }
    // A short pause before the hero follows the pointer, so sweeping across a row doesn't
    // load every backdrop on the way.
    LaunchedEffect(hovered) {
        val next = hovered ?: return@LaunchedEffect
        delay(220)
        featured = next
    }
    // A removed Continue Watching entry mustn't linger in the hero.
    val hero = featured?.takeUnless { it is HeroSubject.Resume && continueWatching.none { e -> e.contentId == it.entry.contentId } }
        ?: fallback
    val listState = state.homeListState
    // "Because you watched": TMDB's picks for the titles finished last, after Continue Watching.
    val finished = state.progress.finished
    LaunchedEffect(finished.take(2).map { it.contentId }, settings.tmdb, settings.tmdbApiKey) {
        state.tmdb.refreshBecauseYouWatched(finished)
    }
    // The YouTube row's subscriptions, fetched again when older than ten minutes.
    LaunchedEffect(settings.youtube.homeRowEnabled, state.youtube.signedIn) { state.youtube.onHomeVisible() }
    val cwActions = remember(state) {
        ContinueWatchingActions(
            onPlay = { state.openContinueWatching(it, play = true) },
            onDetails = { state.openContinueWatching(it, play = false) },
            onStartOver = state::startFromBeginning,
            onRemove = state::removeFromContinueWatching,
            onToggleQueue = state::toggleQueued,
            isQueued = { state.extras.isQueued(it.videoId) }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * 0.56f).coerceIn(340.dp, 600.dp)
        Backdrop(hero?.backdrop, height = heroHeight + 120.dp)
        ScrollbarBox(listState, Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(36.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "hero") {
                    when (hero) {
                        is HeroSubject.Title -> Hero(
                            item = hero.preview,
                            height = heroHeight,
                            onPlay = { state.navigate(Screen.Detail(hero.preview, autoPlay = hero.preview.type != ContentType.SERIES)) },
                            onDetails = { state.navigate(Screen.Detail(hero.preview)) },
                            mdbListRatings = state.mdbListRatings.forHero(hero.preview.imdbId ?: hero.preview.id, hero.preview.apiType),
                            mdbListOrder = settings.mdblist.enabledOrder()
                        )
                        is HeroSubject.Resume -> ResumeHero(
                            entry = hero.entry,
                            height = heroHeight,
                            onPlay = { cwActions.onPlay(hero.entry) },
                            onDetails = { cwActions.onDetails(hero.entry) }
                        )
                        null -> Spacer(Modifier.height(heroHeight))
                    }
                }
                if (continueWatching.isNotEmpty()) {
                    item(key = "continue-watching") {
                        ContinueWatchingRow(continueWatching, cwActions, onHover = { hovered = HeroSubject.Resume(it) })
                    }
                }
                // "Separate Upcoming row": Next Up episodes that haven't aired, soonest first.
                val upcoming = if (settings.showContinueWatching) state.upcoming else emptyList()
                if (upcoming.isNotEmpty()) {
                    item(key = "upcoming") {
                        ContinueWatchingRow(upcoming, cwActions, onHover = { hovered = HeroSubject.Resume(it) }, title = "Upcoming")
                    }
                }
                // "YouTube subscriptions" (Settings › YouTube), after Continue Watching as on TV.
                if (state.youtube.homeRowShown) {
                    item(key = "youtube") { YouTubeHomeRow(state) }
                }
                // Pinned collections lead the rows, as on TV.
                items(state.collections.pinned, key = { "collection|${it.id}" }) { collection ->
                    CollectionRowView(collection, onOpen = { state.navigate(Screen.Folder(collection.id, it.id)) })
                }
                items(state.tmdb.becauseYouWatched, key = { "byw|${it.seed.contentId}" }) { row ->
                    MediaRowView(
                        title = "Because you watched ${row.seed.name}",
                        subtitle = null,
                        items = row.items.filter { state.allowed(it) && !(settings.hideUnreleased && com.fetch.desktop.isUnreleased(it)) },
                        loading = false,
                        error = null,
                        onOpen = { state.navigate(Screen.Detail(it)) },
                        onHover = { hovered = HeroSubject.Title(it) },
                        showLabels = settings.showPosterLabels
                    )
                }
                items(homeRows, key = { it.key }) { row ->
                    CatalogRowView(
                        row,
                        onOpen = { state.navigate(Screen.Detail(it)) },
                        onHover = {
                            hovered = HeroSubject.Title(it)
                            // MDBList's ratings for the whole row in one request, before the hero needs them.
                            state.mdbListRatings.prefetch(row.items.map { p -> (p.imdbId ?: p.id) to p.apiType })
                        },
                        title = row.title(settings.showCatalogType),
                        subtitle = row.addon.displayName.takeIf { settings.showAddonName },
                        showLabels = settings.showPosterLabels,
                        onSeeAll = { state.openSeeAll(row, row.title(settings.showCatalogType)) }
                    )
                }
                // The other collections follow the catalog rows.
                items(state.collections.unpinned, key = { "collection|${it.id}" }) { collection ->
                    CollectionRowView(collection, onOpen = { state.navigate(Screen.Folder(collection.id, it.id)) })
                }
                // Filter rows over your server libraries.
                items(state.serverFilterRows, key = { "server-filter|${it.row.id}" }) { view ->
                    MediaRowView(
                        title = view.row.title,
                        subtitle = null,
                        items = view.items.filter(state::allowed),
                        loading = view.loading,
                        error = view.error,
                        onOpen = { state.navigate(Screen.Detail(it)) },
                        onHover = { hovered = HeroSubject.Title(it) },
                        showLabels = settings.showPosterLabels
                    )
                }
                // Your Jellyfin, Emby and Plex libraries.
                items(state.servers.rows, key = { "server|${it.key}" }) { row ->
                    MediaRowView(
                        title = row.title,
                        subtitle = row.subtitle.takeIf { settings.showAddonName },
                        items = row.items.filter(state::allowed),
                        loading = row.loading,
                        error = row.error,
                        onOpen = { state.navigate(Screen.Detail(it)) },
                        onHover = { hovered = HeroSubject.Title(it) },
                        showLabels = settings.showPosterLabels
                    )
                }
            }
        }
    }
}

/** The hero for a Continue Watching entry: "Resume S2 E5" with the time left and a progress line. */
@Composable
private fun ResumeHero(entry: ProgressEntry, height: Dp, onPlay: () -> Unit, onDetails: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(height)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.align(Alignment.BottomStart)
                .padding(start = PageGutter, end = PageGutter, bottom = 8.dp)
                .widthIn(max = 760.dp)
        ) {
            TitleArt(entry.name, entry.logo)
            val line = listOfNotNull(episodeTag(entry.season, entry.episode), entry.episodeTitle).joinToString(" · ")
            if (line.isNotEmpty()) FactsLine(facts = listOf(line), imdbRating = null)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                val upcoming = com.fetch.desktop.data.WatchHistory.isUpcoming(entry, java.time.LocalDate.now())
                if (upcoming) {
                    PrimaryButton("Details", onClick = onDetails, icon = FetchIcons.Info, detail = entry.nextUpBadge())
                } else {
                    PrimaryButton(entry.playLabel(), onClick = onPlay, detail = remainingLabel(entry) ?: entry.nextUpBadge().takeIf { entry.upNext })
                }
                if (!upcoming) SecondaryButton("Details", onClick = onDetails, icon = FetchIcons.Info)
            }
            if (!entry.upNext) ProgressBar(entry.fraction(), Modifier.width(360.dp))
        }
    }
}

/** The highlighted title's backdrop, faded into the page on the left and at the bottom. */
@Composable
fun Backdrop(url: String?, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height)) {
        Crossfade(targetState = url, animationSpec = tween(450), label = "backdrop") { target ->
            if (target != null) {
                AsyncImage(
                    model = target,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        val background = MaterialTheme.colorScheme.background
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to background,
                    0.30f to background.copy(alpha = 0.85f),
                    0.65f to background.copy(alpha = 0.15f),
                    1f to Color.Transparent
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.25f),
                    0.55f to Color.Transparent,
                    1f to background
                )
            )
        )
    }
}

@Composable
private fun Hero(
    item: MetaPreview,
    height: Dp,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    /** MDBList's ratings with "Use ratings on Home's hero" on: the first three, in place of the IMDb badge, as on TV. */
    mdbListRatings: com.fetch.tv.domain.model.MDBListRatings? = null,
    mdbListOrder: List<String> = emptyList()
) {
    Box(Modifier.fillMaxWidth().height(height)) {
        Crossfade(targetState = item, animationSpec = tween(300), label = "hero", modifier = Modifier.align(Alignment.BottomStart)) { shown ->
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(start = PageGutter, end = PageGutter, bottom = 8.dp).widthIn(max = 760.dp)
            ) {
                TitleArt(shown.name, shown.logo)
                FactsLine(
                    facts = listOfNotNull(
                        shown.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() },
                        shown.releaseInfo,
                        shown.runtime
                    ),
                    imdbRating = shown.imdbRating.takeIf { mdbListRatings == null || shown != item }
                )
                if (shown == item) mdbListRatings?.let { MdbListRatingsRow(it, mdbListOrder, maxItems = 3) }
                shown.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = FetchPalette.TextPrimary.copy(alpha = 0.9f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (shown.type == ContentType.SERIES) {
                        PrimaryButton("Episodes", onClick = onDetails)
                    } else {
                        PrimaryButton("Play", onClick = onPlay)
                        SecondaryButton("More info", onClick = onDetails, icon = FetchIcons.Info)
                    }
                }
            }
        }
    }
}

/** The title's logo when the addon has one, otherwise its name set large. */
@Composable
fun TitleArt(name: String, logo: String?, maxLogoHeight: Dp = 120.dp, fontSize: Int = 52) {
    var logoFailed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !logoFailed) {
        AsyncImage(
            model = logo,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { logoFailed = true },
            modifier = Modifier.heightIn(max = maxLogoHeight).widthIn(max = 480.dp)
        )
    } else {
        Text(
            name.uppercase(),
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.05).sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = FetchPalette.Accent)
    }
}
