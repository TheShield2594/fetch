package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.FilterRow
import com.fetch.desktop.FilterRowFilters
import com.fetch.desktop.FilterRowSource
import com.fetch.desktop.RowSort
import com.fetch.desktop.RowWatched
import com.fetch.desktop.typeLabel
import com.fetch.tv.domain.model.supportsExtra
import java.util.UUID

/** What a filter row can read from, as the TV's FilterRowSources: server libraries, and addon catalogs with a genre. */
data class FilterRowSourceOption(
    val key: String,
    val label: String,
    val source: FilterRowSource,
    /** An addon catalog's genres; null for a server library, whose genres are fetched. */
    val addonGenres: List<String>?,
    val genreRequired: Boolean = false
) {
    val isServer: Boolean get() = source is FilterRowSource.ServerLibrary
}

fun filterRowSourceKey(source: FilterRowSource): String = when (source) {
    is FilterRowSource.ServerLibrary -> "server:${source.accountId}:${source.libraryId}"
    is FilterRowSource.AddonCatalog -> "addon:${source.addonId}:${source.type}:${source.catalogId}"
}

fun filterRowSources(state: AppState): List<FilterRowSourceOption> {
    val servers = state.servers.enabled.flatMap { account ->
        account.libraries.map { lib ->
            val source = FilterRowSource.ServerLibrary(account.id, lib.id)
            FilterRowSourceOption(filterRowSourceKey(source), "${lib.name} · ${account.name}", source, addonGenres = null)
        }
    }
    val addons = state.activeAddons.flatMap { addon ->
        addon.catalogs
            .filter { c ->
                c.supportsExtra("genre") && c.extra.none { it.isRequired && !it.name.equals("genre", ignoreCase = true) } &&
                    c.extraRequired.none { !it.equals("genre", ignoreCase = true) }
            }
            .map { c ->
                val source = FilterRowSource.AddonCatalog(addon.id, c.apiType, c.id)
                FilterRowSourceOption(
                    filterRowSourceKey(source),
                    "${addon.displayName} · ${c.name} (${typeLabel(c.apiType)})",
                    source,
                    addonGenres = c.extra.firstOrNull { it.name.equals("genre", ignoreCase = true) }?.options.orEmpty(),
                    genreRequired = c.extra.any { it.isRequired && it.name.equals("genre", ignoreCase = true) } ||
                        c.extraRequired.any { it.equals("genre", ignoreCase = true) }
                )
            }
    }
    return servers + addons
}

/** Settings › Content & Discovery › Filter rows: the profile's filter rows, as the TV's FilterRowsScreen. */
@Composable
fun FilterRowsPage(state: AppState) {
    var editing by remember { mutableStateOf<FilterRow?>(null) }
    var creating by remember { mutableStateOf(false) }
    val options = filterRowSources(state)
    val labels = options.associate { it.key to it.label }
    SubPageHeader("Filter rows", onBack = state::back)
    Text("Home rows narrowed from one source. They show on Home with your other rows.", color = FetchPalette.TextSecondary)
    SettingsSection(null) {
        if (state.filterRows.isEmpty()) SettingsRow("No filter rows yet", "Make one from a media server library or an addon catalog with genres.")
        state.filterRows.forEach { row ->
            SettingsRow(
                row.title,
                labels[filterRowSourceKey(row.source)]?.let { "$it${summary(row)}" } ?: "Its source is gone: the server library or addon isn't available.",
                onClick = { editing = row }
            ) {
                CircleIconButton(FetchIcons.Delete, "Delete", onClick = { state.deleteFilterRow(row.id) }, size = 36.dp)
            }
        }
    }
    Row(Modifier.padding(top = 4.dp)) {
        PrimaryButton("New filter row", icon = FetchIcons.Add, enabled = options.isNotEmpty(), onClick = { creating = true })
    }
    if (options.isEmpty()) {
        Text("Connect a media server, or install an addon whose catalogs take a genre, to make a filter row.", color = FetchPalette.TextMuted)
    }
    if (creating || editing != null) {
        FilterRowEditor(state, options, editing, onDone = { creating = false; editing = null })
    }
}

private fun summary(row: FilterRow): String {
    val f = row.filters
    val parts = listOfNotNull(
        f.genre,
        when {
            f.yearFrom != null && f.yearTo != null -> "${f.yearFrom}–${f.yearTo}"
            f.yearFrom != null -> "from ${f.yearFrom}"
            f.yearTo != null -> "to ${f.yearTo}"
            else -> null
        },
        f.watched.takeIf { it != RowWatched.ANY }?.label,
        f.minRating?.let { "rated $it+" }
    )
    return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
}

