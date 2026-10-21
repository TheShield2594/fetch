package com.fetch.tv.ui.screens.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.streams.ranking.PreferenceSuggestion
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Offered a few seconds into playback after the user picked a stream over Best match's
 * choice: one press makes Best match prefer what set their pick apart.
 */
@Composable
internal fun PreferNextTimePrompt(
    suggestions: List<PreferenceSuggestion>,
    onPrefer: (PreferenceSuggestion) -> Unit,
    onDismiss: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(suggestions) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(FetchTheme.radii.lg))
            .padding(FetchTheme.spacing.lg)
            .focusGroup()
            .onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.prefer_next_time_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
            suggestions.forEachIndexed { index, suggestion ->
                PlayerOverlayButton(
                    text = suggestion.label(context),
                    onClick = { onPrefer(suggestion) },
                    primary = index == 0,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
            PlayerOverlayButton(
                text = stringResource(R.string.prefer_next_time_not_now),
                onClick = onDismiss,
                primary = false
            )
        }
    }
}

internal fun PreferenceSuggestion.label(context: Context): String = when (this) {
    is PreferenceSuggestion.ReleaseGroup -> context.getString(R.string.prefer_next_time_group, group)
    is PreferenceSuggestion.Language -> context.getString(R.string.prefer_next_time_language, language.label)
    is PreferenceSuggestion.Resolution -> resolution.label
    is PreferenceSuggestion.SizeUpTo -> context.getString(R.string.prefer_next_time_size, gb.toInt())
}
