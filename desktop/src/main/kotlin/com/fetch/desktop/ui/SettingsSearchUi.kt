package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.SettingsPage
import kotlinx.coroutines.delay

/**
 * Settings search's state: what's typed, the row a result opened (lit up on its page for a few
 * seconds), and Ctrl+F's requests to focus the field. One window, so one of each.
 */
object SettingsSearchState {
    var query by mutableStateOf("")
    var highlight by mutableStateOf<Pair<SettingsPage, String>?>(null)
    var focusRequests by mutableIntStateOf(0)
        private set

    /** Ctrl+F while in Settings. */
    fun requestFocus() {
        focusRequests++
    }

    fun open(state: AppState, entry: SettingsSearchEntry) {
        query = ""
        highlight = entry.page to entry.title
        state.navigate(Screen.Settings(entry.page))
    }
}

/** The title of the row to light up on the page showing, if search opened it. */
val LocalSettingsHighlight = compositionLocalOf<String?> { null }

@Composable
internal fun settingsRowHighlighted(title: String): Boolean =
    LocalSettingsHighlight.current?.equals(title, ignoreCase = true) == true

/** Scrolls a highlighted row into view and tints it with the accent, fading after a moment. */
internal fun Modifier.settingsHighlight(on: Boolean): Modifier = if (!on) this else composed {
    val requester = remember { BringIntoViewRequester() }
    var lit by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(if (lit) 0.22f else 0f, tween(600))
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { requester.bringIntoView() }
        delay(2_200)
        lit = false
    }
    bringIntoViewRequester(requester).background(FetchPalette.Accent.copy(alpha = alpha))
}

/** The search field at the top of the Settings rail. Escape clears it. */
@Composable
internal fun SettingsSearchField(modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val requests = SettingsSearchState.focusRequests
    LaunchedEffect(requests) { if (requests > 0) runCatching { focus.requestFocus() } }
    val query = SettingsSearchState.query
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FetchPalette.Background)
            .border(1.dp, if (query.isNotEmpty()) FetchPalette.Accent else FetchPalette.Outline, RoundedCornerShape(10.dp))
            .height(40.dp)
            .padding(horizontal = 10.dp)
    ) {
        Icon(FetchIcons.Search, contentDescription = null, tint = FetchPalette.TextMuted, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = query,
            onValueChange = { SettingsSearchState.query = it.take(80) },
            singleLine = true,
            textStyle = TextStyle(color = FetchPalette.TextPrimary, fontSize = 14.sp, fontFamily = fontFamilyFor(FetchPalette.Font)),
            cursorBrush = SolidColor(FetchPalette.Accent),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) Text(tr("Search settings"), color = FetchPalette.TextMuted, fontSize = 14.sp)
                    field()
                }
            },
            modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && SettingsSearchState.query.isNotEmpty()) {
                    SettingsSearchState.query = ""
                    true
                } else false
            }
        )
    }
}

/** Search results in place of the page: each opens its page with the row lit up. */
@Composable
internal fun SettingsSearchResults(state: AppState, results: List<SettingsSearchEntry>) {
    PageHeader("Search", subtitle = if (results.isEmpty()) "No settings match “${SettingsSearchState.query.trim()}”." else null)
    if (results.isEmpty()) return
    SettingsSection(null) {
        results.forEach { entry ->
            SearchResultRow(entry, onClick = { SettingsSearchState.open(state, entry) })
        }
    }
}

@Composable
private fun SearchResultRow(entry: SettingsSearchEntry, onClick: () -> Unit) {
    // Not a SettingsRow, so a result never lights up as a search target itself.
    val where = listOfNotNull(entry.page.railPage.group, entry.page.railPage.label, entry.page.label.takeIf { entry.page.parent != null }, entry.section)
        .distinct()
        .joinToString(" › ") { tr(it) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr(entry.title), color = FetchPalette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(where, color = FetchPalette.TextMuted, fontSize = 13.sp)
        }
        Icon(FetchIcons.ChevronRight, contentDescription = null, tint = FetchPalette.TextMuted)
    }
}
