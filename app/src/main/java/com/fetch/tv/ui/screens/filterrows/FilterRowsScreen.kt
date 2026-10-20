package com.fetch.tv.ui.screens.filterrows

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.FilterRowFilters
import com.fetch.tv.domain.model.FilterRowSort
import com.fetch.tv.domain.model.WatchedFilter
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.screens.collection.FetchButton
import com.fetch.tv.ui.screens.collection.FetchTextField
import com.fetch.tv.ui.theme.FetchTheme
import java.time.Year

/** Lists the filter rows of this profile. Every control is a button, so D-pad, OK and Back cover it. */
@Composable
fun FilterRowsScreen(
    onNavigateToEditor: (rowId: String?) -> Unit,
    onBack: () -> Unit,
    viewModel: FilterRowsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val newRowFocus = remember { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) runCatching { newRowFocus.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FetchTheme.spacing.xxxl, vertical = FetchTheme.spacing.xl),
        contentPadding = PaddingValues(bottom = FetchTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = stringResource(R.string.filter_rows_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = FetchTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                Text(
                    text = stringResource(R.string.filter_rows_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))
                FetchButton(
                    onClick = { onNavigateToEditor(null) },
                    modifier = Modifier.focusRequester(newRowFocus)
                ) {
                    Text(stringResource(R.string.filter_rows_new))
                }
            }
        }
        when {
            uiState.isLoading -> item(key = "loading") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
            uiState.rows.isEmpty() -> item(key = "empty") {
                Text(
                    text = stringResource(R.string.filter_rows_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = FetchTheme.colors.TextSecondary
                )
            }
            else -> items(uiState.rows, key = { it.row.id }) { item ->
                FilterRowCard(
                    item = item,
                    onEdit = { onNavigateToEditor(item.row.id) },
                    onDelete = { viewModel.delete(item.row.id) }
                )
            }
        }
    }
}

@Composable
private fun FilterRowCard(item: FilterRowListItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.row.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = FetchTheme.colors.TextPrimary
            )
            Text(
                text = item.sourceLabel ?: stringResource(R.string.filter_row_source_missing),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.sourceLabel == null) FetchTheme.colors.Error else FetchTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        FetchButton(onClick = onEdit) { Text(stringResource(R.string.filter_rows_edit)) }
        FetchButton(onClick = onDelete) { Text(stringResource(R.string.filter_rows_delete)) }
    }
}

private enum class FilterField { SOURCE, GENRE, YEAR_FROM, YEAR_TO, WATCHED, RATING, SORT, ORDER }

private data class Choice(val label: String, val onPick: () -> Unit, val selected: Boolean)

