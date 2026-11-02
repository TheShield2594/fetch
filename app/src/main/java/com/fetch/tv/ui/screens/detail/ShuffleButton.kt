package com.fetch.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.theme.FetchTheme

@Composable
internal fun ShuffleButton(
    active: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    upFocusRequester: FocusRequester = FocusRequester.Cancel
) {
    val busyDescription = stringResource(R.string.shuffle_pending)
    // Stays focusable while pending so focus doesn't jump; the dimmed label and the state
    // description say the press was taken and is still working.
    DetailButton(
        onClick = { if (!pending) onClick() },
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { up = upFocusRequester }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { if (pending) stateDescription = busyDescription },
        contentPadding = PaddingValues(horizontal = FetchTheme.spacing.lg, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.alpha(if (pending) 0.5f else 1f),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (active) Icons.Default.Stop else Icons.Default.Shuffle, null, Modifier.size(18.dp))
            Text(stringResource(if (active) R.string.shuffle_stop else R.string.random_episode_title))
        }
    }
}
