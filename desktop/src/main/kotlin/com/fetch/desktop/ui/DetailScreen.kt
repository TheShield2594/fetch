package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.SourcesState
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.tv.core.streams.SpecLine
import com.fetch.tv.core.streams.ranking.RankedStream
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Video

@Composable
fun DetailScreen(state: AppState, preview: MetaPreview) {
    val meta = state.detailMeta
    val isSeries = preview.type == ContentType.SERIES ||
        (meta?.videos?.any { it.season != null } == true && preview.type != ContentType.MOVIE)

    // A movie has one set of sources, so look them up straight away.
    LaunchedEffect(preview.id, state.detailLoading) {
        if (!state.detailLoading && !isSeries && state.sources == null) state.loadSources(preview)
    }

    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.weight(1f).fillMaxHeight()
        ) {
            item { Hero(preview, meta, onBack = state::back, canGoBack = state.canGoBack) }
            if (state.detailLoading) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator() } }
            } else if (isSeries) {
                val videos = meta?.videos.orEmpty()
                if (videos.isEmpty()) {
                    item { Note("No episode list from your addons.") }
                } else {
                    item { Episodes(videos, selectedId = state.sources?.videoId, onPick = { state.loadSources(preview, it) }) }
                }
            }
        }
        val sources = state.sources
        if (sources != null) {
            Sources(
                sources = sources,
                onPlay = { state.play(it, sources.label) },
                modifier = Modifier.width(460.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    }
}

@Composable
private fun Hero(preview: MetaPreview, meta: Meta?, onBack: () -> Unit, canGoBack: Boolean) {
    val background = meta?.background ?: preview.background
    Box(Modifier.fillMaxWidth().height(380.dp)) {
        if (background != null) {
            AsyncImage(
                model = background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x660D0D0D), MaterialTheme.colorScheme.background))
            )
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 32.dp)
        ) {
            if (canGoBack) TextButton(onClick = onBack) { Text("← Back") }
            Text(meta?.name ?: preview.name, style = MaterialTheme.typography.headlineLarge)
            val facts = listOfNotNull(
                meta?.releaseInfo ?: preview.releaseInfo,
                meta?.runtime ?: preview.runtime,
                (meta?.imdbRating ?: preview.imdbRating)?.let { "IMDb $it" },
                (meta?.genres ?: preview.genres).take(3).joinToString(", ").takeIf { it.isNotBlank() }
            )
            if (facts.isNotEmpty()) {
                Text(facts.joinToString(SpecLine.SEPARATOR), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            (meta?.description ?: preview.description)?.let {
                Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.8f))
            }
        }
    }
}

@Composable
private fun Episodes(videos: List<Video>, selectedId: String?, onPick: (Video) -> Unit) {
    val seasons = videos.mapNotNull { it.season }.distinct().sorted()
    // Season 0 holds specials; start on the first real season.
    var season by remember(videos) { mutableStateOf(seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 32.dp)) {
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { s ->
                    FilterChip(
                        selected = s == season,
                        onClick = { season = s },
                        label = { Text(if (s == 0) "Specials" else "Season $s") }
                    )
                }
            }
        }
        videos
            .filter { season == null || it.season == season }
            .sortedBy { it.episode ?: 0 }
            .forEach { video ->
                val selected = video.id == selectedId
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { onPick(video) }
                        .padding(8.dp)
                ) {
                    Box(
                        Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (video.thumbnail != null) {
                            AsyncImage(
                                model = video.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            listOfNotNull(video.episode?.let { "E$it" }, video.title).joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall
                        )
                        video.overview?.let {
                            Text(
                                it,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun Sources(sources: SourcesState, onPlay: (RankedStream) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sources", style = MaterialTheme.typography.titleLarge)
        Text(sources.label, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            sources.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text("Asking your addons…")
            }
            sources.ranked.isEmpty() -> Note("No sources found. Install a stream addon on the Addons page.")
            else -> {
                val best = sources.ranked.firstOrNull { it.isEligible }
                if (best != null) {
                    Button(onClick = { onPlay(best) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Play best match", maxLines = 1)
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(sources.ranked, key = { it.stream.stableKey(it.originalIndex) }) { ranked ->
                        SourceCard(ranked, isBest = ranked === best, onPlay = { onPlay(ranked) })
                    }
                }
            }
        }
        sources.failedAddons.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SourceCard(ranked: RankedStream, isBest: Boolean, onPlay: () -> Unit) {
    val stream = ranked.stream
    val spec = SpecLine.of(
        labels = ranked.descriptor.summaryLabels(),
        isCached = ranked.descriptor.isDebridCached,
        cachedLabel = "Cached"
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isBest) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        // A ruled-out source still plays when picked by hand; only unplayable links are disabled.
        modifier = Modifier.fillMaxWidth().clickable(enabled = ExternalPlayer.canPlay(stream), onClick = onPlay)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            spec?.let { Text(it.text, style = MaterialTheme.typography.labelLarge) }
            val release = stream.behaviorHints?.filename ?: stream.title ?: stream.description ?: stream.name
            release?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val footer = listOfNotNull(
                stream.addonName,
                ranked.exclusion?.let { "Not auto-played: ${it.name.lowercase().replace('_', ' ')}" }
            )
            Text(footer.joinToString(SpecLine.SEPARATOR), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
}
