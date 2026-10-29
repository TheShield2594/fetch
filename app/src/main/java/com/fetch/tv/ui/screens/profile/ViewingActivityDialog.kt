package com.fetch.tv.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme
import java.text.DateFormat
import java.util.Date

/** What [profile] watched and when, newest first. [entries] is null while it loads. */
@Composable
internal fun ViewingActivityDialog(
    profile: UserProfile,
    entries: List<ViewingActivityEntry>?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    FetchDialog(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.profile_viewing_activity_title, profile.name),
        width = 640.dp
    ) {
        when {
            entries == null -> ViewingActivityMessage(stringResource(R.string.profile_viewing_activity_loading))
            entries.isEmpty() -> ViewingActivityMessage(stringResource(R.string.profile_viewing_activity_empty))
            else -> {
                val firstRow = remember(profile.id) { FocusRequester() }
                LaunchedEffect(profile.id) {
                    repeat(2) { withFrameNanos { } }
                    runCatching { firstRow.requestFocus() }
                }
                val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
                // Fits under FetchDialog's 492dp cap with the padding, a two-line title (long profile
                // name) and the gap at 1.3x text, so the last row isn't clipped below the dialog edge.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
                ) {
                    items(
                        items = entries,
                        key = { "${it.contentId}:${it.season}:${it.episode}" }
                    ) { entry ->
                        ViewingActivityRow(
                            entry = entry,
                            date = dateFormat.format(Date(entry.watchedAt)),
                            modifier = if (entry === entries.first()) Modifier.focusRequester(firstRow) else Modifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewingActivityMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = FetchTheme.colors.TextSecondary,
        // Focusable so the dialog has somewhere for focus to land, and Back still closes it.
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
            .padding(vertical = FetchTheme.spacing.md)
    )
}

@Composable
private fun ViewingActivityRow(
    entry: ViewingActivityEntry,
    date: String,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FetchTheme.radii.md)
    // Rows only show; they are focusable so the D-pad can scroll the list.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) FetchTheme.colors.FocusBackground else Color.Transparent, shape)
            .border(
                width = FetchTheme.spacing.hairline,
                color = if (focused) FetchTheme.colors.Secondary else Color.Transparent,
                shape = shape
            )
            .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = FetchTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val episodeLine = viewingActivityEpisodeLine(entry)
            if (episodeLine != null) {
                Text(
                    text = episodeLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = date,
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextSecondary
            )
            Text(
                text = entry.percentWatched?.let { stringResource(R.string.profile_viewing_activity_partly, it) }
                    ?: stringResource(R.string.profile_viewing_activity_finished),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextTertiary
            )
        }
    }
}

@Composable
private fun viewingActivityEpisodeLine(entry: ViewingActivityEntry): String? {
    val season = entry.season ?: return null
    val episode = entry.episode ?: return null
    val label = stringResource(R.string.stream_episode_label, season, episode)
    return entry.episodeTitle?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label
}
