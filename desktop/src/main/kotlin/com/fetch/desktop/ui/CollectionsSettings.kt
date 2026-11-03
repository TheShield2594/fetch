package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.collections.CollectionEditing
import com.fetch.desktop.collections.CollectionSourceResolver
import com.fetch.desktop.collections.MediaChoice
import com.fetch.desktop.collections.PickableCatalog
import com.fetch.desktop.tmdb.TmdbCollectionSources
import com.fetch.desktop.tmdb.TmdbSearchHit
import com.fetch.desktop.tracking.TraktPublicList
import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.CollectionSource
import com.fetch.tv.domain.model.FolderViewMode
import com.fetch.tv.domain.model.LocalListCollectionSource
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.TmdbCollectionFilters
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import com.fetch.tv.domain.model.TmdbCollectionSort
import com.fetch.tv.domain.model.TmdbCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionSourceType
import com.fetch.tv.domain.model.TraktCollectionSource
import com.fetch.tv.domain.model.TraktListSort
import com.fetch.tv.domain.model.TraktSortHow
import kotlinx.coroutines.launch

private val ViewModes = listOf(
    DropdownOption(FolderViewMode.TABBED_GRID, "Tabbed grid"),
    DropdownOption(FolderViewMode.ROWS, "Rows"),
    DropdownOption(FolderViewMode.FOLLOW_LAYOUT, "Follow home layout")
)

private val TileShapes = listOf(
    DropdownOption(PosterShape.POSTER, "Poster"),
    DropdownOption(PosterShape.LANDSCAPE, "Landscape"),
    DropdownOption(PosterShape.SQUARE, "Square")
)

/**
 * Settings › Content & Discovery › Collections, as the TV's Collections screen: make, edit,
 * reorder, pin and delete collections, and import or export them as the TV's JSON. Editing one
 * opens its editor in place.
 */
@Composable
fun CollectionsSettingsPage(state: AppState, onBack: () -> Unit) {
    val collections = state.collections
    var editing by remember { mutableStateOf<Collection?>(null) }
    var deleting by remember { mutableStateOf<Collection?>(null) }
    var importing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    editing?.let { draft ->
        CollectionEditor(state, draft, onDone = { saved ->
            if (saved != null) collections.save(saved)
            editing = null
        })
        return
    }

    SettingsHeader(tr("Collections"), onBack) {
        SecondaryButton("Import", onClick = { importing = true })
        if (collections.collections.isNotEmpty()) SecondaryButton("Export", onClick = { exporting = true })
        PrimaryButton("New collection", icon = FetchIcons.Add, onClick = { editing = CollectionEditing.newCollection(collections.newId()) })
    }
    Text(
        "Collections are Home rows of folders; each folder opens a page of catalogs, TMDB, Trakt and your lists. " +
            "Pinned ones lead Home, the rest follow its catalog rows. They sync with your TV through your sync server.",
        color = FetchPalette.TextSecondary
    )
    if (collections.collections.isEmpty()) {
        Text(tr("No collections yet."), color = FetchPalette.TextMuted)
    } else {
        SettingsSection(null) {
            collections.collections.forEachIndexed { i, c ->
                SettingsRow(
                    title = c.title.ifBlank { "Untitled collection" },
                    description = listOfNotNull(
                        "${c.folders.size} folder${if (c.folders.size == 1) "" else "s"}",
                        ViewModes.first { it.value == c.viewMode }.label,
                        "Pinned to the top of Home".takeIf { c.pinToTop }
                    ).joinToString(" · "),
                    onClick = { editing = c }
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircleIconButton(FetchIcons.ArrowUp, "Move up", onClick = { collections.move(c.id, -1) }, size = 34.dp, enabled = i > 0)
                        CircleIconButton(FetchIcons.ArrowDown, "Move down", onClick = { collections.move(c.id, 1) }, size = 34.dp, enabled = i < collections.collections.lastIndex)
                        SecondaryButton(if (c.pinToTop) "Unpin" else "Pin to top", onClick = { collections.setPinned(c.id, !c.pinToTop) })
                        SecondaryButton("Edit", onClick = { editing = c })
                        CircleIconButton(FetchIcons.Delete, "Delete", onClick = { deleting = c }, size = 34.dp)
                    }
                }
            }
        }
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = FetchPalette.SurfaceRaised,
            title = { Text("Delete ${c.title.ifBlank { "this collection" }}?", color = Color.White) },
            text = { Text(tr("Its folders go too, here and on your other devices."), color = FetchPalette.TextSecondary) },
            confirmButton = { PrimaryButton("Delete", icon = null, onClick = { collections.delete(c.id); deleting = null }) },
            dismissButton = { SecondaryButton("Keep", onClick = { deleting = null }) }
        )
    }
    if (importing) ImportDialog(state, onDismiss = { importing = false })
    if (exporting) ExportDialog(collections.exportJson(), onDismiss = { exporting = false })
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircleIconButton(FetchIcons.Back, "Back", onClick = onBack, size = 40.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
private fun ImportDialog(state: AppState, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(tr("Import collections"), color = Color.White) },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Paste collections JSON, as the TV's Export or the website gives it."), color = FetchPalette.TextSecondary)
                CollectionField(text, tr("[ { \"id\": …, \"title\": …, \"folders\": [ … ] } ]"), onChange = { text = it; error = null }, singleLine = false, minHeight = 180)
                FilterDropdown("Mode", replace, listOf(DropdownOption(false, "Add to mine"), DropdownOption(true, "Replace all of mine")), onSelect = { replace = it })
                error?.let { Text(it, color = FetchPalette.Error) }
            }
        },
        confirmButton = {
            PrimaryButton("Import", icon = null, enabled = text.isNotBlank(), onClick = {
                error = state.collections.import(text, replace)
                if (error == null) onDismiss()
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}

@Composable
private fun ExportDialog(json: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(tr("Export collections"), color = Color.White) },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("The TV's format: import it on a TV, the website or another computer."), color = FetchPalette.TextSecondary)
                CollectionField(json, "", onChange = {}, singleLine = false, minHeight = 220, maxHeight = 360)
            }
        },
        confirmButton = { PrimaryButton("Copy", icon = null, onClick = { clipboard.setText(AnnotatedString(json)); onDismiss() }) },
        dismissButton = { SecondaryButton("Close", onClick = onDismiss) }
    )
}

