package com.fetch.tv.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.theme.FetchTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayManualOverrideDialog(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    showPlayManually: Boolean = true,
    onPlayManually: () -> Unit,
    showStartFromBeginning: Boolean = false,
    onStartFromBeginning: () -> Unit = {}
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    FetchDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle
    ) {
        if (showPlayManually) {
            Button(
                onClick = onPlayManually,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(primaryFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.play_manually))
            }
        }

        if (showStartFromBeginning) {
            Button(
                onClick = onStartFromBeginning,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!showPlayManually) Modifier.focusRequester(primaryFocusRequester) else Modifier),
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.cw_action_start_from_beginning))
            }
        }
    }
}
