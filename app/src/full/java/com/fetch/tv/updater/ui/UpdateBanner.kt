@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.fetch.tv.updater.ui

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.ThemeColors
import com.fetch.tv.ui.theme.accentBrush
import com.fetch.tv.updater.UpdateUiState
import com.fetch.tv.updater.model.AppUpdate

@Composable
internal fun UpdateBanner(
    state: UpdateUiState,
    update: AppUpdate,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onShowReleaseNotes: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val targetProgress = (if (state.isDownloading) state.downloadProgress ?: 0f else 0f)
        .coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 180),
        label = "updateBannerProgress"
    )
    val containerColor = FetchTheme.colors.BackgroundElevated
    val isWhiteTheme = FetchTheme.currentTheme == AppTheme.WHITE
    val progressBrush = ThemeColors.getColorPalette(FetchTheme.currentTheme).accentBrush()
    val progressAlpha = if (isWhiteTheme) 0.18f else 1f
    val dividerColor = FetchTheme.colors.Border
    val subtitle = when {
        state.errorMessage != null -> state.errorMessage
        state.isDownloading && state.downloadProgress != null -> stringResource(
            R.string.update_downloading_progress,
            (state.downloadProgress * 100).toInt().coerceIn(0, 100)
        )
        state.isDownloading -> stringResource(R.string.update_preparing_download)
        state.downloadedApkPath != null -> stringResource(R.string.update_download_complete)
        else -> stringResource(R.string.update_available)
    }
    val updateLabel = listOfNotNull(
        update.tag,
        update.assetSizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    ).joinToString(separator = " • ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(containerColor)
                if (progress > 0f) {
                    drawRect(
                        brush = progressBrush,
                        alpha = progressAlpha,
                        size = Size(width = size.width * progress, height = size.height)
                    )
                }
                drawRect(
                    color = dividerColor,
                    topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(width = size.width, height = 1.dp.toPx())
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = FetchTheme.colors.TextPrimary,
                modifier = Modifier.size(28.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = updateLabel,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDirection = TextDirection.Ltr
                    ),
                    color = FetchTheme.colors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        state.errorMessage != null -> FetchTheme.colors.Error
                        state.isDownloading && isWhiteTheme -> FetchTheme.colors.TextPrimary
                        state.isDownloading -> FetchTheme.colors.OnSecondary
                        else -> FetchTheme.colors.TextSecondary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            UpdateBannerIconButton(
                icon = Icons.Default.Info,
                contentDescription = stringResource(R.string.update_release_notes),
                onClick = onShowReleaseNotes,
                enabled = update.notes.isNotBlank()
            )

            Button(
                onClick = {
                    when {
                        state.isDownloading -> Unit
                        state.downloadedApkPath != null -> onInstall()
                        else -> onDownload()
                    }
                },
                enabled = state.isDownloading ||
                    state.downloadedApkPath != null ||
                    state.isUpdateAvailable,
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.Secondary,
                    focusedContainerColor = FetchTheme.colors.SecondaryVariant,
                    contentColor = FetchTheme.colors.OnSecondary,
                    focusedContentColor = FetchTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = when {
                        state.isDownloading -> stringResource(R.string.update_downloading_ellipsis)
                        state.downloadedApkPath != null -> stringResource(R.string.update_install)
                        state.errorMessage != null -> stringResource(R.string.action_retry)
                        else -> stringResource(R.string.update_download)
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!state.isDownloading) {
                UpdateBannerIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.update_close),
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun UpdateBannerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.Secondary,
            contentColor = FetchTheme.colors.TextPrimary,
            focusedContentColor = FetchTheme.colors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FetchTheme.colors.FocusRing),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}