// --- Editing a collection ---

@Composable
private fun CollectionEditor(state: AppState, initial: Collection, onDone: (Collection?) -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var editingFolder by remember { mutableStateOf<CollectionFolder?>(null) }
    var deletingFolder by remember { mutableStateOf<CollectionFolder?>(null) }
    val isNew = state.collections.find(initial.id) == null

    editingFolder?.let { folder ->
        FolderEditor(state, folder, onDone = { saved ->
            if (saved != null) {
                val index = draft.folders.indexOfFirst { it.id == saved.id }
                draft = draft.copy(folders = if (index >= 0) draft.folders.toMutableList().also { it[index] = saved } else draft.folders + saved)
            }
            editingFolder = null
        })
        return
    }

    SettingsHeader(if (isNew) tr("New collection") else tr("Edit collection"), onBack = { onDone(null) }) {
        SecondaryButton("Cancel", onClick = { onDone(null) })
        PrimaryButton("Save", icon = null, enabled = draft.folders.isNotEmpty(), onClick = { onDone(CollectionEditing.cleanCollection(draft)) })
    }
    if (draft.folders.isEmpty()) Text(tr("Add a folder to save this collection."), color = FetchPalette.TextMuted)
    SettingsSection("Collection") {
        LabeledField(tr("Title"), draft.title, tr("Untitled collection")) { draft = draft.copy(title = it.take(80)) }
        LabeledField(tr("Backdrop image"), draft.backdropImageUrl.orEmpty(), "https://… (shown behind its folders' pages)") { draft = draft.copy(backdropImageUrl = it) }
        SettingsToggle("Pin to top", "Show this collection before the other rows on Home.", draft.pinToTop) { draft = draft.copy(pinToTop = it) }
        SettingsChoice("Folder view", "How a folder's page shows its sources.", draft.viewMode, ViewModes) { draft = draft.copy(viewMode = it) }
        SettingsToggle("Show \"All\" tab", "A tab with every source together, for folders with two sources or more.", draft.showAllTab) { draft = draft.copy(showAllTab = it) }
    }
    SettingsSection("Folders") {
        draft.folders.forEachIndexed { i, folder ->
            SettingsRow(
                title = folder.title.ifBlank { "Untitled" },
                description = folder.sources.joinToString(" · ") { CollectionSourceResolver.label(it, state.addons.toList()).first }
                    .ifBlank { "No sources" },
                onClick = { editingFolder = folder }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniTile(folder)
                    CircleIconButton(FetchIcons.ArrowUp, "Move up", onClick = { draft = draft.copy(folders = CollectionEditing.move(draft.folders, i, -1)) }, size = 34.dp, enabled = i > 0)
                    CircleIconButton(FetchIcons.ArrowDown, "Move down", onClick = { draft = draft.copy(folders = CollectionEditing.move(draft.folders, i, 1)) }, size = 34.dp, enabled = i < draft.folders.lastIndex)
                    SecondaryButton("Edit", onClick = { editingFolder = folder })
                    CircleIconButton(FetchIcons.Delete, "Delete", onClick = { deletingFolder = folder }, size = 34.dp)
                }
            }
        }
        SettingsRow("Add folder", "A tile on the row, opening a page of sources.", onClick = {
            editingFolder = CollectionEditing.newFolder(state.collections.newId())
        }) { Icon(FetchIcons.Add, contentDescription = null, tint = FetchPalette.TextSecondary) }
    }
    deletingFolder?.let { f ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            containerColor = FetchPalette.SurfaceRaised,
            title = { Text(tr("Remove %1\$s?", f.title.ifBlank { "this folder" }), color = Color.White) },
            text = { Text(tr("It goes when you save the collection."), color = FetchPalette.TextSecondary) },
            confirmButton = { PrimaryButton("Remove", icon = null, onClick = { draft = draft.copy(folders = draft.folders.filterNot { it.id == f.id }); deletingFolder = null }) },
            dismissButton = { SecondaryButton("Keep", onClick = { deletingFolder = null }) }
        )
    }
}

