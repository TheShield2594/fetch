package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.badges.ImportedBadgeSource
import com.fetch.desktop.badges.StreamBadgesState
import com.fetch.tv.core.streams.STREAM_BADGE_IMPORT_LIMIT
import com.fetch.tv.core.streams.StreamBadgePlacement
import com.fetch.tv.domain.model.StreamBadge
import java.text.DateFormat
import java.util.Date
import kotlin.math.round

private val BadgeShape = RoundedCornerShape(5.dp)

/**
 * A stream's badges, like the TV app's chips: image badges from the Fusion import, text labels
 * for badges without an image, then the file size when [fileSizeBytes] is given (pass it only
 * when Size badges is on). Wraps onto further lines when there are many.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamBadgeRow(badges: List<StreamBadge>, modifier: Modifier = Modifier, fileSizeBytes: Long? = null) {
    if (badges.isEmpty() && fileSizeBytes == null) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        badges.forEach { badge ->
            if (badge.imageURL.isNotBlank()) ImageBadge(badge) else TextBadge(badge)
        }
        if (fileSizeBytes != null && fileSizeBytes > 0) SizeBadge(fileSizeBytes)
    }
}

@Composable
private fun ImageBadge(badge: StreamBadge) {
    val background = remember(badge.tagColor, badge.tagStyle) {
        badge.tagColor.toBadgeColorOrNull()?.takeIf { badge.tagStyle.equals("filled", ignoreCase = true) }
    }
    val outline = remember(badge.borderColor) { badge.borderColor.toBadgeColorOrNull() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(22.dp)
            .then(if (background != null) Modifier.background(background, BadgeShape) else Modifier)
            .then(if (outline != null) Modifier.border(1.dp, outline, BadgeShape) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        AsyncImage(
            model = badge.imageURL,
            contentDescription = badge.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(18.dp).widthIn(min = 34.dp, max = 92.dp).clip(BadgeShape)
        )
    }
}

@Composable
private fun TextBadge(badge: StreamBadge) {
    val tag = remember(badge.tagColor) { badge.tagColor.toBadgeColorOrNull() }
    val filled = tag != null && !badge.tagStyle.equals("outlined", ignoreCase = true)
    val outline = remember(badge.borderColor) { badge.borderColor.toBadgeColorOrNull() } ?: tag ?: FetchPalette.TextMuted
    val textColor = remember(badge.textColor) { badge.textColor.toBadgeColorOrNull() }
        ?: if (filled) Color.White else FetchPalette.TextPrimary
    Text(
        badge.name,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = textColor,
        maxLines = 1,
        modifier = Modifier
            .height(22.dp)
            .then(if (filled && tag != null) Modifier.background(tag, BadgeShape) else Modifier)
            .border(1.dp, outline, BadgeShape)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun SizeBadge(bytes: Long) {
    val label = remember(bytes) { "SIZE ${formatBadgeSize(bytes)}" }
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .height(22.dp)
            .background(Color(0xFF0A0A0A), BadgeShape)
            .border(1.dp, FetchPalette.Outline, BadgeShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** "1.4 GB" or "700 MB", as the TV app's size badge. */
fun formatBadgeSize(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        "${round(gib * 10.0) / 10.0} GB"
    } else {
        "${round(bytes.toDouble() / (1024.0 * 1024.0)).toInt()} MB"
    }
}

private fun String.toBadgeColorOrNull(): Color? {
    val hex = trim().removePrefix("#")
    val argb = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return argb.toLongOrNull(16)?.let { Color(it) }
}

// --- Settings page ---

/**
 * Settings › Stream badges: the Fusion badge URLs and the badge display settings. Sits inside the
 * settings page's scrolling column, so it doesn't scroll itself.
 */
