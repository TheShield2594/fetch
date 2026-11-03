package com.fetch.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.fetch.desktop.badges.StreamBadgesState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.SourcesState
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.remainingLabel
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.tv.core.streams.SpecLine
import com.fetch.tv.core.streams.ranking.RankedStream
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaCastMember
import com.fetch.tv.domain.model.MetaCompany
import com.fetch.desktop.Screen
import com.fetch.desktop.tmdb.TmdbEntityKind
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Video

/**
 * A title's page, laid out like the TV detail page: backdrop, title art, facts, a Play button
 * that names the best source, the best match and a way into every source, then episodes for a
 * series and the cast.
 */
@Composable
fun DetailScreen(state: AppState, preview: MetaPreview) {
    val meta = state.detailMeta
    val isSeries = preview.type == ContentType.SERIES ||
        (meta?.videos?.any { it.season != null } == true && preview.type != ContentType.MOVIE)
    val listState = rememberLazyListState()

    // A rating limit blocks the title, as on TV.
    if (!state.detailLoading && !state.allowed(meta, preview)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Not available on ${state.profile.name}", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("This title is rated above the limit set for this profile.", color = FetchPalette.TextSecondary)
                if (state.canGoBack) SecondaryButton("Back", onClick = state::back, icon = FetchIcons.Back)
            }
        }
        return
    }

    CompositionLocalProvider(LocalStreamBadges provides state.badges) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroMinHeight = maxHeight * 0.5f
        Backdrop(meta?.background ?: preview.backdropUrl, height = maxHeight * 0.85f)
        ScrollbarBox(listState, Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 24.dp, bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "back") {
                    if (state.canGoBack) {
                        CircleIconButton(
                            FetchIcons.Back,
                            "Back",
                            onClick = state::back,
                            modifier = Modifier.padding(horizontal = PageGutter)
                        )
                    }
                }
                item(key = "hero") {
                    Hero(
                        preview = preview,
                        meta = meta,
                        sources = state.sources,
                        isSeries = isSeries,
                        onPlay = state::playBest,
                        onAllSources = { state.sourcesPanelOpen = true },
                        onTrailer = { ytId -> state.playTrailer(ytId, meta?.name ?: preview.name) },
                        resume = state.sources?.videoId?.let(state::resumeEntry),
                        onStartOver = state::startOverDetail,
                        inLibrary = state.isInLibrary(preview.id),
                        onToggleLibrary = { state.toggleLibrary(preview, meta) },
                        onManageLists = { state.manageLists(preview) },
                        watched = state.isTitleWatched(preview, meta?.videos.orEmpty()),
                        tmdbRating = state.tmdb.tmdbRating?.takeIf { state.tmdb.extrasFor == meta?.id },
                        mdbListRatings = state.mdbListRatings.forTitle(meta?.imdbId ?: preview.imdbId ?: preview.id, preview.apiType),
                        mdbListOrder = state.settings.mdblist.enabledOrder(),
                        onToggleWatched = { on ->
                            if (isSeries) state.setWatched(preview, meta?.videos.orEmpty().filter { (it.season ?: 0) > 0 && com.fetch.desktop.isAired(it) }, on)
                            else state.setWatched(preview, emptyList(), on)
                        },
                        minHeight = heroMinHeight
                    )
                }
                item(key = "sources") {
                    SourcesSummary(
                        sources = state.sources,
                        loadingMeta = state.detailLoading,
                        onPlay = state::play,
                        onAllSources = { state.sourcesPanelOpen = true },
                        menu = { sourceMenu(state, it) }
                    )
                }
                if (isSeries && !state.detailLoading) {
                    item(key = "episodes") {
                        val videos = meta?.videos.orEmpty()
                        if (videos.isEmpty()) {
                            Note("No episode list from your addons.")
                        } else {
                            Episodes(
                                videos = videos,
                                selectedId = state.sources?.videoId,
                                onPick = {
                                    state.loadSources(preview, it)
                                    state.sourcesPanelOpen = true
                                },
                                watched = state::isWatched,
                                progress = { id -> state.resumeEntry(id)?.let { e -> if (e.durationMs > 0) e.positionMs.toFloat() / e.durationMs else e.remotePercent?.div(100f) } },
                                menu = { video ->
                                    val watched = state.isWatched(video.id)
                                    val season = videos.filter { it.season == video.season && com.fetch.desktop.isAired(it) }
                                    buildList {
                                        add(ContextMenuItem("Play") {
                                            state.loadSources(preview, video)
                                            state.playBest()
                                        })
                                        add(ContextMenuItem("Choose source") {
                                            state.loadSources(preview, video)
                                            state.sourcesPanelOpen = true
                                        })
                                        add(ContextMenuItem(if (watched) "Mark as unwatched" else "Mark as watched") { state.setWatched(preview, listOf(video), !watched) })
                                        val before = com.fetch.desktop.data.WatchHistory.episodesBefore(videos, video)
                                        if (before.any { !state.isWatched(it.id) }) {
                                            add(ContextMenuItem("Mark previous episodes as watched") { state.setWatched(preview, before, true) })
                                        }
                                        if (video.season != null && video.season != 0) {
                                            val seasonWatched = season.isNotEmpty() && season.all { state.isWatched(it.id) }
                                            add(ContextMenuItem(if (seasonWatched) "Mark season ${video.season} as unwatched" else "Mark season ${video.season} as watched") {
                                                state.setWatched(preview, season, !seasonWatched)
                                            })
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                val cast = meta?.castMembers.orEmpty()
                if (cast.isNotEmpty()) {
                    item(key = "cast") { Cast(cast, onOpen = state::openPerson) }
                }
                val tmdbExtras = state.tmdb.takeIf { it.extrasFor == meta?.id }
                tmdbExtras?.collection?.let { collection ->
                    item(key = "collection") {
                        MediaRowView(
                            title = collection.name,
                            subtitle = "Collection",
                            items = collection.parts.filter(state::allowed),
                            loading = false,
                            error = null,
                            onOpen = { state.navigate(Screen.Detail(it)) },
                            showLabels = state.settings.showPosterLabels
                        )
                    }
                }
                tmdbExtras?.moreLikeThis?.filter(state::allowed)?.takeIf { it.isNotEmpty() }?.let { recs ->
                    item(key = "more-like-this") {
                        MediaRowView(
                            title = "More like this",
                            subtitle = null,
                            items = recs,
                            loading = false,
                            error = null,
                            onOpen = { state.navigate(Screen.Detail(it)) },
                            showLabels = state.settings.showPosterLabels
                        )
                    }
                }
                val companies = meta?.networks.orEmpty().map { it to TmdbEntityKind.NETWORK } +
                    meta?.productionCompanies.orEmpty().map { it to TmdbEntityKind.COMPANY }
                if (companies.any { it.first.tmdbId != null }) {
                    item(key = "companies") { Companies(companies.filter { it.first.tmdbId != null }.distinctBy { it.first.tmdbId to it.second }) { c, k -> state.openEntity(c, k) } }
                }
            }
        }

        // The full list slides in from the right over a dimmed page.
        AnimatedVisibility(visible = state.sourcesPanelOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        state.sourcesPanelOpen = false
                    }
            )
        }
        AnimatedVisibility(
            visible = state.sourcesPanelOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            val sources = state.sources
            if (sources != null) {
                SourcesPanel(
                    sources = sources,
                    onPlay = state::play,
                    onClose = { state.sourcesPanelOpen = false },
                    menu = { sourceMenu(state, it) },
                    modifier = Modifier.width(560.dp).fillMaxHeight()
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** The stream badges to draw on source cards. */
val LocalStreamBadges = staticCompositionLocalOf<StreamBadgesState?> { null }

@Composable
private fun Hero(
    preview: MetaPreview,
    meta: Meta?,
    sources: SourcesState?,
    isSeries: Boolean,
    onPlay: () -> Unit,
    onAllSources: () -> Unit,
    onTrailer: (String) -> Unit,
    resume: ProgressEntry?,
    onStartOver: () -> Unit,
    inLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onManageLists: () -> Unit,
    watched: Boolean,
    onToggleWatched: (Boolean) -> Unit,
    tmdbRating: Float?,
    minHeight: Dp,
    /** MDBList's ratings (Settings › MDBList), in [mdbListOrder]; they replace the addon's IMDb badge, as on TV. */
    mdbListRatings: com.fetch.tv.domain.model.MDBListRatings? = null,
    mdbListOrder: List<String> = emptyList()
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
        modifier = Modifier
            .padding(horizontal = PageGutter)
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .heightIn(min = minHeight)
    ) {
        TitleArt(meta?.name ?: preview.name, meta?.logo ?: preview.logo, maxLogoHeight = 140.dp, fontSize = 60)
        FactsLine(
            facts = listOfNotNull(
                (meta?.genres ?: preview.genres).take(3).joinToString(", ").takeIf { it.isNotBlank() },
                meta?.releaseInfo ?: preview.releaseInfo
            ),
            imdbRating = (meta?.imdbRating ?: preview.imdbRating).takeIf { mdbListRatings == null }
        )
        mdbListRatings?.let { MdbListRatingsRow(it, mdbListOrder) }
        // TMDB's own rating beside IMDb's, and who made it, as on the TV detail page.
        val credit = when {
            isSeries -> meta?.castMembers?.filter { it.character == "Creator" }?.map { it.name }?.takeIf { it.isNotEmpty() }?.let { "Created by ${it.take(3).joinToString(", ")}" }
            else -> meta?.director?.takeIf { it.isNotEmpty() }?.let { "Directed by ${it.take(3).joinToString(", ")}" }
        }
        if (tmdbRating != null || credit != null || meta?.status != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                tmdbRating?.let { SpecChip("TMDB %.1f".format(java.util.Locale.ROOT, it)) }
                meta?.status?.takeIf { isSeries }?.let { SpecChip(it) }
                credit?.let { Text(it, color = FetchPalette.TextSecondary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        val ageRating = meta?.ageRating ?: preview.ageRating
        val second = listOfNotNull(meta?.runtime ?: preview.runtime, (meta?.language ?: preview.language)?.uppercase()?.take(12))
        if (ageRating != null || second.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ageRating?.let { SpecChip(it) }
                second.forEach { Text(it, color = FetchPalette.TextSecondary, fontSize = 16.sp) }
            }
        }
        (meta?.description ?: preview.description)?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 26.sp,
                color = FetchPalette.TextPrimary.copy(alpha = 0.92f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Wraps on a narrow window instead of clipping the last buttons.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            val best = sources?.best
            // "Resume S2 E5" with the time left when this episode or movie was started, as on TV.
            val verb = if (resume != null) "Resume" else "Play"
            val playLabel = listOfNotNull(verb, sources?.episodeTag).joinToString(" ")
            val specs = when {
                sources == null -> if (isSeries) "Pick an episode" else "Loading…"
                sources.loading -> "Finding the best source…"
                best != null -> best.specText() ?: best.stream.addonName
                else -> "No playable source"
            }
            val detail = listOfNotNull(resume?.let(::remainingLabel), specs).joinToString(" · ")
            PrimaryButton(
                text = playLabel,
                detail = detail,
                onClick = onPlay,
                enabled = sources != null && (sources.loading || sources.ranked.isNotEmpty())
            )
            if (resume != null && sources != null) {
                SecondaryButton("Start over", onClick = onStartOver, icon = FetchIcons.Replay10)
            }
            if (sources != null && !sources.loading && sources.ranked.isNotEmpty()) {
                SecondaryButton("All sources", onClick = onAllSources, icon = FetchIcons.List)
            }
            SecondaryButton(
                if (inLibrary) "In library" else "Add to library",
                onClick = onToggleLibrary,
                icon = if (inLibrary) FetchIcons.Check else FetchIcons.Add
            )
            SecondaryButton("Lists", onClick = onManageLists, icon = FetchIcons.List)
            if (!isSeries || meta?.videos?.any { (it.season ?: 0) > 0 } == true) {
                SecondaryButton(
                    if (watched) "Watched" else if (isSeries) "Mark all watched" else "Mark watched",
                    onClick = { onToggleWatched(!watched) },
                    icon = if (watched) FetchIcons.Check else null
                )
            }
            val trailer = meta?.trailerYtIds?.firstOrNull() ?: preview.trailerYtIds.firstOrNull()
            if (trailer != null) {
                CircleIconButton(FetchIcons.Trailer, "Play trailer", onClick = { onTrailer(trailer) }, size = 52.dp)
            }
        }
    }
}

private fun RankedStream.specText(): String? =
    SpecLine.of(labels = descriptor.summaryLabels(), isCached = descriptor.isDebridCached, cachedLabel = "Cached")?.text

/** The best match and a card into every source, as on the TV detail page. */
@Composable
private fun SourcesSummary(
    sources: SourcesState?,
    loadingMeta: Boolean,
    onPlay: (RankedStream) -> Unit,
    onAllSources: () -> Unit,
    menu: @Composable (RankedStream) -> List<ContextMenuItem>
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = PageGutter)) {
        Text("Sources", style = MaterialTheme.typography.titleLarge)
        when {
            loadingMeta -> LoadingLine("Loading details…")
            sources == null -> Text("Pick an episode below to see its sources.", color = FetchPalette.TextMuted)
            sources.loading -> LoadingLine("Asking your addons…")
            sources.ranked.isEmpty() -> Text(
                "No sources found. Install a stream addon on the Addons page.",
                color = FetchPalette.TextMuted
            )
            else -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.widthIn(max = 1200.dp)) {
                val best = sources.best
                if (best != null) {
                    SourceCard(best, isBest = true, onPlay = { onPlay(best) }, menu = menu(best), modifier = Modifier.weight(1.7f))
                }
                SummaryCard(
                    title = "All sources",
                    body = buildString {
                        append("${sources.ranked.size} found")
                        val ruledOut = sources.ranked.count { !it.isEligible }
                        if (ruledOut > 0) append(" · $ruledOut ruled out")
                    },
                    onClick = onAllSources,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        sources?.failedAddons?.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = FetchPalette.Error)
        }
    }
}

@Composable
private fun LoadingLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
        Text(text, color = FetchPalette.TextSecondary)
    }
}

@Composable
private fun SummaryCard(title: String, body: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        modifier = modifier
            .height(150.dp)
            .clip(shape)
            .background(FetchPalette.Surface.copy(alpha = 0.92f))
            .border(if (hovered) 2.dp else 1.dp, if (hovered) Color.White else FetchPalette.SurfaceHigh, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(24.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, color = FetchPalette.TextSecondary, fontSize = 16.sp)
    }
}

/** One source: who sent it, its parsed specs as chips and the release name. */
@Composable
private fun SourceCard(
    ranked: RankedStream,
    isBest: Boolean,
    onPlay: () -> Unit,
    menu: List<ContextMenuItem> = emptyList(),
    modifier: Modifier = Modifier
) {
    val stream = ranked.stream
    val playable = ExternalPlayer.canPlay(stream)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    ContextMenuArea(items = { menu }) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(shape)
            .background(FetchPalette.Surface.copy(alpha = 0.92f))
            .border(if (hovered && playable) 2.dp else 1.dp, if (hovered && playable) Color.White else FetchPalette.SurfaceHigh, shape)
            .hoverable(interaction)
            // A ruled-out source still plays when picked by hand; only unplayable links are disabled.
            .clickable(enabled = playable, interactionSource = interaction, indication = null, onClick = onPlay)
            .then(if (playable) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        if (isBest) Text("BEST MATCH", style = OverlineStyle, color = FetchPalette.Accent)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stream.addonName.ifBlank { "Source" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (hovered && playable) {
                Icon(FetchIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        SpecChips(ranked)
        // Fusion badges from Settings › Stream Selection › Stream badges, as on TV.
        LocalStreamBadges.current?.let { badges ->
            val matched = badges.badgesFor(stream)
            val size = stream.behaviorHints?.videoSize?.takeIf { badges.showFileSizeBadges && matched.isNotEmpty() }
            if (matched.isNotEmpty()) StreamBadgeRow(matched, fileSizeBytes = size)
        }
        val release = stream.behaviorHints?.filename ?: stream.title ?: stream.description ?: stream.name
        release?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = FetchPalette.TextMuted
            )
        }
        val notes = listOfNotNull(
            ranked.exclusion?.let { "Not auto-played: ${it.name.lowercase().replace('_', ' ')}" },
            if (!playable) "Torrent only, not playable here" else null
        )
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(SpecLine.SEPARATOR), style = MaterialTheme.typography.labelSmall, color = FetchPalette.TextMuted)
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpecChips(ranked: RankedStream) {
    val labels = ranked.descriptor.summaryLabels()
    val cached = ranked.descriptor.isDebridCached
    if (labels.isEmpty() && !cached) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { SpecChip(it) }
        if (cached) SpecChip("Cached", filled = true)
    }
}

@Composable
private fun SourcesPanel(
    sources: SourcesState,
    onPlay: (RankedStream) -> Unit,
    onClose: () -> Unit,
    menu: @Composable (RankedStream) -> List<ContextMenuItem>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var addonFilter by remember(sources.videoId) { mutableStateOf<String?>(null) }
    Column(
        modifier
            .background(FetchPalette.Background)
            .border(1.dp, FetchPalette.SurfaceHigh)
            // Clicks inside the panel shouldn't fall through to the scrim and close it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(top = 24.dp, start = 24.dp, end = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("All sources", style = MaterialTheme.typography.headlineSmall)
                Text(sources.label, color = FetchPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CircleIconButton(FetchIcons.Close, "Close", onClick = onClose, size = 40.dp)
        }
        Box(Modifier.height(16.dp))
        when {
            sources.loading -> LoadingLine("Asking your addons…")
            sources.ranked.isEmpty() -> Text(
                "No sources found. Install a stream addon on the Addons page.",
                color = FetchPalette.TextMuted
            )
            else -> {
                val best = sources.best
                if (best != null) {
                    PrimaryButton(
                        text = "Play best match",
                        detail = best.specText(),
                        onClick = { onPlay(best) },
                        modifier = Modifier.padding(end = 12.dp).fillMaxWidth()
                    )
                    Box(Modifier.height(16.dp))
                }
                // Addon chips, as on the TV's Sources screen.
                val addonNames = sources.ranked.map { it.stream.addonName.ifBlank { "Source" } }.distinct()
                if (addonNames.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp, bottom = 12.dp)) {
                        item { SeasonTab("All", selected = addonFilter == null, onClick = { addonFilter = null }) }
                        items(addonNames) { name -> SeasonTab(name, selected = addonFilter == name, onClick = { addonFilter = name }) }
                    }
                }
                val shown = sources.ranked.filter { addonFilter == null || it.stream.addonName.ifBlank { "Source" } == addonFilter }
                ScrollbarBox(listState, Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(end = 14.dp, bottom = 24.dp)
                    ) {
                        sourceGroups(shown, best).forEach { (heading, group) ->
                            item(key = "h:$heading") {
                                Text(heading.uppercase(), style = OverlineStyle, color = FetchPalette.TextMuted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                            }
                            items(group, key = { it.stream.stableKey(it.originalIndex) }) { ranked ->
                                SourceCard(ranked, isBest = ranked === best, onPlay = { onPlay(ranked) }, menu = menu(ranked))
                            }
                        }
                        if (sources.failedAddons.isNotEmpty()) {
                            item(key = "failed") {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    sources.failedAddons.forEach {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = FetchPalette.Error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Episodes(
    videos: List<Video>,
    selectedId: String?,
    onPick: (Video) -> Unit,
    watched: (String) -> Boolean,
    progress: (String) -> Float?,
    menu: (Video) -> List<ContextMenuItem>
) {
    val seasons = videos.mapNotNull { it.season }.distinct().sorted()
    // Season 0 holds specials; start on the season of the selected episode, else the first real one.
    val selectedSeason = videos.firstOrNull { it.id == selectedId }?.season
    var season by remember(videos) {
        mutableStateOf(selectedSeason ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull())
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(horizontal = PageGutter)) {
        Text("Episodes", style = MaterialTheme.typography.titleLarge)
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { s ->
                    SeasonTab(label = if (s == 0) "Specials" else "Season $s", selected = s == season, onClick = { season = s })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            videos
                .filter { season == null || it.season == season }
                .sortedBy { it.episode ?: 0 }
                .forEach { video ->
                    ContextMenuArea(items = { menu(video) }) {
                        EpisodeCard(video, selected = video.id == selectedId, watched = watched(video.id), progress = progress(video.id), onClick = { onPick(video) })
                    }
                }
        }
    }
}

@Composable
private fun SeasonTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        label,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    selected -> Color.White
                    hovered -> Color.White.copy(alpha = 0.2f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    )
}

@Composable
private fun EpisodeCard(video: Video, selected: Boolean, watched: Boolean, progress: Float?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .width(300.dp)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(169.dp)
                .clip(shape)
                .background(FetchPalette.SurfaceRaised)
                .then(
                    when {
                        hovered -> Modifier.border(3.dp, Color.White, shape)
                        selected -> Modifier.border(3.dp, FetchPalette.Accent, shape)
                        else -> Modifier
                    }
                )
        ) {
            if (video.thumbnail != null) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (hovered) {
                Box(
                    Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(FetchIcons.Play, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(FetchIcons.Check, contentDescription = "Watched", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(FetchPalette.Accent))
                }
            }
            if (!com.fetch.desktop.isAired(video)) {
                Text(
                    "Not aired yet",
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            video.rating?.takeIf { it > 0 }?.let {
                Text(
                    "★ %.1f".format(java.util.Locale.ROOT, it),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            video.runtime?.let {
                Text(
                    "${it}m",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            listOfNotNull(video.episode?.let { "E$it" }, video.title.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        video.overview?.takeIf { it.isNotBlank() }?.let {
            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = FetchPalette.TextMuted)
        }
    }
}

@Composable
private fun Cast(cast: List<MetaCastMember>, onOpen: (MetaCastMember) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Cast", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = PageGutter))
        LazyRow(
            contentPadding = PaddingValues(horizontal = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(cast.take(30)) { member ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val clickable = member.tmdbId != null
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.width(110.dp)
                        .then(
                            if (clickable) Modifier.hoverable(interaction)
                                .clickable(interactionSource = interaction, indication = null) { onOpen(member) }
                                .pointerHoverIcon(PointerIcon.Hand)
                            else Modifier
                        )
                ) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(FetchPalette.SurfaceRaised)
                            .then(if (hovered && clickable) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(member.name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = FetchPalette.TextMuted)
                        if (member.photo != null) {
                            AsyncImage(
                                model = member.photo,
                                contentDescription = member.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Text(member.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    member.character?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = FetchPalette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = FetchPalette.TextMuted, modifier = Modifier.padding(horizontal = PageGutter))
}

/**
 * The TV's source sections: Best match first, then by resolution, then what the rules passed
 * over. Empty sections are left out.
 */
internal fun sourceGroups(ranked: List<RankedStream>, best: RankedStream?): List<Pair<String, List<RankedStream>>> {
    val eligible = ranked.filter { it.isEligible && it !== best }
    val passed = ranked.filter { !it.isEligible }
    fun res(r: RankedStream) = r.descriptor.resolution?.label
    val byRes = eligible.groupBy { res(it) ?: "Other" }
    val order = listOf("2160p", "4K", "1440p", "1080p", "720p", "480p")
    val keys = byRes.keys.sortedWith(compareBy { k -> order.indexOfFirst { k.contains(it, ignoreCase = true) }.let { if (it < 0) 99 else it } })
    return buildList {
        if (best != null && best in ranked) add("Best match" to listOf(best))
        keys.forEach { add(it to byRes.getValue(it)) }
        if (passed.isNotEmpty()) add("Not auto-played" to passed)
    }
}

/** A source's right-click menu: where to play it, a rule from it, its link. */
@Composable
private fun sourceMenu(state: AppState, ranked: RankedStream): List<ContextMenuItem> {
    val clipboard = LocalClipboardManager.current
    val stream = ranked.stream
    val rules = state.settings.rankingRules
    return buildList {
        if (ExternalPlayer.canPlay(stream)) {
            add(ContextMenuItem("Play") { state.play(ranked) })
            if (com.fetch.desktop.player.embedded.LibMpv.available) add(ContextMenuItem("Play in the built-in player") { state.play(ranked, builtIn = true) })
            add(ContextMenuItem("Play in mpv's window") { state.play(ranked, builtIn = false) })
        }
        val addon = stream.addonName.trim()
        if (addon.isNotEmpty() && rules.preferredAddons.none { it.equals(addon, ignoreCase = true) }) {
            add(ContextMenuItem("Prefer $addon") {
                state.applyRankingRule({ r -> r.copy(preferredAddons = listOf(addon) + r.preferredAddons.filterNot { it.equals(addon, ignoreCase = true) }) }, "$addon now ranks first")
            })
        }
        val group = state.releaseGroupOf(stream)
        if (group != null && rules.excludedReleaseGroups.none { it.equals(group, ignoreCase = true) }) {
            add(ContextMenuItem("Never auto-play $group") {
                state.applyRankingRule({ r ->
                    r.copy(
                        excludedReleaseGroups = r.excludedReleaseGroups.filterNot { it.equals(group, ignoreCase = true) } + group,
                        preferredReleaseGroups = r.preferredReleaseGroups.filterNot { it.equals(group, ignoreCase = true) }
                    )
                }, "$group won't be auto-played")
            })
        }
        ExternalPlayer.playableUrl(stream)?.let { url -> add(ContextMenuItem("Copy link") { clipboard.setText(AnnotatedString(url)) }) }
    }
}

/** The studios and networks behind a title, as logos that open their pages (TV: company logos). */
@Composable
private fun Companies(companies: List<Pair<MetaCompany, TmdbEntityKind>>, onOpen: (MetaCompany, TmdbEntityKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Production", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = PageGutter))
        LazyRow(contentPadding = PaddingValues(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(companies) { (company, kind) ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val shape = RoundedCornerShape(12.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(72.dp)
                        .widthIn(min = 120.dp, max = 220.dp)
                        .clip(shape)
                        .background(if (company.logo != null) Color.White.copy(alpha = 0.92f) else FetchPalette.SurfaceRaised)
                        .border(if (hovered) 3.dp else 0.dp, if (hovered) FetchPalette.Accent else Color.Transparent, shape)
                        .hoverable(interaction)
                        .clickable(interactionSource = interaction, indication = null) { onOpen(company, kind) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    if (company.logo != null) {
                        AsyncImage(model = company.logo, contentDescription = company.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(company.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
