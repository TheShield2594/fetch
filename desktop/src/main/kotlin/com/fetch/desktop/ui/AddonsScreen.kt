package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.tv.core.addon.isConfigurable
import com.fetch.tv.domain.model.Addon

@Composable
fun AddonsScreen(state: AppState) {
    var url by remember { mutableStateOf("") }
    val install = {
        if (url.isNotBlank()) {
            state.installAddon(url)
            url = ""
        }
    }
    val listState = rememberLazyListState()
    ScrollbarBox(listState, Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = PageGutter, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().widthIn(max = 1100.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    "Streams and catalogs come from Stremio addons. Paste an addon's link (it usually ends in manifest.json). " +
                        "Debrid services are signed in on the addon's own setup page.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        placeholder = { Text("https://example.com/manifest.json") },
                        modifier = Modifier.weight(1f).onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                install(); true
                            } else false
                        }
                    )
                    PrimaryButton("Install", onClick = install, icon = null, enabled = url.isNotBlank())
                }
                AddonToolsRow(state)
            }
        }
        if (state.addons.isEmpty() && state.unreachableAddons.isEmpty() && !state.addonsLoading) {
            item { Text("No addons installed. Add one to get started.", color = FetchPalette.TextMuted) }
        }
        itemsIndexed(state.addons, key = { _, addon -> addon.baseUrl }) { index, addon ->
            AddonCard(
                addon = addon,
                canMoveUp = index > 0,
                canMoveDown = index < state.addons.lastIndex,
                onMove = { delta -> state.moveAddon(addon, delta) },
                onRemove = { state.removeAddon(addon) },
                onConfigure = if (addon.isConfigurable) ({ state.configureAddon(addon) }) else null
            )
        }
        items(state.unreachableAddons, key = { "unreachable|${it.first}" }) { (addonUrl, error) ->
            Card(colors = CardDefaults.cardColors(containerColor = FetchPalette.Surface), shape = RoundedCornerShape(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(addonUrl, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Didn't answer at startup ($error). It stays installed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    CircleIconButton(FetchIcons.Delete, "Remove", onClick = { state.forgetUnreachable(addonUrl) }, size = 40.dp)
                }
            }
        }
    }
    }
}

@Composable
private fun AddonCard(
    addon: Addon,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onConfigure: (() -> Unit)?
) {
    Card(colors = CardDefaults.cardColors(containerColor = FetchPalette.Surface), shape = RoundedCornerShape(14.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (addon.logo.isNullOrBlank()) {
                    Text(addon.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                } else {
                    AsyncImage(model = addon.logo, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${addon.displayName}  ${addon.version}", style = MaterialTheme.typography.titleMedium)
                addon.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    addon.resources.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onConfigure != null) SecondaryButton("Configure", onClick = onConfigure, icon = FetchIcons.Settings)
            CircleIconButton(FetchIcons.ArrowUp, "Move up", onClick = { onMove(-1) }, size = 40.dp, enabled = canMoveUp)
            CircleIconButton(FetchIcons.ArrowDown, "Move down", onClick = { onMove(1) }, size = 40.dp, enabled = canMoveDown)
            CircleIconButton(FetchIcons.Delete, "Remove", onClick = onRemove, size = 40.dp)
        }
    }
}
