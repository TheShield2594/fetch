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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.components.FetchCardDefaults
import com.fetch.tv.ui.components.LightweightCard
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The last card of a Home catalog row: opens the row's full grid, so the end of a row leads
 * somewhere instead of stopping. Sized like the row's cards so the row keeps its rhythm, and
 * focused like them too: the same card, scale, ring and resting edge as its neighbours (#819).
 */
@Composable
internal fun ModernSeeAllCard(
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when the row's cards widen into their backdrop on focus instead of growing. */
    expandEnabled: Boolean = false
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val isFastScrolling = LocalFastScrollActive.current.value
    val restingBorder = FetchCardDefaults.restingBorder(shape)
    LightweightCard(
        onClick = onClick,
        shape = shape,
        containerColor = FetchTheme.colors.BackgroundCard,
        modifier = modifier
            .width(width)
            .height(height),
        focusedBorder = if (isFastScrolling) restingBorder else FetchCardDefaults.focusedBorder(shape),
        restingBorder = restingBorder,
        focusedScale = if (expandEnabled || isFastScrolling) 1f else FetchTheme.focus.cardScale,
        focusedShadow = FetchTheme.focus.cardShadow
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