@Composable
fun StreamBadgesSettingsPage(state: StreamBadgesState, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(FetchIcons.Back, contentDescription = "Back", onClick = onBack, size = 40.dp)
            PageHeader(
                "Stream badges",
                "Import up to $STREAM_BADGE_IMPORT_LIMIT Fusion-style stream badge JSON URLs. " +
                    "Each URL can be updated or deleted separately."
            )
        }

        SettingsSection("Fusion Style") {
            SettingsToggle(
                "Size badges",
                "Show file size badges in stream results and player source panels.",
                checked = state.showFileSizeBadges,
                onChange = state::setShowFileSizeBadges
            )
            SettingsChoice(
                "Badge position",
                "Choose whether Fusion and size badges appear above or below stream cards.",
                selected = state.badgePlacement,
                options = listOf(
                    DropdownOption(StreamBadgePlacement.BOTTOM, "Bottom"),
                    DropdownOption(StreamBadgePlacement.TOP, "Top")
                ),
                onSelect = state::setBadgePlacement
            )
            SettingsToggle(
                "Addon logo",
                "Show addon logo and name next to stream sources.",
                checked = state.showAddonLogo,
                onChange = state::setShowAddonLogo
            )
        }

        SettingsSection("Fusion badge URLs") {
            val rules = state.rules
            SettingsRow(
                "Fusion badge URLs",
                if (rules.hasImport) {
                    "${rules.imports.size}/$STREAM_BADGE_IMPORT_LIMIT URLs, ${rules.enabledFilterCount} active Fusion badges"
                } else {
                    "No Fusion badge URLs imported."
                }
            ) {
                if (rules.hasImport) {
                    SecondaryButton(
                        if (state.refreshing) "Refreshing…" else "Refresh",
                        onClick = { state.refreshAll() },
                        enabled = !state.refreshing && !state.importing
                    )
                }
            }
            state.sources.forEach { source ->
                BadgeSourceRow(
                    source,
                    onActivate = { state.setActive(source.url) },
                    onRemove = { state.remove(source.url) }
                )
            }
            AddBadgeUrl(state)
        }
    }
}

@Composable
private fun BadgeSourceRow(source: ImportedBadgeSource, onActivate: () -> Unit, onRemove: () -> Unit) {
    val status = if (source.isActive) "Active" else "Inactive"
    val updated = remember(source.lastUpdated) {
        source.lastUpdated.takeIf { it > 0 }?.let {
            " · Updated " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }.orEmpty()
    }
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            source.name,
            "${source.url}\n$status, ${source.enabledBadgeCount} enabled badges, ${source.groupCount} groups$updated",
            onClick = if (source.isActive) null else onActivate
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (source.isActive) SpecChip("Active", filled = true)
                CircleIconButton(FetchIcons.Delete, contentDescription = "Delete badge URL", onClick = onRemove, size = 36.dp)
            }
        }
        val preview = remember(source.badges) { source.badges.filter { it.imageURL.isNotBlank() }.take(24) }
        if (preview.isNotEmpty()) {
            StreamBadgeRow(preview, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp))
        }
    }
}

@Composable
private fun AddBadgeUrl(state: StreamBadgesState) {
    var url by rememberSaveable { mutableStateOf("") }
    val canAdd = state.canAddMore
    val submit = {
        if (url.isNotBlank() && !state.importing) {
            val entered = url
            state.add(entered)?.invokeOnCompletion { if (state.error == null) url = "" }
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(tr("Fusion badge JSON URL"), color = FetchPalette.TextSecondary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = url,
                onValueChange = {
                    url = it
                    if (state.error != null) state.clearError()
                },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(FetchPalette.Accent),
                decorationBox = { field ->
                    Box {
                        if (url.isEmpty()) {
                            Text(
                                if (canAdd) "https://example.com/badges.json" else tr("Delete a URL to import another"),
                                color = FetchPalette.TextMuted,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        field()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(FetchPalette.Background)
                    .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp))
                    .padding(12.dp)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                            submit(); true
                        } else false
                    }
            )
            PrimaryButton(
                if (state.importing) "Importing…" else "Add badge URL",
                onClick = submit,
                icon = FetchIcons.Add,
                enabled = url.isNotBlank() && !state.importing
            )
        }
        state.error?.let { Text(it, color = FetchPalette.Error, fontSize = 13.sp) }
    }
}
