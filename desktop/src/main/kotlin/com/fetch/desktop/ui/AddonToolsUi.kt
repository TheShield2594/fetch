package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.addons.AddonHealth
import com.fetch.desktop.addons.AddonHealthSort
import com.fetch.desktop.addons.AddonHealthTracker
import com.fetch.desktop.addons.HomeCatalogOrder
import com.fetch.desktop.addons.sortByHealth
import com.fetch.tv.domain.model.Addon

/** Addons' "Addon health" and "Catalog order", as the TV's addon manager offers them. */
@Composable
fun AddonToolsRow(state: AppState, catalogOrder: Boolean = true) {
    var health by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Addon health", onClick = { health = true })
        if (catalogOrder) SecondaryButton("Catalog order", onClick = { order = true }, enabled = state.rows.any { it.filter == null })
    }
    if (health) AddonHealthDialog(
        state.addons.toList(),
        onDismiss = { health = false },
        // The TV's "Disable": only for your own addons, not the main profile's.
        onDisable = if (catalogOrder) ({ addon -> state.setAddonEnabled(addon, false) }) else null
    )
    if (order) CatalogOrderDialog(state, onDismiss = { order = false })
}

private fun latencyLabel(ms: Long) = "%.1f s".format(ms / 1000.0)

private fun relativeTime(atMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - atMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/**
 * Every installed addon's recent health (the TV's `AddonHealthPanel`): median response time,
 * share of failures, last success and last error over its last requests, sortable, kept across
 * restarts. Clear history asks twice, as on TV.
 */
@Composable
fun AddonHealthDialog(
    addons: List<Addon>,
    onDismiss: () -> Unit,
    onDisable: ((Addon) -> Unit)? = null,
    tracker: AddonHealthTracker = AddonHealthTracker.shared
) {
    var sort by remember { mutableStateOf(AddonHealthSort.entries.first()) }
    var clearArmed by remember { mutableStateOf(false) }
    val health = tracker.health
    val rows = sortByHealth(addons, { it.displayName }, { health[it.displayName] }, sort)
    // Health is kept by name: when two addons share one, it can't say which failed, so neither offers Disable.
    val sharedNames = addons.groupingBy { it.displayName }.eachCount().filterValues { it > 1 }.keys
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Addon health", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(720.dp)) {
                Text("Over each addon’s last ${AddonHealth.WINDOW_SIZE} requests for streams, catalogs and details", color = FetchPalette.TextSecondary, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort by", color = FetchPalette.TextMuted, fontSize = 14.sp)
                    ChipPicker(
                        options = AddonHealthSort.entries.map { DropdownOption(it, it.label) },
                        selected = listOf(sort),
                        onChange = { picked -> (picked - sort).firstOrNull()?.let { sort = it } }
                    )
                }
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rows.isEmpty()) Text("No addons installed.", color = FetchPalette.TextMuted)
                    rows.forEach { addon ->
                        HealthRow(
                            addon.displayName,
                            health[addon.displayName]?.takeIf { it.requests > 0 },
                            enabled = addon.enabled,
                            sharedName = addon.displayName in sharedNames,
                            onDisable = onDisable?.takeIf { addon.displayName !in sharedNames }?.let { disable -> { disable(addon) } }
                        )
                    }
                }
            }
        },
        confirmButton = { PrimaryButton("Close", icon = null, onClick = onDismiss) },
        dismissButton = {
            SecondaryButton(if (clearArmed) "Click again to clear" else "Clear history", onClick = {
                if (clearArmed) {
                    tracker.clear()
                    clearArmed = false
                } else {
                    clearArmed = true
                }
            })
        }
    )
}

@Composable
private fun HealthRow(name: String, health: AddonHealth?, enabled: Boolean = true, sharedName: Boolean = false, onDisable: (() -> Unit)? = null) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FetchPalette.Background).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(name + if (enabled) "" else " · Off", color = if (enabled) Color.White else FetchPalette.TextMuted, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (health == null) {
            Text("No requests yet", color = FetchPalette.TextSecondary, fontSize = 13.sp)
            return@Column
        }
        val failurePercent = (health.failureRate * 100).toInt()
        Text(
            listOfNotNull(
                health.p50LatencyMs?.let { "Median ${latencyLabel(it)}" },
                "$failurePercent% failed",
                health.lastSuccessAtMs?.let { "Last success ${relativeTime(it)}" } ?: "No success yet"
            ).joinToString(" · "),
            color = if (failurePercent >= 50) FetchPalette.Error else FetchPalette.TextSecondary,
            fontSize = 13.sp
        )
        // A record shared by two addons of one name can't say which failed, so it suggests nothing.
        if (health.shouldSuggestDisabling && enabled && !sharedName) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Failed ${health.consecutiveFailures} times in a row · " + if (onDisable != null) "consider turning it off" else "consider removing it",
                    color = FetchPalette.Error,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                if (onDisable != null) SecondaryButton("Disable", onClick = onDisable)
            }
        }
        health.lastError?.let { Text("Last error: $it", color = FetchPalette.TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * The TV's Catalog order: every addon catalog Home can show, in Home's order, moved one place at
 * a time and shown or hidden. Saved in the TV's keys and synced with it through the account.
 */
@Composable
fun CatalogOrderDialog(state: AppState, onDismiss: () -> Unit) {
    val settings = state.settings
    val catalogs = state.rows.filter { it.filter == null }.distinctBy { it.ordered.tvKey }
    val ordered = HomeCatalogOrder.ordered(catalogs, settings, { it.key }, { it.ordered.tvKey })
    val all = catalogs.map { it.ordered }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Catalog order", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(680.dp)) {
                Text("The order of your addon catalogs on Home. Synced with your other devices.", color = FetchPalette.TextSecondary, fontSize = 14.sp)
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ordered.forEachIndexed { index, row ->
                        val shown = !HomeCatalogOrder.isDisabled(settings, row.ordered)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FetchPalette.Background).padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("${index + 1}", color = FetchPalette.TextMuted, fontSize = 13.sp, modifier = Modifier.width(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.title(true), color = if (shown) Color.White else FetchPalette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(row.addon.displayName + if (shown) "" else " · Hidden", color = FetchPalette.TextMuted, fontSize = 12.sp, maxLines = 1)
                            }
                            CircleIconButton(FetchIcons.ArrowUp, "Move up", onClick = {
                                state.updateSettings { HomeCatalogOrder.move(it, all, row.ordered, -1) }
                            }, size = 34.dp, enabled = index > 0)
                            CircleIconButton(FetchIcons.ArrowDown, "Move down", onClick = {
                                state.updateSettings { HomeCatalogOrder.move(it, all, row.ordered, 1) }
                            }, size = 34.dp, enabled = index < ordered.lastIndex)
                            SecondaryButton(if (shown) "Hide" else "Show", onClick = {
                                state.updateSettings { HomeCatalogOrder.setEnabled(it, row.ordered, !shown) }
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { PrimaryButton("Done", icon = null, onClick = onDismiss) },
        dismissButton = { SecondaryButton("Reset order", onClick = { state.updateSettings(HomeCatalogOrder::reset) }) }
    )
}