@Composable
private fun MiniTile(folder: CollectionFolder) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(40.dp).clip(shape).background(FetchPalette.SurfaceHigh), contentAlignment = Alignment.Center) {
        val image = folder.coverImageUrl?.takeIf { it.isNotBlank() }
        when {
            image != null -> AsyncImage(image, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(40.dp))
            !folder.coverEmoji.isNullOrBlank() -> Text(folder.coverEmoji.orEmpty(), fontSize = 20.sp)
            else -> Text(folder.title.take(2).uppercase(), color = FetchPalette.TextSecondary, fontSize = 13.sp)
        }
    }
}

// --- Editing a folder ---

private enum class CoverKind(val label: String) { IMAGE("Image"), EMOJI("Emoji"), NONE("None") }

private enum class SourcePicker { CATALOG, TMDB, TRAKT, LOCAL, WEBSITE }

@Composable
private fun FolderEditor(state: AppState, initial: CollectionFolder, onDone: (CollectionFolder?) -> Unit) {
    var folder by remember(initial.id) { mutableStateOf(initial) }
    var cover by remember(initial.id) {
        mutableStateOf(
            when {
                !initial.coverImageUrl.isNullOrBlank() -> CoverKind.IMAGE
                !initial.coverEmoji.isNullOrBlank() -> CoverKind.EMOJI
                else -> CoverKind.NONE
            }
        )
    }
    var picker by remember { mutableStateOf<SourcePicker?>(null) }
    val catalogs = remember(state.activeAddons) { CollectionEditing.pickableCatalogs(state.activeAddons) }
    val isNew = folder.sources.isEmpty() && initial.title.isBlank()

    SettingsHeader(if (isNew) tr("New folder") else tr("Edit folder"), onBack = { onDone(null) }) {
        SecondaryButton("Cancel", onClick = { onDone(null) })
        PrimaryButton("Done", icon = null, enabled = folder.sources.isNotEmpty(), onClick = { onDone(CollectionEditing.cleanFolder(folder)) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
        FolderTile(CollectionEditing.cleanFolder(folder), onClick = {})
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("PREVIEW"), style = OverlineStyle, color = FetchPalette.TextMuted)
            Text(
                if (folder.sources.isEmpty()) tr("Add a source below: a folder needs one at least.") else "${folder.sources.size} source${if (folder.sources.size == 1) "" else "s"}",
                color = FetchPalette.TextSecondary
            )
        }
    }
    SettingsSection("Folder") {
        LabeledField(tr("Title"), folder.title, tr("Untitled")) { folder = folder.copy(title = it.take(60)) }
        SettingsChoice("Cover", null, cover, CoverKind.entries.map { DropdownOption(it, it.label) }) {
            cover = it
            folder = when (it) {
                CoverKind.IMAGE -> folder.copy(coverEmoji = null)
                CoverKind.EMOJI -> folder.copy(coverImageUrl = null)
                CoverKind.NONE -> folder.copy(coverImageUrl = null, coverEmoji = null)
            }
        }
        when (cover) {
            CoverKind.IMAGE -> LabeledField(tr("Cover image"), folder.coverImageUrl.orEmpty(), "https://…") { folder = folder.copy(coverImageUrl = it) }
            CoverKind.EMOJI -> {
                LabeledField(tr("Emoji"), folder.coverEmoji.orEmpty(), tr("Type or pick one")) { folder = folder.copy(coverEmoji = it.take(16)) }
                EmojiPicks(selected = folder.coverEmoji) { folder = folder.copy(coverEmoji = it) }
            }
            CoverKind.NONE -> Unit
        }
        SettingsChoice("Tile shape", null, folder.tileShape, TileShapes) { folder = folder.copy(tileShape = it) }
        SettingsToggle("Hide title", "Show only the cover on the tile.", folder.hideTitle) { folder = folder.copy(hideTitle = it) }
        LabeledField(tr("Hover animation"), folder.focusGifUrl.orEmpty(), tr("A GIF address, played over the cover on hover (optional)")) { folder = folder.copy(focusGifUrl = it) }
        LabeledField(tr("Page backdrop"), folder.heroBackdropUrl.orEmpty(), "https://… (optional)") { folder = folder.copy(heroBackdropUrl = it) }
        LabeledField(tr("Title logo"), folder.titleLogoUrl.orEmpty(), "https://… (optional, shown on its page)") { folder = folder.copy(titleLogoUrl = it) }
    }
    SettingsSection("Sources") {
        if (folder.sources.isEmpty()) {
            Text(tr("No sources yet."), color = FetchPalette.TextMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }
        folder.sources.forEachIndexed { i, source ->
            val (label, kind) = CollectionSourceResolver.label(source, state.addons.toList())
            SettingsRow(label, kind) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (source is AddonCatalogCollectionSource) {
                        val catalog = catalogs.firstOrNull { it.matches(source) }
                        if (catalog != null && catalog.genres.isNotEmpty()) {
                            val options = (if (catalog.genreRequired) emptyList() else listOf(DropdownOption<String?>(null, "All genres"))) +
                                catalog.genres.map { DropdownOption<String?>(it, it) }
                            FilterDropdown("Genre", source.genre, options, onSelect = { g -> folder = CollectionEditing.setGenre(folder, i, g, catalogs) })
                        }
                        if (catalog == null) Text(tr("Not installed"), color = FetchPalette.TextMuted, fontSize = 13.sp)
                    }
                    CircleIconButton(FetchIcons.ArrowUp, "Move up", onClick = { folder = folder.copy(sources = CollectionEditing.move(folder.sources, i, -1)) }, size = 34.dp, enabled = i > 0)
                    CircleIconButton(FetchIcons.ArrowDown, "Move down", onClick = { folder = folder.copy(sources = CollectionEditing.move(folder.sources, i, 1)) }, size = 34.dp, enabled = i < folder.sources.lastIndex)
                    CircleIconButton(FetchIcons.Delete, "Remove", onClick = { folder = CollectionEditing.removeSource(folder, i) }, size = 34.dp)
                }
            }
        }
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            SecondaryButton("Addon catalogs", icon = FetchIcons.Add, onClick = { picker = SourcePicker.CATALOG })
            SecondaryButton("TMDB", icon = FetchIcons.Add, onClick = { picker = SourcePicker.TMDB })
            if (state.tracking.credentials.traktAvailable) SecondaryButton("Trakt list", icon = FetchIcons.Add, onClick = { picker = SourcePicker.TRAKT })
            SecondaryButton("Local list", icon = FetchIcons.Add, onClick = { picker = SourcePicker.LOCAL })
            if (com.fetch.desktop.website.FetchWebsiteState.shared.session != null) {
                SecondaryButton("Website list", icon = FetchIcons.Add, onClick = { picker = SourcePicker.WEBSITE })
            }
        }
    }
    when (picker) {
        SourcePicker.CATALOG -> CatalogPickerDialog(catalogs, folder, onToggle = { folder = CollectionEditing.toggleCatalog(folder, it) }, onDismiss = { picker = null })
        SourcePicker.TMDB -> TmdbPickerDialog(state, onAdd = { sources, image ->
            folder = CollectionEditing.addSources(folder, sources, image)
            if (folder.coverImageUrl != null) cover = CoverKind.IMAGE
            picker = null
        }, onDismiss = { picker = null })
        SourcePicker.TRAKT -> TraktPickerDialog(state, onAdd = { sources, image ->
            folder = CollectionEditing.addSources(folder, sources, image)
            if (folder.coverImageUrl != null) cover = CoverKind.IMAGE
            picker = null
        }, onDismiss = { picker = null })
        SourcePicker.LOCAL -> LocalListPickerDialog(state, folder, onChange = { folder = it }, onDismiss = { picker = null })
        SourcePicker.WEBSITE -> WebsiteListPickerDialog(state, folder, onChange = { folder = it }, onDismiss = { picker = null })
        null -> Unit
    }
}

