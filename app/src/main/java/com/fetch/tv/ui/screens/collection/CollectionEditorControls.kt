package com.fetch.tv.ui.screens.collection

import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.FetchStatusTones

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.CollectionSource
import com.fetch.tv.domain.model.FetchListCollectionSource
import com.fetch.tv.domain.model.LocalListCollectionSource
import com.fetch.tv.domain.model.FolderViewMode
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.TmdbCollectionFilters
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import com.fetch.tv.domain.model.TmdbCollectionSort
import com.fetch.tv.domain.model.TmdbCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionSourceType
import com.fetch.tv.domain.model.TraktCollectionSource
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FetchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null,
    isError: Boolean = false
) {
    var isEditing by remember { mutableStateOf(false) }
    val textFieldFocusRequester = remember { FocusRequester() }
    val surfaceFocusRequester = focusRequester ?: remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isEditing) {
        if (isEditing) {
            repeat(3) { androidx.compose.runtime.withFrameNanos { } }
            try { textFieldFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Surface(
        onClick = { isEditing = true },
        modifier = modifier.focusRequester(surfaceFocusRequester),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundElevated,
            focusedContainerColor = FetchTheme.colors.BackgroundElevated
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    FetchTheme.spacing.hairline,
                    if (isError) FetchStatusTones.errorBorder else FetchTheme.colors.Border
                ),
                shape = RoundedCornerShape(FetchTheme.radii.md)
            ),
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(FetchTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.padding(FetchTheme.spacing.md)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(textFieldFocusRequester)
                    .onFocusChanged {
                        if (!it.isFocused && isEditing) {
                            isEditing = false
                            keyboardController?.hide()
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        isEditing = false
                        keyboardController?.hide()
                        surfaceFocusRequester.requestFocus()
                    }
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = FetchTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content
                ),
                cursorBrush = SolidColor(if (isEditing) FetchTheme.colors.Primary else Color.Transparent),
                decorationBox = { innerTextField ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FetchTheme.colors.TextTertiary
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}

/** Asks before Back throws away unsaved edits. "Keep editing" takes focus, so a stray press is safe. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscardChangesDialog(
    subtitle: String,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit
) {
    val keepEditingFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        try { keepEditingFocusRequester.requestFocus() } catch (_: Exception) {}
    }
    FetchDialog(
        onDismiss = onKeepEditing,
        title = stringResource(R.string.collections_editor_discard_title),
        subtitle = subtitle,
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md, Alignment.End)
        ) {
            FetchButton(
                onClick = onKeepEditing,
                modifier = Modifier.focusRequester(keepEditingFocusRequester)
            ) {
                Text(stringResource(R.string.collections_editor_keep_editing))
            }
            FetchButton(onClick = onDiscard, destructive = true) {
                Text(stringResource(R.string.collections_editor_discard))
            }
        }
    }
}

/**
 * The one button used across the collection screens. The focused label stays as bright as the
 * resting one; focus is shown by the ring. [destructive] gives a confirm-delete button the error
 * fill, so it can't be mistaken for Cancel.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FetchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.then(if (!enabled) Modifier.alpha(0.35f) else Modifier),
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.colors(
                containerColor = FetchStatusTones.errorContainer,
                contentColor = FetchTheme.colors.TextPrimary,
                focusedContainerColor = FetchTheme.colors.Error,
                focusedContentColor = FetchTheme.colors.OnHighlight
            )
        } else {
            ButtonDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                contentColor = FetchTheme.colors.TextPrimary,
                focusedContainerColor = FetchTheme.colors.FocusBackground,
                focusedContentColor = FetchTheme.colors.TextPrimary
            )
        },
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(FetchTheme.radii.md)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md)),
        content = { content() }
    )
}

/**
 * One option in a row of choices (view mode, cover, tile shape, TMDB/Trakt options). The selected
 * option carries a check mark, which stays while it has focus, so landing on an option still shows
 * whether it is the current one.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FetchChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(FetchTheme.radii.md)
    Button(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) FetchTheme.colors.Secondary.copy(alpha = 0.3f) else FetchTheme.colors.BackgroundCard,
            contentColor = if (selected) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextSecondary,
            focusedContainerColor = FetchTheme.colors.FocusBackground,
            focusedContentColor = FetchTheme.colors.TextPrimary
        ),
        border = ButtonDefaults.border(
            border = if (selected) {
                Border(border = BorderStroke(FetchTheme.spacing.xxs, FetchTheme.colors.Secondary), shape = shape)
            } else {
                Border.None
            },
            focusedBorder = Border(border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs), shape = shape)
        ),
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(FetchTheme.spacing.xs))
        }
        content()
    }
}

/** A short helper line that says what is missing, read out when it appears. */
@Composable
internal fun FieldRequirementText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FetchStatusTones.errorText,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** An addon catalog's content type in the app language ("movie" → "Movie"); unknown types pass through. */
@Composable
internal fun addonCatalogTypeLabel(type: String): String = when (type.lowercase()) {
    "movie" -> stringResource(R.string.type_movie)
    "series", "tv" -> stringResource(R.string.type_series)
    else -> type
}

fun collectionSourceKey(source: CollectionSource): String {
    return when (source) {
        is AddonCatalogCollectionSource -> "addon_${source.addonId}_${source.type}_${source.catalogId}_${source.genre.orEmpty()}"
        is TmdbCollectionSource -> "tmdb_${source.sourceType}_${source.tmdbId}_${source.mediaType}_${source.sortBy}_${source.filters.hashCode()}"
        is TraktCollectionSource -> "trakt_${source.traktListId}_${source.mediaType}_${source.sortBy}_${source.sortHow}"
        is FetchListCollectionSource -> "fetch_${source.listId}_${source.mediaType}"
        is LocalListCollectionSource -> "local_${source.listId}_${source.mediaType}"
    }
}

@Composable
fun tmdbSourceSubtitle(source: TmdbCollectionSource): String {
    val media = when (source.mediaType) {
        TmdbCollectionMediaType.MOVIE -> stringResource(R.string.type_movies)
        TmdbCollectionMediaType.TV -> stringResource(R.string.type_series_plural)
    }
    val sort = when (source.sortBy) {
        TmdbCollectionSort.ORIGINAL.value -> stringResource(R.string.collections_editor_sort_original)
        TmdbCollectionSort.POPULAR_DESC.value -> stringResource(R.string.tmdb_entity_rail_popular)
        TmdbCollectionSort.VOTE_AVERAGE_DESC.value -> stringResource(R.string.tmdb_entity_rail_top_rated)
        TmdbCollectionSort.VOTE_COUNT_DESC.value -> stringResource(R.string.tmdb_entity_rail_most_voted)
        TmdbCollectionSort.RELEASE_DATE_DESC.value,
        TmdbCollectionSort.FIRST_AIR_DATE_DESC.value -> stringResource(R.string.tmdb_entity_rail_recent)
        else -> source.sortBy
    }
    return when (source.sourceType) {
        TmdbCollectionSourceType.LIST -> stringResource(R.string.collections_editor_tmdb_default_list)
        TmdbCollectionSourceType.COLLECTION -> stringResource(R.string.collections_editor_tmdb_movie_collection)
        TmdbCollectionSourceType.COMPANY -> listOf(stringResource(R.string.collections_editor_tmdb_mode_production), media, sort).joinToString(" • ")
        TmdbCollectionSourceType.NETWORK -> listOf(stringResource(R.string.collections_editor_tmdb_mode_network), stringResource(R.string.type_series_plural), sort).joinToString(" • ")
        TmdbCollectionSourceType.PERSON -> listOf(stringResource(R.string.collections_editor_tmdb_person_credits), media, sort).joinToString(" • ")
        TmdbCollectionSourceType.DIRECTOR -> listOf(stringResource(R.string.collections_editor_tmdb_director_credits), media, sort).joinToString(" • ")
        TmdbCollectionSourceType.DISCOVER -> listOf(stringResource(R.string.collections_editor_tmdb_default_discover), media, sort).joinToString(" • ")
    }
}

@Composable
fun traktSourceSubtitle(source: TraktCollectionSource): String {
    val media = when (source.mediaType) {
        TmdbCollectionMediaType.MOVIE -> stringResource(R.string.type_movies)
        TmdbCollectionMediaType.TV -> stringResource(R.string.type_series_plural)
    }
    val sort = when (source.sortBy) {
        "rank" -> stringResource(R.string.collections_editor_sort_list_order)
        "added" -> stringResource(R.string.collections_editor_sort_recently_added)
        "title" -> stringResource(R.string.collections_editor_sort_title)
        "released" -> stringResource(R.string.collections_editor_sort_released)
        "popularity" -> stringResource(R.string.tmdb_entity_rail_popular)
        "votes" -> stringResource(R.string.collections_editor_sort_votes)
        else -> source.sortBy
    }
    val direction = if (source.sortHow == "desc") {
        stringResource(R.string.collections_editor_direction_desc_short)
    } else {
        stringResource(R.string.collections_editor_direction_asc_short)
    }
    return listOf(stringResource(R.string.collections_editor_trakt_list), media, "$sort $direction").joinToString(" • ")
}
