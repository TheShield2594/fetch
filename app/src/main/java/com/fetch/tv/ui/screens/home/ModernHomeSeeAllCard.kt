package com.fetch.tv.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.components.FetchCardDefaults
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The last card of a Home catalog row: opens the row's full grid, so the end of a row leads
 * somewhere instead of stopping. Sized like the row's cards so the row keeps its rhythm.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ModernSeeAllCard(
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    Card(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .height(height),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchCardDefaults.FocusedBorderWidth),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(focusedScale = FetchCardDefaults.FocusedScale)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(FetchTheme.spacing.xxl),
                    tint = FetchTheme.colors.TextSecondary
                )
                Text(
                    text = stringResource(R.string.action_see_all),
                    style = MaterialTheme.typography.titleSmall,
                    color = FetchTheme.colors.TextSecondary
                )
            }
        }
    }
}
