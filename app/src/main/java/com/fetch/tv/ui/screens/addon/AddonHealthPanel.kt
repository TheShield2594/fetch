package com.fetch.tv.ui.screens.addon

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.streams.AddonHealth
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.screens.settings.SettingsChoiceChip
import com.fetch.tv.ui.theme.FetchTheme

/** Chip order in the panel; the first is the default. */
enum class AddonHealthSort { FAILURE_RATE, LATENCY, LAST_SUCCESS, NAME }

data class AddonHealthRow(val addon: Addon, val health: AddonHealth?)

/**
 * Orders the panel. Everything but [AddonHealthSort.NAME] puts the worst addon first: slowest,
 * most failing, longest since it last answered. Addons without history go last.
 */
internal fun sortAddonHealthRows(rows: List<AddonHealthRow>, sort: AddonHealthSort): List<AddonHealthRow> {
    val byName = compareBy<AddonHealthRow, String>(String.CASE_INSENSITIVE_ORDER) { it.addon.displayName }
    val hasHistory = compareBy<AddonHealthRow> { (it.health?.requests ?: 0) == 0 }
    return when (sort) {
        AddonHealthSort.NAME -> rows.sortedWith(byName)
        AddonHealthSort.LATENCY -> rows.sortedWith(
            hasHistory.thenByDescending { it.health?.p50LatencyMs ?: -1L }.then(byName)
        )
        AddonHealthSort.FAILURE_RATE -> rows.sortedWith(
            hasHistory.thenByDescending { it.health?.failureRate ?: 0f }.then(byName)
        )
        AddonHealthSort.LAST_SUCCESS -> rows.sortedWith(
            hasHistory.thenBy { it.health?.lastSuccessAtMs ?: Long.MIN_VALUE }.then(byName)
        )
    }
}

/** Every installed addon's recent health, sortable, with a way to disable one that keeps failing. */
@Composable
internal fun AddonHealthPanel(
    addons: List<Addon>,
    health: Map<String, AddonHealth>,
    canDisable: Boolean,
    onDisable: (Addon) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    var sort by remember { mutableStateOf(AddonHealthSort.entries.first()) }
    val rows = remember(addons, health, sort) {
        sortAddonHealthRows(addons.map { AddonHealthRow(it, health[it.displayName]) }, sort)
    }
    val firstChipFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstChipFocus.requestFocus() }
    }

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.addon_health_entry_title),
        subtitle = stringResource(R.string.addon_health_panel_subtitle, AddonHealth.WINDOW_SIZE),
        width = 760.dp,
        usePlatformDefaultWidth = false,
        suppressFirstKeyUp = false
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.addon_health_sort_label),
                style = MaterialTheme.typography.labelMedium,
                color = FetchTheme.colors.TextSecondary
            )
            AddonHealthSort.entries.forEachIndexed { index, option ->
                SettingsChoiceChip(
                    label = stringResource(option.labelRes()),
                    selected = sort == option,
                    onClick = { sort = option },
                    modifier = if (index == 0) Modifier.focusRequester(firstChipFocus) else Modifier
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                // Leaves room for the chips and the Clear/Close row under FetchDialog's 492dp cap.
                .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            items(rows, key = { it.addon.baseUrl }) { row ->
                AddonHealthPanelRow(
                    row = row,
                    canDisable = canDisable,
                    onDisable = { onDisable(row.addon) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md, Alignment.End)
        ) {
            Button(onClick = onClearHistory) {
                Text(stringResource(R.string.addon_health_clear))
            }
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.addon_health_close))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddonHealthPanelRow(
    row: AddonHealthRow,
    canDisable: Boolean,
    onDisable: () -> Unit
) {
    val health = row.health?.takeIf { it.requests > 0 }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Focusable so the list scrolls with the D-pad; there's nothing to open.
        Surface(
            onClick = {},
            modifier = Modifier.weight(1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                focusedContainerColor = FetchTheme.colors.FocusBackground
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = RoundedCornerShape(FetchTheme.radii.sm)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.sm)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
            ) {
                Text(
                    text = row.addon.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Content
                    ),
                    color = FetchTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (health == null) {
                    Text(
                        text = stringResource(R.string.addon_health_no_requests),
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextSecondary
                    )
                } else {
                    AddonHealthStats(health)
                }
            }
        }
        if (canDisable && row.addon.enabled && health?.shouldSuggestDisabling == true) {
            Button(onClick = onDisable) {
                Text(stringResource(R.string.addon_health_disable))
            }
        }
    }
}

@Composable
private fun AddonHealthStats(health: AddonHealth) {
    val median = health.p50LatencyMs?.let {
        stringResource(R.string.addon_health_median, stringResource(R.string.addon_health_latency, it / 1000.0))
    }
    val failurePercent = (health.failureRate * 100).toInt()
    val lastSuccess = health.lastSuccessAtMs?.let {
        stringResource(R.string.addon_health_last_success, relativeTime(it))
    } ?: stringResource(R.string.addon_health_last_success_never)
    Text(
        text = listOfNotNull(
            median,
            stringResource(R.string.addon_health_failure_rate, failurePercent),
            lastSuccess
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = if (failurePercent >= 50) FetchTheme.colors.Error else FetchTheme.colors.TextSecondary
    )
    if (health.shouldSuggestDisabling) {
        Text(
            text = stringResource(R.string.addon_health_failed_in_a_row, health.consecutiveFailures),
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.Error
        )
    }
    health.lastError?.let { error ->
        Text(
            text = stringResource(R.string.addon_health_last_error, error),
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun relativeTime(atMs: Long): String =
    DateUtils.getRelativeTimeSpanString(
        atMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

private fun AddonHealthSort.labelRes(): Int = when (this) {
    AddonHealthSort.NAME -> R.string.addon_health_sort_name
    AddonHealthSort.LATENCY -> R.string.addon_health_sort_latency
    AddonHealthSort.FAILURE_RATE -> R.string.addon_health_sort_failures
    AddonHealthSort.LAST_SUCCESS -> R.string.addon_health_sort_last_success
}
