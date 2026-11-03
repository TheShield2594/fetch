package com.fetch.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape

/** One choice in a [FilterDropdown]. */
data class DropdownOption<T>(val value: T, val label: String)

/**
 * A pill showing "Label: Value ▾" that opens a menu of [options], like the TV app's filter and
 * picker rows.
 */
@Composable
fun <T> FilterDropdown(
    label: String,
    selected: T,
    options: List<DropdownOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select"
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val current = options.firstOrNull { it.value == selected }?.label ?: placeholder
    Box(modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (hovered || open) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
                .border(1.dp, if (open) Color.White else Color.Transparent, RoundedCornerShape(50))
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp)
        ) {
            if (label.isNotEmpty()) Text("$label:", color = FetchPalette.TextMuted, fontSize = 14.sp)
            Text(current, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(FetchIcons.ArrowDown, contentDescription = null, tint = FetchPalette.TextSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = FetchPalette.SurfaceRaised,
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            color = if (isSelected) FetchPalette.Accent else FetchPalette.TextPrimary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(option.value)
                    }
                )
            }
        }
    }
}

/** A page heading with an optional line under it, as at the top of Library and Discover. */
@Composable
fun PageHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Color.White)
        if (subtitle != null) Text(subtitle, color = FetchPalette.TextSecondary)
    }
}

/**
 * A grid of posters that fills the width, with a scrollbar. [header] sits above the posters and
 * spans the whole width; [footer] below them.
 */
@Composable
fun PosterGrid(
    items: List<MetaPreview>,
    state: LazyGridState,
    onOpen: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null
) {
    val style = LocalCardStyle.current
    val shape = style.shapeFor(items.groupingBy { it.posterShape }.eachCount().maxByOrNull { it.value }?.key ?: PosterShape.POSTER)
    Box(modifier) {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(style.size(shape).width),
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 40.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            header?.invoke(this)
            items(items, key = { "${it.apiType}:${it.id}" }) { item ->
                PosterCard(item, onClick = { onOpen(item) }, shape = shape, showLabels = showLabels, fillWidth = true)
            }
            footer?.invoke(this)
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp, horizontal = 4.dp)
        )
    }
}

/** A grid item that spans every column. */
fun LazyGridScope.fullWidthItem(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

// --- Settings rows ---

/** A titled block of settings rows. */
@Composable
fun SettingsSection(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(title.uppercase(), style = OverlineStyle, color = FetchPalette.TextMuted, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FetchPalette.Surface)
        ) { content() }
    }
}

/** One row: a title, an optional description and a control on the right. */
@Composable
fun SettingsRow(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The row Settings search jumped to scrolls into view and lights up for a moment.
    val highlighted = settingsRowHighlighted(title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .settingsHighlight(highlighted)
            .background(if (hovered && onClick != null) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(interaction)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else Modifier
            )
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = FetchPalette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (description != null) {
                Text(description, color = FetchPalette.TextMuted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
fun SettingsToggle(title: String, description: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(title, description, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FetchPalette.OnAccent,
                checkedTrackColor = FetchPalette.Accent,
                uncheckedThumbColor = FetchPalette.TextSecondary,
                uncheckedTrackColor = FetchPalette.SurfaceHigh,
                uncheckedBorderColor = FetchPalette.Outline
            )
        )
    }
}

/** A row whose value is picked from a menu. */
@Composable
fun <T> SettingsChoice(
    title: String,
    description: String? = null,
    selected: T,
    options: List<DropdownOption<T>>,
    onSelect: (T) -> Unit
) {
    SettingsRow(title, description) {
        FilterDropdown(label = "", selected = selected, options = options, onSelect = onSelect)
    }
}

/** Toggle chips for picking several values; [ordered] numbers them in the order picked. */
@Composable
fun <T> ChipPicker(
    options: List<DropdownOption<T>>,
    selected: List<T>,
    onChange: (List<T>) -> Unit,
    ordered: Boolean = false
) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).padding(bottom = 12.dp)
    ) {
        options.forEach { option ->
            val index = selected.indexOf(option.value)
            val on = index >= 0
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Text(
                if (on && ordered) "${index + 1}. ${option.label}" else option.label,
                fontSize = 14.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) FetchPalette.OnAccent else FetchPalette.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            on -> FetchPalette.Accent
                            hovered -> Color.White.copy(alpha = 0.16f)
                            else -> Color.White.copy(alpha = 0.07f)
                        }
                    )
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) {
                        onChange(if (on) selected - option.value else selected + option.value)
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}
