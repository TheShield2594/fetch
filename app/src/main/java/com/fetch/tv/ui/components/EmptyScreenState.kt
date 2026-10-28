package com.fetch.tv.ui.components

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** The one thing an empty or error screen offers to do next, such as "Add an addon" or "Retry". */
data class StateAction(
    val label: String,
    val onClick: () -> Unit
)

/**
 * A screen or section with nothing to show. Give it an [action] whenever there's an obvious next
 * step, so the screen is never a dead end.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EmptyScreenState(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    height: Dp = 400.dp,
    action: StateAction? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = FetchTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = FetchTheme.colors.TextTertiary
            )
            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = FetchTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )

        if (subtitle != null) {
            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }

        if (action != null) {
            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
            StateActionButton(action)
        }
    }
}

/** The button under an empty or error state. Every state uses this one, so they all look alike. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StateActionButton(action: StateAction, modifier: Modifier = Modifier) {
    Button(
        onClick = action.onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            contentColor = FetchTheme.colors.TextPrimary,
            focusedContainerColor = FetchTheme.colors.FocusBackground,
            focusedContentColor = FetchTheme.colors.Primary
        ),
        shape = ButtonDefaults.shape(FetchTheme.shapes.button)
    ) {
        Text(action.label)
    }
}