@Composable
fun FilterRowEditorScreen(
    onBack: () -> Unit,
    viewModel: FilterRowEditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var openField by remember { mutableStateOf<FilterField?>(null) }
    val firstFieldFocus = remember { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) runCatching { firstFieldFocus.requestFocus() }
    }

    val filters = uiState.filters
    val source = uiState.selectedSource
    val isServer = source?.isServer == true
    val anyLabel = stringResource(R.string.filter_row_any)
    val defaultTitle = listOfNotNull(filters.genre, source?.label).joinToString(" · ")
        .ifEmpty { stringResource(R.string.filter_rows_title) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FetchTheme.spacing.xxxl, vertical = FetchTheme.spacing.xl),
        contentPadding = PaddingValues(bottom = FetchTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        item(key = "header") {
            Text(
                text = stringResource(if (uiState.isNew) R.string.filter_rows_new else R.string.filter_rows_edit_title),
                style = MaterialTheme.typography.headlineLarge,
                color = FetchTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))
        }
        if (uiState.isLoading) {
            item(key = "loading") { LoadingIndicator() }
            return@LazyColumn
        }
        if (uiState.sources.isEmpty()) {
            item(key = "no_sources") {
                Text(
                    text = stringResource(R.string.filter_rows_no_sources),
                    style = MaterialTheme.typography.bodyLarge,
                    color = FetchTheme.colors.TextSecondary
                )
            }
            return@LazyColumn
        }
        item(key = "title") {
            FetchTextField(
                value = uiState.title,
                onValueChange = viewModel::setTitle,
                modifier = Modifier.fillMaxWidth(),
                placeholder = defaultTitle,
                focusRequester = firstFieldFocus
            )
        }
        item(key = "source") {
            FieldButton(stringResource(R.string.filter_row_source), source?.label ?: anyLabel) {
                openField = FilterField.SOURCE
            }
        }
        item(key = "genre") {
            FieldButton(stringResource(R.string.filter_row_genre), filters.genre ?: anyLabel) {
                openField = FilterField.GENRE
            }
        }
        if (isServer) {
            item(key = "year_from") {
                FieldButton(stringResource(R.string.filter_row_year_from), filters.yearFrom?.toString() ?: anyLabel) {
                    openField = FilterField.YEAR_FROM
                }
            }
            item(key = "year_to") {
                FieldButton(stringResource(R.string.filter_row_year_to), filters.yearTo?.toString() ?: anyLabel) {
                    openField = FilterField.YEAR_TO
                }
            }
            item(key = "watched") {
                FieldButton(stringResource(R.string.filter_row_watched), watchedLabel(filters.watched)) {
                    openField = FilterField.WATCHED
                }
            }
            item(key = "rating") {
                FieldButton(stringResource(R.string.filter_row_rating), filters.minRating?.let { ratingLabel(it) } ?: anyLabel) {
                    openField = FilterField.RATING
                }
            }
            item(key = "sort") {
                FieldButton(stringResource(R.string.filter_row_sort), sortLabel(filters.sort)) {
                    openField = FilterField.SORT
                }
            }
            item(key = "order") {
                FieldButton(stringResource(R.string.filter_row_order), orderLabel(filters.descending)) {
                    openField = FilterField.ORDER
                }
            }
        } else {
            item(key = "addon_note") {
                Text(
                    text = stringResource(R.string.filter_row_addon_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.TextSecondary
                )
            }
        }
        item(key = "actions") {
            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
                FetchButton(onClick = { viewModel.save(defaultTitle, onBack) }, enabled = uiState.canSave) {
                    Text(stringResource(R.string.filter_rows_save))
                }
                if (!uiState.isNew) {
                    FetchButton(onClick = { viewModel.delete(onBack) }) {
                        Text(stringResource(R.string.filter_rows_delete))
                    }
                }
            }
        }
    }

    val field = openField ?: return
    val close = { openField = null }
    val title: String
    val choices: List<Choice>
    when (field) {
        FilterField.SOURCE -> {
            title = stringResource(R.string.filter_row_source)
            choices = uiState.sources.map { Choice(it.label, { viewModel.selectSource(it.key) }, it.key == uiState.selectedSourceKey) }
        }
        FilterField.GENRE -> {
            title = stringResource(R.string.filter_row_genre)
            choices = listOf(Choice(anyLabel, { viewModel.setGenre(null) }, filters.genre == null)) +
                uiState.genres.map { Choice(it, { viewModel.setGenre(it) }, it == filters.genre) }
        }
        FilterField.YEAR_FROM, FilterField.YEAR_TO -> {
            val isFrom = field == FilterField.YEAR_FROM
            val current = if (isFrom) filters.yearFrom else filters.yearTo
            val pick: (Int?) -> Unit = if (isFrom) viewModel::setYearFrom else viewModel::setYearTo
            title = stringResource(if (isFrom) R.string.filter_row_year_from else R.string.filter_row_year_to)
            choices = listOf(Choice(anyLabel, { pick(null) }, current == null)) +
                yearOptions(filters, isFrom).map { year -> Choice(year.toString(), { pick(year) }, year == current) }
        }
        FilterField.WATCHED -> {
            title = stringResource(R.string.filter_row_watched)
            choices = WatchedFilter.entries.map { Choice(watchedLabel(it), { viewModel.setWatched(it) }, it == filters.watched) }
        }
        FilterField.RATING -> {
            title = stringResource(R.string.filter_row_rating)
            choices = listOf(Choice(anyLabel, { viewModel.setMinRating(null) }, filters.minRating == null)) +
                RATING_OPTIONS.map { rating -> Choice(ratingLabel(rating), { viewModel.setMinRating(rating) }, rating == filters.minRating) }
        }
        FilterField.SORT -> {
            title = stringResource(R.string.filter_row_sort)
            choices = FilterRowSort.entries.map { Choice(sortLabel(it), { viewModel.setSort(it) }, it == filters.sort) }
        }
        FilterField.ORDER -> {
            title = stringResource(R.string.filter_row_order)
            choices = listOf(true, false).map { Choice(orderLabel(it), { viewModel.setDescending(it) }, it == filters.descending) }
        }
    }
    ChoiceDialog(
        title = title,
        loading = field == FilterField.GENRE && uiState.genresLoading,
        choices = choices,
        onDismiss = close
    )
}