@Composable
private fun EmojiPicks(selected: String?, onPick: (String) -> Unit) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)
    ) {
        CollectionEditing.emojis.forEach { emoji ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (emoji == selected) FetchPalette.Accent else if (hovered) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) { onPick(emoji) }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) { Text(emoji, fontSize = 22.sp) }
        }
    }
}

// --- Source pickers ---

@Composable
private fun PickerDialog(title: String, onDismiss: () -> Unit, confirm: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(title, color = Color.White) },
        text = {
            Column(
                Modifier.width(620.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) { content() }
        },
        confirmButton = confirm,
        dismissButton = { SecondaryButton("Close", onClick = onDismiss) }
    )
}

@Composable
private fun CheckRow(label: String, detail: String?, on: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
                .background(if (on) FetchPalette.Accent else Color.Transparent)
                .border(2.dp, if (on) FetchPalette.Accent else FetchPalette.Outline, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) { if (on) Icon(FetchIcons.Check, contentDescription = null, tint = FetchPalette.OnAccent, modifier = Modifier.size(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = FetchPalette.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CatalogPickerDialog(catalogs: List<PickableCatalog>, folder: CollectionFolder, onToggle: (PickableCatalog) -> Unit, onDismiss: () -> Unit) {
    PickerDialog(tr("Addon catalogs"), onDismiss) {
        if (catalogs.isEmpty()) Text(tr("Install an addon with catalogs in Settings › Content & Discovery › Addons first."), color = FetchPalette.TextMuted)
        catalogs.groupBy { it.addon.baseUrl }.values.forEach { group ->
            Text(group.first().addon.displayName.uppercase(), style = OverlineStyle, color = FetchPalette.TextMuted, modifier = Modifier.padding(top = 6.dp))
            group.forEach { c ->
                val on = folder.sources.any { it is AddonCatalogCollectionSource && c.matches(it) }
                CheckRow(
                    c.catalog.name.replaceFirstChar { it.uppercase() },
                    listOfNotNull(com.fetch.desktop.typeLabel(c.catalog.apiType), "genres".takeIf { c.genres.isNotEmpty() }).joinToString(" · "),
                    on
                ) { onToggle(c) }
            }
        }
    }
}

/** The profile's lists on the Fetch website, as the TV's "Fetch website list" source picks them. */
@Composable
private fun WebsiteListPickerDialog(state: AppState, folder: CollectionFolder, onChange: (CollectionFolder) -> Unit, onDismiss: () -> Unit) {
    var media by remember { mutableStateOf<TmdbCollectionMediaType?>(null) }
    val website = com.fetch.desktop.website.FetchWebsiteState.shared
    val loaded by androidx.compose.runtime.produceState<Result<List<com.fetch.desktop.website.WebsiteList>>?>(null) {
        value = runCatching {
            val profile = website.profileFor(state.profile.id) ?: error("This profile has no profile on the Fetch website yet.")
            website.client.lists(profile.id)
        }
    }
    PickerDialog(tr("Fetch website lists"), onDismiss) {
        val result = loaded
        when {
            result == null -> Text(tr("Loading…"), color = FetchPalette.TextMuted)
            result.isFailure -> Text(result.exceptionOrNull()?.message ?: tr("Couldn’t load the website’s lists."), color = FetchPalette.TextMuted)
            result.getOrThrow().isEmpty() -> Text(tr("This profile has no lists on the website yet."), color = FetchPalette.TextMuted)
            else -> {
                FilterDropdown(
                    "Show", media,
                    listOf(DropdownOption<TmdbCollectionMediaType?>(null, "Movies and series"), DropdownOption(TmdbCollectionMediaType.MOVIE, "Movies"), DropdownOption(TmdbCollectionMediaType.TV, "Series")),
                    onSelect = { media = it }
                )
                result.getOrThrow().forEach { list ->
                    val existing = folder.sources.filterIsInstance<com.fetch.tv.domain.model.FetchListCollectionSource>().firstOrNull { it.listId == list.id }
                    CheckRow(list.name, list.kind.replaceFirstChar { it.uppercase() }, existing != null) {
                        onChange(
                            if (existing != null) folder.copy(sources = folder.sources - existing)
                            else folder.copy(sources = folder.sources + com.fetch.tv.domain.model.FetchListCollectionSource(list.id, list.name, media))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalListPickerDialog(state: AppState, folder: CollectionFolder, onChange: (CollectionFolder) -> Unit, onDismiss: () -> Unit) {
    var media by remember { mutableStateOf<TmdbCollectionMediaType?>(null) }
    PickerDialog(tr("Local lists"), onDismiss) {
        val lists = state.lists.local
        if (lists.isEmpty()) Text(tr("Make a list in the Library first (Manage lists)."), color = FetchPalette.TextMuted)
        else FilterDropdown(
            "Show", media,
            listOf(DropdownOption<TmdbCollectionMediaType?>(null, "Movies and series"), DropdownOption(TmdbCollectionMediaType.MOVIE, "Movies"), DropdownOption(TmdbCollectionMediaType.TV, "Series")),
            onSelect = { media = it }
        )
        lists.forEach { list ->
            val existing = folder.sources.filterIsInstance<LocalListCollectionSource>().firstOrNull { it.listId == list.id }
            CheckRow(list.name, "${list.items.size} titles", existing != null) {
                onChange(
                    if (existing != null) folder.copy(sources = folder.sources - existing)
                    else folder.copy(sources = folder.sources + LocalListCollectionSource(list.id, list.name, media))
                )
            }
        }
    }
}

private val TmdbModes = listOf(
    DropdownOption<TmdbCollectionSourceType?>(null, "Quick picks"),
    DropdownOption<TmdbCollectionSourceType?>(TmdbCollectionSourceType.COMPANY, "Production company"),
    DropdownOption<TmdbCollectionSourceType?>(TmdbCollectionSourceType.NETWORK, "Network"),
    DropdownOption<TmdbCollectionSourceType?>(TmdbCollectionSourceType.COLLECTION, "Movie collection"),
    DropdownOption<TmdbCollectionSourceType?>(TmdbCollectionSourceType.LIST, "TMDB list"),
    DropdownOption<TmdbCollectionSourceType?>(TmdbCollectionSourceType.PERSON, "Person (cast)"),
    DropdownOption<TmdbCollectionSourceType?>(TmdbCollectionSourceType.DIRECTOR, "Director"),
    DropdownOption<TmdbCollectionSourceType?>(TmdbCollectionSourceType.DISCOVER, "Discover (filters)")
)

private val TmdbSorts = listOf(
    DropdownOption(TmdbCollectionSort.POPULAR_DESC.value, "Most popular"),
    DropdownOption(TmdbCollectionSort.VOTE_AVERAGE_DESC.value, "Highest rated"),
    DropdownOption(TmdbCollectionSort.VOTE_COUNT_DESC.value, "Most votes"),
    DropdownOption(TmdbCollectionSort.RELEASE_DATE_DESC.value, "Newest")
)

@Composable
private fun TmdbPickerDialog(state: AppState, onAdd: (List<CollectionSource>, String?) -> Unit, onDismiss: () -> Unit) {
    val tmdb = remember(state) { TmdbCollectionSources(state.tmdb.client) }
    val language = state.settings.tmdb.language
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<TmdbCollectionSourceType?>(null) }
    var input by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var media by remember { mutableStateOf(MediaChoice.MOVIES) }
    var sort by remember { mutableStateOf(TmdbCollectionSort.POPULAR_DESC.value) }
    var filters by remember { mutableStateOf(TmdbCollectionFilters()) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TmdbSearchHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    /** Adds [type] sources for [id], with the folder cover and title TMDB has for it. */
    fun add(type: TmdbCollectionSourceType, id: Int?, givenTitle: String?) {
        scope.launch {
            busy = true
            error = null
            val meta = id?.let { runCatching { tmdb.metadata(type, it, language) }.getOrNull() }
            if (id != null && meta == null && type in setOf(TmdbCollectionSourceType.LIST, TmdbCollectionSourceType.COLLECTION)) {
                error = "TMDB doesn't know that ${if (type == TmdbCollectionSourceType.LIST) "list" else "collection"}."
                busy = false
                return@launch
            }
            val base = title.ifBlank { givenTitle ?: meta?.title ?: TmdbModes.first { it.value == type }.label }
            val types = CollectionEditing.tmdbMediaTypes(type, media)
            val sortBy = if (type == TmdbCollectionSourceType.LIST || type == TmdbCollectionSourceType.COLLECTION) {
                if (sort == TmdbCollectionSort.POPULAR_DESC.value) TmdbCollectionSort.ORIGINAL.value else sort
            } else sort
            busy = false
            onAdd(types.map { t ->
                TmdbCollectionSource(type, CollectionEditing.titleForMedia(base, t, types.size > 1), id, t, sortBy,
                    if (type == TmdbCollectionSourceType.DISCOVER) filters else TmdbCollectionFilters())
            }, meta?.coverImageUrl)
        }
    }

    PickerDialog(
        tr("TMDB source"),
        onDismiss,
        confirm = {
            if (mode != null) PrimaryButton("Add", icon = null, enabled = !busy, onClick = {
                val type = mode!!
                if (type == TmdbCollectionSourceType.DISCOVER) add(type, null, null)
                else {
                    val id = TmdbCollectionSources.parseTmdbId(input)
                    if (id == null) error = "Enter a TMDB id or address, or pick a search result." else add(type, id, null)
                }
            })
        }
    ) {
        if (!state.tmdb.client.hasKey) Text(tr("TMDB sources need a TMDB API key: add yours in Settings › TMDB."), color = FetchPalette.Error)
        FilterDropdown("Source", mode, TmdbModes, onSelect = { mode = it; hits = emptyList(); input = ""; error = null })
        val type = mode
        if (type == null) {
            Text(tr("Studios and streaming networks, in one click."), color = FetchPalette.TextSecondary)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CollectionEditing.tmdbPresets.forEach { preset ->
                    SecondaryButton(preset.title, onClick = {
                        scope.launch {
                            val meta = runCatching { tmdb.metadata(preset.sourceType, preset.tmdbId!!, language) }.getOrNull()
                            onAdd(listOf(preset), meta?.coverImageUrl)
                        }
                    })
                }
            }
        } else {
            val searchable = type in setOf(TmdbCollectionSourceType.COMPANY, TmdbCollectionSourceType.COLLECTION, TmdbCollectionSourceType.PERSON, TmdbCollectionSourceType.DIRECTOR)
            if (searchable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { CollectionField(query, tr("Search TMDB"), onChange = { query = it }) }
                    SecondaryButton("Search", enabled = query.isNotBlank() && !busy, onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            hits = runCatching {
                                when (type) {
                                    TmdbCollectionSourceType.COMPANY -> tmdb.searchCompanies(query)
                                    TmdbCollectionSourceType.COLLECTION -> tmdb.searchCollections(query, language)
                                    else -> tmdb.searchPeople(query, language)
                                }
                            }.getOrElse { error = it.message; emptyList() }
                            if (hits.isEmpty() && error == null) error = "Nothing found."
                            busy = false
                        }
                    })
                }
                hits.take(12).forEach { hit ->
                    SearchHitRow(hit.name, hit.subtitle, hit.image) { add(type, hit.id, hit.name) }
                }
            }
            if (type != TmdbCollectionSourceType.DISCOVER) {
                CollectionField(input, if (searchable) tr("…or a TMDB id or address") else "TMDB id or address (themoviedb.org/${if (type == TmdbCollectionSourceType.NETWORK) "network" else "list"}/…)", onChange = { input = it })
            }
            CollectionField(title, tr("Title (optional)"), onChange = { title = it.take(60) })
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (type in setOf(TmdbCollectionSourceType.COMPANY, TmdbCollectionSourceType.PERSON, TmdbCollectionSourceType.DIRECTOR, TmdbCollectionSourceType.DISCOVER)) {
                    FilterDropdown("Show", media, MediaChoice.entries.map { DropdownOption(it, it.label) }, onSelect = { media = it })
                }
                FilterDropdown("Sort", sort, (if (type == TmdbCollectionSourceType.LIST || type == TmdbCollectionSourceType.COLLECTION)
                    listOf(DropdownOption(TmdbCollectionSort.POPULAR_DESC.value, "Their order")) + TmdbSorts.drop(1) else TmdbSorts), onSelect = { sort = it })
            }
            if (type == TmdbCollectionSourceType.DISCOVER) DiscoverFilters(tmdb, language, media, filters) { filters = it }
        }
        if (busy) CircularProgressIndicator(color = FetchPalette.Accent, modifier = Modifier.size(24.dp))
        error?.let { Text(it, color = FetchPalette.Error) }
    }
}

@Composable
private fun SearchHitRow(name: String, subtitle: String?, image: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(8.dp)
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            if (image != null) AsyncImage(image, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.size(36.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = FetchPalette.TextMuted, fontSize = 12.sp, maxLines = 1)
        }
        Text(tr("Add"), color = FetchPalette.Accent, fontWeight = FontWeight.SemiBold)
    }
}

/** The TV's main discover filters: genres in and out, dates, rating, votes, language, country, keywords, year. */
@Composable
private fun DiscoverFilters(tmdb: TmdbCollectionSources, language: String, media: MediaChoice, filters: TmdbCollectionFilters, onChange: (TmdbCollectionFilters) -> Unit) {
    val genreType = if (media == MediaChoice.SERIES) TmdbCollectionMediaType.TV else TmdbCollectionMediaType.MOVIE
    var genres by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(genreType, language) { genres = runCatching { tmdb.genres(genreType, language) }.getOrDefault(emptyMap()) }
    val scope = rememberCoroutineScope()
    var keywordQuery by remember { mutableStateOf("") }
    var keywordHits by remember { mutableStateOf<List<TmdbSearchHit>>(emptyList()) }

    fun ids(raw: String?): List<Int> = raw.orEmpty().split(',', '|').mapNotNull { it.trim().toIntOrNull() }
    fun join(list: List<Int>): String? = list.distinct().joinToString(",").ifBlank { null }

    Text(tr("FILTERS"), style = OverlineStyle, color = FetchPalette.TextMuted, modifier = Modifier.padding(top = 8.dp))
    if (genres.isNotEmpty()) {
        Text(tr("Genres"), color = FetchPalette.TextSecondary)
        ChipPicker(genres.entries.map { DropdownOption(it.key, it.value) }, ids(filters.withGenres), onChange = { onChange(filters.copy(withGenres = join(it))) })
        Text(tr("Without genres"), color = FetchPalette.TextSecondary)
        ChipPicker(genres.entries.map { DropdownOption(it.key, it.value) }, ids(filters.withoutGenres), onChange = { onChange(filters.copy(withoutGenres = join(it))) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { CollectionField(filters.releaseDateGte.orEmpty(), tr("Released from (YYYY-MM-DD)"), onChange = { onChange(filters.copy(releaseDateGte = it.trim().ifBlank { null })) }) }
        Box(Modifier.weight(1f)) { CollectionField(filters.releaseDateLte.orEmpty(), tr("Released until (YYYY-MM-DD)"), onChange = { onChange(filters.copy(releaseDateLte = it.trim().ifBlank { null })) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { NumberField(filters.voteAverageGte?.toString(), tr("Rating at least (0–10)")) { onChange(filters.copy(voteAverageGte = it?.toDoubleOrNull())) } }
        Box(Modifier.weight(1f)) { NumberField(filters.voteAverageLte?.toString(), tr("Rating at most")) { onChange(filters.copy(voteAverageLte = it?.toDoubleOrNull())) } }
        Box(Modifier.weight(1f)) { NumberField(filters.voteCountGte?.toString(), tr("Votes at least")) { onChange(filters.copy(voteCountGte = it?.toIntOrNull())) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { CollectionField(filters.withOriginalLanguage.orEmpty(), tr("Original language (en, ko, ja…)"), onChange = { onChange(filters.copy(withOriginalLanguage = it.trim().lowercase().ifBlank { null })) }) }
        Box(Modifier.weight(1f)) { CollectionField(filters.withOriginCountry.orEmpty(), tr("Country (US, KR, JP…)"), onChange = { onChange(filters.copy(withOriginCountry = it.trim().uppercase().ifBlank { null })) }) }
        Box(Modifier.weight(1f)) { NumberField(filters.year?.toString(), tr("Year")) { onChange(filters.copy(year = it?.toIntOrNull())) } }
    }
    Text(tr("Keywords"), color = FetchPalette.TextSecondary)
    val chosen = ids(filters.withKeywords)
    if (chosen.isNotEmpty()) Text("Keyword ids: ${chosen.joinToString(", ")}", color = FetchPalette.TextMuted, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { CollectionField(keywordQuery, tr("Search keywords (\"time travel\")"), onChange = { keywordQuery = it }) }
        SecondaryButton("Search", enabled = keywordQuery.isNotBlank(), onClick = {
            scope.launch { keywordHits = runCatching { tmdb.searchKeywords(keywordQuery) }.getOrDefault(emptyList()) }
        })
        if (chosen.isNotEmpty()) SecondaryButton("Clear", onClick = { onChange(filters.copy(withKeywords = null)) })
    }
    if (keywordHits.isNotEmpty()) {
        ChipPicker(keywordHits.take(12).map { DropdownOption(it.id, it.name) }, chosen, onChange = { onChange(filters.copy(withKeywords = join(it))) })
    }
}

@Composable
private fun TraktPickerDialog(state: AppState, onAdd: (List<CollectionSource>, String?) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var media by remember { mutableStateOf(MediaChoice.MOVIES) }
    var sortBy by remember { mutableStateOf(TraktListSort.RANK.value) }
    var sortHow by remember { mutableStateOf(TraktSortHow.ASC.value) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TraktPublicList>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun add(id: Long, name: String, cover: String?) {
        val base = title.ifBlank { name }
        val types = media.types()
        onAdd(types.map { t ->
            TraktCollectionSource(CollectionEditing.titleForMedia(base, t, types.size > 1), id, t, sortBy, sortHow)
        }, cover)
    }

    PickerDialog(
        tr("Trakt list"),
        onDismiss,
        confirm = {
            PrimaryButton("Add", icon = null, enabled = input.isNotBlank() && !busy, onClick = {
                val path = CollectionEditing.parseTraktList(input)
                if (path == null) {
                    error = "Enter a Trakt list id or address."
                    return@PrimaryButton
                }
                scope.launch {
                    busy = true
                    error = null
                    val list = runCatching { state.tracking.withTraktPublic { publicList(it, path) } }
                    busy = false
                    val found = list.getOrNull()
                    when {
                        list.isFailure -> error = list.exceptionOrNull()?.message ?: "Couldn't reach Trakt."
                        found == null -> error = "Trakt doesn't know that list (a private list needs Trakt connected in Settings › Tracking)."
                        else -> add(found.id, found.name, found.cover)
                    }
                }
            })
        }
    ) {
        val mine = state.lists.traktLists
        if (mine.isNotEmpty()) {
            Text(tr("YOUR LISTS"), style = OverlineStyle, color = FetchPalette.TextMuted)
            mine.forEach { list -> SearchHitRow(list.name, list.itemCount?.let { "$it titles" }, null) { add(list.id, list.name, null) } }
        }
        Text(tr("PUBLIC LISTS"), style = OverlineStyle, color = FetchPalette.TextMuted, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CollectionField(query, tr("Search Trakt lists"), onChange = { query = it }) }
            SecondaryButton("Search", enabled = query.isNotBlank() && !busy, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    hits = runCatching { state.tracking.withTraktPublic { searchLists(it, query) }.orEmpty() }.getOrElse { error = it.message; emptyList() }
                    if (hits.isEmpty() && error == null) error = "No lists found."
                    busy = false
                }
            })
        }
        hits.forEach { list ->
            SearchHitRow(list.name, listOfNotNull(list.owner?.let { "by $it" }, list.itemCount?.let { "$it titles" }).joinToString(" · "), list.cover) {
                add(list.id, list.name, list.cover)
            }
        }
        CollectionField(input, tr("…or a list id or address (trakt.tv/users/…/lists/…)"), onChange = { input = it })
        CollectionField(title, tr("Title (optional)"), onChange = { title = it.take(60) })
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterDropdown("Show", media, MediaChoice.entries.map { DropdownOption(it, it.label) }, onSelect = { media = it })
            FilterDropdown("Sort", sortBy, TraktListSort.entries.map { DropdownOption(it.value, it.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }, onSelect = { sortBy = it })
            FilterDropdown("Order", sortHow, listOf(DropdownOption(TraktSortHow.ASC.value, "Ascending"), DropdownOption(TraktSortHow.DESC.value, "Descending")), onSelect = { sortHow = it })
        }
        if (busy) CircularProgressIndicator(color = FetchPalette.Accent, modifier = Modifier.size(24.dp))
        error?.let { Text(it, color = FetchPalette.Error) }
    }
}

// --- Fields ---

@Composable
private fun LabeledField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(label, color = FetchPalette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(170.dp))
        Box(Modifier.weight(1f)) { CollectionField(value, placeholder, onChange) }
    }
}

@Composable
private fun NumberField(value: String?, placeholder: String, onChange: (String?) -> Unit) {
    var text by remember { mutableStateOf(value.orEmpty()) }
    CollectionField(text, placeholder, onChange = { raw ->
        text = raw.filter { it.isDigit() || it == '.' }
        onChange(text.ifBlank { null })
    })
}

@Composable
private fun CollectionField(value: String, placeholder: String, onChange: (String) -> Unit, singleLine: Boolean = true, minHeight: Int = 0, maxHeight: Int = 0) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
        cursorBrush = SolidColor(FetchPalette.Accent),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = FetchPalette.TextMuted, fontSize = 15.sp, maxLines = if (singleLine) 1 else 4)
                field()
            }
        },
        modifier = Modifier.fillMaxWidth()
            .then(if (minHeight > 0) Modifier.heightIn(min = minHeight.dp, max = if (maxHeight > 0) maxHeight.dp else 400.dp) else Modifier)
            .clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background)
            .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp)).padding(12.dp)
    )
}
