package com.fetch.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.screens.account.InputField
import com.fetch.tv.ui.theme.FetchTheme
import java.text.Normalizer
import java.util.Locale

/** At most this many results are listed; a query that matches more is too short to help. */
private const val MAX_RESULTS = 60

/** One setting that can be searched for: its title, which is also its row's key, and the page that has it. */
internal data class SettingsSearchEntry(val category: SettingsCategory, val title: String)

/**
 * The entries to list: those matching [query], or with a blank query every entry when
 * [changedOnly] is on. [changedOnly] keeps only the [changed] ones, in page order.
 */
internal fun settingsSearchResults(
    query: String,
    entries: List<SettingsSearchEntry>,
    changedOnly: Boolean,
    changed: Set<SettingsSearchEntry>
): List<SettingsSearchEntry> = when {
    !changedOnly -> searchSettings(query, entries)
    query.isBlank() -> entries.distinct().filter { it in changed }
    else -> searchSettings(query, entries.filter { it in changed })
}

/**
 * The entries in [entries] whose titles hold every word of [query], ignoring case and accents.
 * Titles that start with the query come first, then those with a word that does, then the rest,
 * each in page order. The same title on the same page is listed once.
 */
internal fun searchSettings(query: String, entries: List<SettingsSearchEntry>): List<SettingsSearchEntry> {
    val needle = foldForSearch(query)
    val words = needle.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return entries
        .asSequence()
        .distinct()
        .mapNotNull { entry ->
            val title = foldForSearch(entry.title)
            if (words.any { it !in title }) return@mapNotNull null
            val rank = when {
                title.startsWith(needle) -> 0
                title.split(' ').any { titleWord -> words.any { titleWord.startsWith(it) } } -> 1
                else -> 2
            }
            rank to entry
        }
        .sortedBy { it.first }
        .map { it.second }
        .take(MAX_RESULTS)
        .toList()
}

private fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

/**
 * Settings search: type part of a setting's name, then pick a result to open the page that has
 * it, focused on that setting. Only pages the rail shows are searched.
 */
@Composable
internal fun SettingsSearchContent(
    visibleCategories: List<SettingsCategory>,
    index: Map<SettingsCategory, List<Int>>,
    query: String,
    onQueryChange: (String) -> Unit,
    changedOnly: Boolean,
    onChangedOnlyChange: (Boolean) -> Unit,
    changedSettings: Set<SettingsSearchKey>,
    pageTitle: @Composable (SettingsCategory) -> String,
    groupTitle: @Composable (SettingsRailGroup) -> String,
    onOpen: (SettingsSearchEntry) -> Unit,
    initialFocusRequester: FocusRequester?
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val entries = remember(visibleCategories, index, context) {
        visibleCategories.flatMap { category ->
            index[category].orEmpty().map { SettingsSearchEntry(category, context.getString(it)) }
        }
    }
    val changedEntries = remember(changedSettings, context) {
        changedSettings.mapTo(HashSet()) { key -> SettingsSearchEntry(key.category, context.getString(key.title)) }
    }
    val results = remember(query, entries, changedOnly, changedEntries) {
        settingsSearchResults(query, entries, changedOnly, changedEntries)
    }
    // Page and category names, resolved once per page rather than once per result.
    val paths = visibleCategories.mapNotNull { category ->
        val group = category.group ?: return@mapNotNull null
        category to stringResource(R.string.settings_search_path, groupTitle(group), pageTitle(category))
    }.toMap()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_search_title),
            subtitle = stringResource(R.string.settings_search_subtitle)
        )
        InputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.settings_search_hint),
            onImeAction = { if (results.isNotEmpty()) focusManager.moveFocus(FocusDirection.Down) },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier)
        )
        SettingsToggleRow(
            title = stringResource(R.string.settings_search_changed_only),
            subtitle = stringResource(R.string.settings_search_changed_only_sub),
            checked = changedOnly,
            onToggle = { onChangedOnlyChange(!changedOnly) }
        )
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                query.isBlank() && !changedOnly -> SearchNote(stringResource(R.string.settings_search_prompt))
                results.isEmpty() && query.isBlank() -> SearchNote(stringResource(R.string.settings_search_changed_empty))
                results.isEmpty() -> SearchNote(stringResource(R.string.settings_search_empty, query.trim()))
                else -> {
                    val listState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
                        ) {
                            items(results, key = { "${it.category}:${it.title}" }) { result ->
                                SettingsActionRow(
                                    title = result.title,
                                    subtitle = paths[result.category],
                                    onClick = { onOpen(result) }
                                )
                            }
                        }
                        SettingsVerticalScrollIndicators(state = listState)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = FetchTheme.colors.TextSecondary,
        modifier = Modifier.fillMaxWidth()
    )
}