@Composable
private fun FieldButton(label: String, value: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            contentColor = FetchTheme.colors.TextPrimary,
            focusedContainerColor = FetchTheme.colors.FocusBackground,
            focusedContentColor = FetchTheme.colors.TextPrimary
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(FetchTheme.radii.md)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChoiceDialog(title: String, loading: Boolean, choices: List<Choice>, onDismiss: () -> Unit) {
    val selectedFocus = remember { FocusRequester() }
    val selectedIndex = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(loading) {
        if (!loading) runCatching { selectedFocus.requestFocus() }
    }
    FetchDialog(onDismiss = onDismiss, title = title) {
        if (loading) {
            LoadingIndicator()
            return@FetchDialog
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
        ) {
            items(choices.size) { index ->
                val choice = choices[index]
                Button(
                    onClick = {
                        choice.onPick()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier),
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = if (choice.selected) FetchTheme.colors.Primary else FetchTheme.colors.TextPrimary
                    )
                ) {
                    Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun watchedLabel(watched: WatchedFilter): String = stringResource(
    when (watched) {
        WatchedFilter.ANY -> R.string.filter_row_any
        WatchedFilter.UNWATCHED -> R.string.filter_row_unwatched
        WatchedFilter.WATCHED -> R.string.filter_row_watched_only
    }
)

@Composable
private fun sortLabel(sort: FilterRowSort): String = stringResource(
    when (sort) {
        FilterRowSort.DATE_ADDED -> R.string.filter_row_sort_added
        FilterRowSort.TITLE -> R.string.filter_row_sort_title
        FilterRowSort.RELEASE_DATE -> R.string.filter_row_sort_release
        FilterRowSort.RATING -> R.string.filter_row_sort_rating
        FilterRowSort.RANDOM -> R.string.filter_row_sort_random
    }
)

@Composable
private fun orderLabel(descending: Boolean): String =
    stringResource(if (descending) R.string.filter_row_descending else R.string.filter_row_ascending)

@Composable
private fun ratingLabel(rating: Double): String = stringResource(R.string.filter_row_rating_value, rating)

/** Years newest first, kept on the right side of the other bound. */
private fun yearOptions(filters: FilterRowFilters, isFrom: Boolean): List<Int> {
    val newest = Year.now().value + 1
    val years = (newest downTo FIRST_YEAR).toList()
    return if (isFrom) {
        years.filter { year -> filters.yearTo?.let { year <= it } ?: true }
    } else {
        years.filter { year -> filters.yearFrom?.let { year >= it } ?: true }
    }
}

private const val FIRST_YEAR = 1920
private val RATING_OPTIONS = listOf(5.0, 6.0, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0)
