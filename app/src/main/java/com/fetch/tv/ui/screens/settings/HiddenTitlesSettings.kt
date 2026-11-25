@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import com.fetch.tv.core.util.runSuspendCatching
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.local.HiddenTitle
import com.fetch.tv.data.local.HiddenTitlesState
import com.fetch.tv.data.local.HiddenTitlesStore
import com.fetch.tv.data.local.isSeriesKind
import com.fetch.tv.ui.theme.FetchTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "Hide watched" and the "Not interested" list with its restore action (#650). */
@HiltViewModel
class HiddenTitlesSettingsViewModel @Inject constructor(
    private val store: HiddenTitlesStore
) : ViewModel() {
    val state: StateFlow<HiddenTitlesState> = store.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HiddenTitlesState.Empty)

    fun onHideWatchedToggle() {
        val next = !state.value.hideWatched
        viewModelScope.launch { runSuspendCatching { store.setHideWatched(next) } }
    }

    fun onRestore(title: HiddenTitle) {
        viewModelScope.launch { runSuspendCatching { store.restore(title) } }
    }
}

/** The group on the Content & Discovery page: the toggle, and the way to the hidden list. */
@Composable
internal fun HiddenTitlesSettingsGroup(
    onOpenHiddenTitles: () -> Unit,
    hiddenTitlesFocusRequester: FocusRequester,
    viewModel: HiddenTitlesSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.hidden_titles_group_title)
    ) {
        SettingsToggleRow(
            title = stringResource(R.string.hidden_titles_hide_watched_title),
            subtitle = stringResource(R.string.hidden_titles_hide_watched_subtitle),
            checked = state.hideWatched,
            onToggle = viewModel::onHideWatchedToggle
        )
        SettingsActionRow(
            title = stringResource(R.string.hidden_titles_not_interested_title),
            subtitle = stringResource(R.string.hidden_titles_not_interested_subtitle),
            value = if (state.titles.isEmpty()) {
                stringResource(R.string.hidden_titles_none)
            } else {
                pluralStringResource(R.plurals.hidden_titles_count, state.titles.size, state.titles.size)
            },
            onClick = onOpenHiddenTitles,
            leadingIcon = Icons.Default.VisibilityOff,
            modifier = Modifier.focusRequester(hiddenTitlesFocusRequester)
        )
    }
}

/** Every "Not interested" title, newest first; selecting one restores it. */
@Composable
internal fun HiddenTitlesSettingsContent(
    initialFocusRequester: FocusRequester,
    viewModel: HiddenTitlesSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val titles = state.titles
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String) = requesters.getOrPut(key) { FocusRequester() }
    // After a restore, focus moves to the title that took its place (or the one above).
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(titles) {
        val index = pendingFocusIndex ?: return@LaunchedEffect
        pendingFocusIndex = null
        val next = titles.getOrNull(index.coerceAtMost(titles.lastIndex))
        runCatching {
            if (next != null) requesterFor(next.storageKey).requestFocus() else initialFocusRequester.requestFocus()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
        SettingsDetailHeader(
            title = stringResource(R.string.hidden_titles_not_interested_title),
            subtitle = stringResource(R.string.hidden_titles_page_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            SettingsRowTargetScroller(listState)
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = FetchTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
                ) {
                    if (titles.isEmpty()) {
                        item(key = "hidden_titles_empty") {
                            // Focusable, so the page has somewhere to land with nothing hidden.
                            SettingsActionRow(
                                title = stringResource(R.string.hidden_titles_empty_title),
                                subtitle = stringResource(R.string.hidden_titles_empty_subtitle),
                                onClick = {},
                                modifier = Modifier.focusRequester(initialFocusRequester)
                            )
                        }
                    }
                    itemsIndexed(titles, key = { _, title -> title.storageKey }) { index, title ->
                        val typeLabel = if (isSeriesKind(title.type)) {
                            stringResource(R.string.type_series)
                        } else if (title.type.equals("movie", ignoreCase = true)) {
                            stringResource(R.string.type_movie)
                        } else {
                            null
                        }
                        SettingsActionRow(
                            title = title.name,
                            subtitle = listOfNotNull(typeLabel, stringResource(R.string.hidden_titles_restore_hint))
                                .joinToString(" · "),
                            onClick = {
                                pendingFocusIndex = index
                                viewModel.onRestore(title)
                            },
                            trailingIcon = Icons.Default.Restore,
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier)
                                .focusRequester(requesterFor(title.storageKey))
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
        Text(
            text = stringResource(R.string.hidden_titles_sync_note),
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = FetchTheme.spacing.sm)
        )
    }
}
