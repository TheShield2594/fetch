package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.tmdb.TmdbEntityKind
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A cast or crew member's page, as on TV: photo, facts, biography and what they're in. */
@Composable
fun PersonScreen(state: AppState, screen: Screen.Person) {
    val tmdb = state.tmdb
    val person = tmdb.person?.takeIf { it.id == screen.tmdbId }
    val grid = rememberLazyGridState()
    val open: (MetaPreview) -> Unit = { state.navigate(Screen.Detail(it)) }
    if (person == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    tmdb.personLoading -> CircularProgressIndicator(color = FetchPalette.Accent)
                    !tmdb.client.isConfigured -> Text(tr("Add a TMDB key in Settings › TMDB to see people."), color = FetchPalette.TextSecondary)
                    else -> Text(tmdb.personError ?: screen.name, color = FetchPalette.TextSecondary)
                }
                if (state.canGoBack) SecondaryButton("Back", onClick = state::back, icon = FetchIcons.Back)
            }
        }
        return
    }
    PosterGrid(
        items = person.credits.filter(state::allowed),
        state = grid,
        onOpen = open,
        showLabels = state.settings.showPosterLabels,
        modifier = Modifier.fillMaxSize(),
        header = {
            fullWidthItem("person-header") {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    if (state.canGoBack) CircleIconButton(FetchIcons.Back, "Back", onClick = state::back)
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Box(Modifier.width(200.dp).height(300.dp).clip(RoundedCornerShape(14.dp)).background(FetchPalette.SurfaceRaised)) {
                            ArtPlaceholder(person.name)
                            person.photo?.let {
                                AsyncImage(model = it, contentDescription = person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 820.dp)) {
                            Text(person.name, style = MaterialTheme.typography.headlineLarge, color = Color.White)
                            val facts = listOfNotNull(
                                person.knownFor?.let { knownForLabel(it) },
                                person.birthday?.let { born ->
                                    val age = person.deathday == null
                                    "Born ${prettyDate(born)}" + (if (age) ageLabel(born)?.let { " ($it)" }.orEmpty() else "")
                                },
                                person.deathday?.let { "Died ${prettyDate(it)}" },
                                person.birthplace
                            )
                            if (facts.isNotEmpty()) FactsLine(facts = facts, imdbRating = null)
                            person.biography?.let { bio -> ExpandableText(bio) }
                        }
                    }
                    if (person.credits.isNotEmpty()) Text(tr("Known for"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    )
}

/** A studio's or network's titles from TMDB, as the TV's company and network pages show them. */
@Composable
fun EntityScreen(state: AppState, screen: Screen.Entity) {
    val tmdb = state.tmdb
    val browse = tmdb.entity?.takeIf { it.id == screen.tmdbId && it.kind == screen.kind }
    val grid = rememberLazyGridState()
    LaunchedEffect(grid, browse?.series) {
        snapshotFlow {
            val info = grid.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) tmdb.entity?.takeIf { it.canLoadMore }?.let { tmdb.loadMoreEntity() } }
    }
    PosterGrid(
        items = browse?.items.orEmpty().filter(state::allowed),
        state = grid,
        onOpen = { state.navigate(Screen.Detail(it)) },
        showLabels = state.settings.showPosterLabels,
        modifier = Modifier.fillMaxSize(),
        header = {
            fullWidthItem("entity-header") {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    if (state.canGoBack) CircleIconButton(FetchIcons.Back, "Back", onClick = state::back)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        browse?.logo?.let { logo ->
                            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.92f)).padding(12.dp)) {
                                AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(48.dp).widthIn(max = 200.dp))
                            }
                        }
                        Column {
                            Text(browse?.name ?: screen.name, style = MaterialTheme.typography.headlineLarge, color = Color.White)
                            Text(screen.kind.label, color = FetchPalette.TextSecondary)
                        }
                    }
                    // Studios make both; networks only air shows.
                    if (screen.kind == TmdbEntityKind.COMPANY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(false to "Movies", true to "Series").forEach { (series, label) ->
                                if (browse?.series == series) PrimaryButton(label, onClick = {}, icon = null)
                                else SecondaryButton(label, onClick = { tmdb.openEntity(screen.kind, screen.tmdbId, screen.name, series) })
                            }
                        }
                    }
                }
            }
        },
        footer = {
            fullWidthItem("entity-footer") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        browse == null || browse.loading -> CircularProgressIndicator(Modifier.size(28.dp), color = FetchPalette.Accent)
                        browse.error != null -> Text(browse.error, color = FetchPalette.TextMuted)
                        browse.items.isEmpty() -> Text("Nothing from ${browse.name} on TMDB.", color = FetchPalette.TextMuted)
                    }
                }
            }
        }
    )
}

/** A long text shown in a few lines, with More to read the rest. */
@Composable
fun ExpandableText(text: String, collapsedLines: Int = 6) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            lineHeight = 26.sp,
            color = FetchPalette.TextPrimary.copy(alpha = 0.92f),
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis
        )
        if (text.length > 400) {
            SecondaryButton(if (expanded) "Less" else "More", onClick = { expanded = !expanded })
        }
    }
}

private fun knownForLabel(department: String) = when (department) {
    "Acting" -> "Actor"
    "Directing" -> "Director"
    "Writing" -> "Writer"
    "Production" -> "Producer"
    else -> department
}

private fun prettyDate(iso: String): String =
    runCatching { LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) }.getOrDefault(iso)

private fun ageLabel(birthday: String): String? = runCatching {
    val born = LocalDate.parse(birthday.take(10))
    "age ${java.time.Period.between(born, LocalDate.now()).years}"
}.getOrNull()