@Composable
private fun FilterRowEditor(state: AppState, options: List<FilterRowSourceOption>, initial: FilterRow?, onDone: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var sourceKey by remember { mutableStateOf(initial?.source?.let(::filterRowSourceKey) ?: options.first().key) }
    var filters by remember { mutableStateOf(initial?.filters ?: FilterRowFilters()) }
    // One id per editor visit, so saving twice can't add two rows.
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }
    val option = options.firstOrNull { it.key == sourceKey }
    var genres by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(sourceKey) {
        genres = option?.addonGenres ?: (option?.source as? FilterRowSource.ServerLibrary)?.let {
            runCatching { state.servers.genres(it.accountId, it.libraryId) }.getOrDefault(emptyList())
        }.orEmpty()
    }
    val canSave = option != null && (!option.genreRequired || filters.genre != null)
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(if (initial == null) "New filter row" else "Edit filter row", color = Color.White) },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(title, option?.label?.substringAfter(" · ")?.let { "Title (default: ${it})" } ?: "Title") { title = it.take(60) }
                Labeled("Source") {
                    FilterDropdown("Source", sourceKey, options.map { DropdownOption(it.key, it.label) }, onSelect = { key ->
                        val next = options.firstOrNull { it.key == key } ?: return@FilterDropdown
                        sourceKey = key
                        // Addon catalogs only filter by genre, and genres differ between sources.
                        filters = if (next.isServer) filters.copy(genre = null) else FilterRowFilters()
                    })
                }
                Labeled("Genre") {
                    FilterDropdown(
                        "Genre",
                        filters.genre,
                        (if (option?.genreRequired == true) emptyList() else listOf(DropdownOption<String?>(null, "Any"))) +
                            genres.map { DropdownOption<String?>(it, it) },
                        onSelect = { filters = filters.copy(genre = it) }
                    )
                }
                if (option?.isServer == true) {
                    val years = (java.time.LocalDate.now().year downTo 1920).toList()
                    Labeled("Years") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterDropdown("From", filters.yearFrom, listOf(DropdownOption<Int?>(null, "Any")) + years.map { DropdownOption<Int?>(it, "$it") }, onSelect = { filters = filters.copy(yearFrom = it) })
                            FilterDropdown("To", filters.yearTo, listOf(DropdownOption<Int?>(null, "Any")) + years.map { DropdownOption<Int?>(it, "$it") }, onSelect = { filters = filters.copy(yearTo = it) })
                        }
                    }
                    Labeled("Watched") {
                        FilterDropdown("Watched", filters.watched, RowWatched.entries.map { DropdownOption(it, it.label) }, onSelect = { filters = filters.copy(watched = it) })
                    }
                    Labeled("Minimum rating") {
                        FilterDropdown(
                            "Rating",
                            filters.minRating,
                            listOf(DropdownOption<Double?>(null, "Any")) + listOf(5.0, 6.0, 6.5, 7.0, 7.5, 8.0, 8.5).map { DropdownOption<Double?>(it, "$it+") },
                            onSelect = { filters = filters.copy(minRating = it) }
                        )
                    }
                    Labeled("Sort") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterDropdown("Sort", filters.sort, RowSort.entries.map { DropdownOption(it, it.label) }, onSelect = { filters = filters.copy(sort = it) })
                            FilterDropdown(
                                "Order",
                                filters.descending,
                                listOf(DropdownOption(true, "Descending"), DropdownOption(false, "Ascending")),
                                onSelect = { filters = filters.copy(descending = it) }
                            )
                        }
                    }
                } else {
                    Text("Addon catalogs only filter by genre.", color = FetchPalette.TextMuted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            PrimaryButton("Save", icon = null, enabled = canSave, onClick = {
                val source = option?.source ?: return@PrimaryButton
                val name = title.trim().ifEmpty { listOfNotNull(filters.genre, option.label.substringBefore(" · ")).joinToString(" · ") }
                state.saveFilterRow(FilterRow(id, name, source, filters))
                onDone()
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDone) }
    )
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = FetchPalette.TextSecondary, modifier = Modifier.width(130.dp))
        content()
    }
}

@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(FetchPalette.Accent),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = FetchPalette.TextMuted, fontSize = 16.sp)
                field()
            }
        },
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background)
            .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp)).padding(12.dp)
    )
}
