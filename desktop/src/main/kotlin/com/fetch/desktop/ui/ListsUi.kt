package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.LibraryList
import com.fetch.desktop.ListPrivacy
import com.fetch.desktop.ListProvider
import com.fetch.desktop.ListsState
import com.fetch.tv.domain.model.MetaPreview

/**
 * The TV's "Manage lists": which of your lists [preview] is on, across this computer, Trakt and
 * Simkl. Simkl's lists hold a title once, so they're a single choice. Saving sends only what
 * changed.
 */
@Composable
fun ManageListsDialog(state: AppState, preview: MetaPreview, onDismiss: () -> Unit) {
    val lists = state.lists.destinations(preview)
    val before = remember(preview.id) { lists.filter { state.lists.isMember(it, preview.id) }.map { it.key }.toSet() }
    var selected by remember(preview.id) { mutableStateOf(before) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Lists for ${preview.name}", color = Color.White) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                lists.groupBy { it.provider }.forEach { (provider, group) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(provider.label.uppercase(), style = OverlineStyle, color = FetchPalette.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
                        val single = group.firstOrNull()?.selectionGroup != null
                        group.forEach { list ->
                            val on = list.key in selected
                            ChoiceRow(list.title, on, radio = single) {
                                selected = when {
                                    single && on -> selected - list.key
                                    single -> selected - group.map { it.key }.toSet() + list.key
                                    on -> selected - list.key
                                    else -> selected + list.key
                                }
                            }
                        }
                        if (single) ChoiceRow(tr("None"), group.none { it.key in selected }, radio = true) { selected = selected - group.map { it.key }.toSet() }
                    }
                }
                if (state.lists.refreshing) Text(tr("Loading your Trakt, Simkl and MDBList lists…"), color = FetchPalette.TextMuted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            PrimaryButton("Save", icon = null, enabled = !state.lists.busy, onClick = {
                state.lists.apply(preview, ListsState.changes(lists, before, selected))
                onDismiss()
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}

@Composable
private fun ChoiceRow(label: String, on: Boolean, radio: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        val shape = if (radio) CircleShape else RoundedCornerShape(5.dp)
        Box(
            Modifier.size(22.dp).clip(shape).background(if (on) FetchPalette.Accent else Color.Transparent)
                .border(2.dp, if (on) FetchPalette.Accent else FetchPalette.Outline, shape),
            contentAlignment = Alignment.Center
        ) {
            if (on) Icon(FetchIcons.Check, contentDescription = null, tint = FetchPalette.OnAccent, modifier = Modifier.size(16.dp))
        }
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

/**
 * Create, rename, describe, reorder and delete [provider]'s personal lists (this computer's or
 * Trakt's), as the TV Library's list management does.
 */
@Composable
fun ListsManagerDialog(state: AppState, provider: ListProvider, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<LibraryList?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LibraryList?>(null) }
    val personal = state.lists.lists(provider).filter { it.editable }
    when {
        creating || editing != null -> {
            ListEditorDialog(
                provider = provider,
                initial = editing,
                onSave = { name, description, privacy ->
                    editing?.let { state.lists.update(it, name, description, privacy) } ?: state.lists.create(provider, name, description, privacy)
                    creating = false
                    editing = null
                },
                onDismiss = { creating = false; editing = null }
            )
            return
        }
        deleting != null -> {
            val list = deleting!!
            AlertDialog(
                onDismissRequest = { deleting = null },
                containerColor = FetchPalette.SurfaceRaised,
                title = { Text("Delete ${list.title}?", color = Color.White) },
                text = {
                    Text(
                        if (provider == ListProvider.TRAKT || provider == ListProvider.MDBLIST) "The list is deleted on ${provider.label} too. Titles stay in your library."
                        else tr("Titles on it stay in your library."),
                        color = FetchPalette.TextSecondary
                    )
                },
                confirmButton = { PrimaryButton("Delete", icon = null, onClick = { state.lists.delete(list); deleting = null }) },
                dismissButton = { SecondaryButton("Keep", onClick = { deleting = null }) }
            )
            return
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("${provider.label} lists", color = Color.White) },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (personal.isEmpty()) Text(tr("No lists yet."), color = FetchPalette.TextMuted)
                personal.forEachIndexed { i, list ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(list.title, color = Color.White, fontWeight = FontWeight.Medium)
                            Text(
                                listOfNotNull("${list.items.size} titles", list.privacy?.label, list.description).joinToString(" · "),
                                color = FetchPalette.TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                        // MDBList keeps its own list order, so its lists aren't reordered here.
                        if (provider != ListProvider.MDBLIST) CircleIconButton(FetchIcons.ArrowUp, "Move up", onClick = { state.lists.move(list, -1) }, size = 34.dp, enabled = i > 0 && !state.lists.busy)
                        if (provider != ListProvider.MDBLIST) CircleIconButton(FetchIcons.ArrowDown, "Move down", onClick = { state.lists.move(list, 1) }, size = 34.dp, enabled = i < personal.lastIndex && !state.lists.busy)
                        SecondaryButton("Edit", onClick = { editing = list })
                        CircleIconButton(FetchIcons.Delete, "Delete", onClick = { deleting = list }, size = 34.dp)
                    }
                }
            }
        },
        confirmButton = { PrimaryButton("New list", icon = FetchIcons.Add, enabled = !state.lists.busy, onClick = { creating = true }) },
        dismissButton = { SecondaryButton("Done", onClick = onDismiss) }
    )
}

@Composable
private fun ListEditorDialog(provider: ListProvider, initial: LibraryList?, onSave: (String, String?, ListPrivacy) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var privacy by remember { mutableStateOf(initial?.privacy ?: ListPrivacy.PRIVATE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(if (initial == null) "New ${provider.label} list" else tr("Edit list"), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(460.dp)) {
                Field(name, tr("Name"), onChange = { name = it.take(60) })
                if (provider != ListProvider.MDBLIST) Field(description, tr("Description (optional)"), onChange = { description = it.take(300) })
                if (provider == ListProvider.TRAKT) {
                    FilterDropdown("Privacy", privacy, ListPrivacy.entries.map { DropdownOption(it, it.label) }, onSelect = { privacy = it })
                }
                // MDBList's lists are private or public, and have no description Fetch can set (as on TV).
                if (provider == ListProvider.MDBLIST) {
                    FilterDropdown("Privacy", privacy, listOf(ListPrivacy.PRIVATE, ListPrivacy.PUBLIC).map { DropdownOption(it, it.label) }, onSelect = { privacy = it })
                }
            }
        },
        confirmButton = {
            PrimaryButton(if (initial == null) "Create" else "Save", icon = null, enabled = name.isNotBlank(), onClick = {
                onSave(name, description.ifBlank { null }, privacy)
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
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
