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
        state.addonsLoading -> CenteredProgress()
        state.rows.isEmpty() -> EmptyState(
            title = "Nothing to show yet",
            body = "Install an addon with catalogs on the Addons page."
        )
        else -> HomeContent(state)
    }
}

@Composable
private fun HomeContent(state: AppState) {
    // The first title with a backdrop leads until the pointer picks another.
    val fallback = state.rows.asSequence().flatMap { it.items }.firstOrNull { it.background != null }
        ?: state.rows.asSequence().flatMap { it.items }.firstOrNull()
    var hovered by remember { mutableStateOf<MetaPreview?>(null) }
    var featured by remember { mutableStateOf<MetaPreview?>(null) }
    // A short pause before the hero follows the pointer, so sweeping across a row doesn't
    // load every backdrop on the way.
    LaunchedEffect(hovered) {
        val next = hovered ?: return@LaunchedEffect
        delay(220)
        featured = next
    }
    val hero = featured ?: fallback
    val listState = state.homeListState

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * 0.56f).coerceIn(340.dp, 600.dp)
        Backdrop(hero?.backdropUrl, height = heroHeight + 120.dp)
        ScrollbarBox(listState, Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(36.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "hero") {
                    if (hero != null) {
                        Hero(
                            item = hero,
                            height = heroHeight,
                            onPlay = { state.navigate(Screen.Detail(hero, autoPlay = hero.type != ContentType.SERIES)) },
                            onDetails = { state.navigate(Screen.Detail(hero)) }
                        )
                    } else {
                        Spacer(Modifier.height(heroHeight))
                    }
                }
                items(state.rows, key = { it.key }) { row ->
                    CatalogRowView(
                        row,
                        onOpen = { state.navigate(Screen.Detail(it)) },
                        onHover = { hovered = it }
                    )
                }
            }
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
private fun Hero(item: MetaPreview, height: Dp, onPlay: () -> Unit, onDetails: () -> Unit) {
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
                    imdbRating = shown.imdbRating
                )
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
