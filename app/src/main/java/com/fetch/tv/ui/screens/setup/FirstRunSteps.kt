package com.fetch.tv.ui.screens.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.server.PhoneHubState
import com.fetch.tv.domain.model.ExperienceMode
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.screens.settings.SettingsToggleRow
import com.fetch.tv.ui.theme.FetchTheme

// ─── Server ───

@Composable
internal fun ServerStep(signedIn: String?, pairing: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(72.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepIntro(
            title = stringResource(R.string.first_run_server_title),
            body = stringResource(R.string.first_run_server_body),
            modifier = Modifier.weight(1f)
        )
        Column(modifier = Modifier.weight(1.2f)) {
            if (signedIn != null) {
                StatusLine(label = stringResource(R.string.website_name), value = signedIn, done = true)
            } else {
                pairing()
            }
        }
    }
}

// ─── Sources ───

@Composable
internal fun SourcesStep(qrCode: android.graphics.Bitmap?, phoneState: PhoneHubState, status: FirstRunStatus) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(72.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PhonePanel(
            title = stringResource(R.string.first_run_sources_title),
            qrCode = qrCode,
            phoneState = phoneState,
            steps = listOf(
                stringResource(R.string.first_run_phone_step_scan),
                stringResource(R.string.first_run_sources_step_addons),
                stringResource(R.string.first_run_sources_step_confirm)
            ),
            modifier = Modifier.weight(1.3f)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
        ) {
            StatusLine(
                label = stringResource(R.string.first_run_sources_stream_addons),
                value = if (status.streamAddonCount > 0) {
                    pluralStringResource(R.plurals.first_run_sources_stream_count, status.streamAddonCount, status.streamAddonCount)
                } else {
                    stringResource(R.string.first_run_sources_stream_none)
                },
                done = status.streamAddonCount > 0
            )
            StatusLine(
                label = stringResource(R.string.first_run_sources_media_servers),
                value = status.mediaServers.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: stringResource(R.string.first_run_sources_media_servers_none),
                done = status.mediaServers.isNotEmpty()
            )
            Hint(stringResource(R.string.first_run_sources_debrid))
        }
    }
}

// ─── Metadata ───

@Composable
internal fun MetadataStep(
    qrCode: android.graphics.Bitmap?,
    phoneState: PhoneHubState,
    status: FirstRunStatus,
    tmdbAvailable: Boolean,
    onToggleTmdb: () -> Unit,
    firstFocus: FocusRequester
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(72.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PhonePanel(
            title = stringResource(R.string.first_run_metadata_title),
            qrCode = qrCode,
            phoneState = phoneState,
            steps = listOf(
                stringResource(R.string.first_run_phone_step_scan),
                stringResource(R.string.first_run_metadata_step_accounts),
                stringResource(R.string.first_run_metadata_step_keys)
            ),
            modifier = Modifier.weight(1.3f)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
        ) {
            if (tmdbAvailable) {
                SettingsToggleRow(
                    title = stringResource(R.string.first_run_metadata_tmdb),
                    subtitle = stringResource(R.string.first_run_metadata_tmdb_subtitle),
                    checked = status.tmdbEnabled,
                    onToggle = onToggleTmdb,
                    modifier = Modifier.focusRequester(firstFocus)
                )
            }
            StatusLine(
                label = stringResource(R.string.first_run_metadata_mdblist),
                value = stringResource(
                    if (status.mdbListKeySet) R.string.first_run_metadata_mdblist_set else R.string.first_run_metadata_mdblist_unset
                ),
                done = status.mdbListKeySet
            )
            StatusLine(
                label = stringResource(R.string.first_run_metadata_trakt),
                value = when {
                    status.traktConnected && !status.traktUser.isNullOrBlank() ->
                        stringResource(R.string.first_run_metadata_trakt_connected_as, status.traktUser)
                    status.traktConnected -> stringResource(R.string.first_run_metadata_trakt_connected)
                    else -> stringResource(R.string.first_run_metadata_trakt_unset)
                },
                done = status.traktConnected
            )
        }
    }
}

// ─── Experience ───

@Composable
internal fun ExperienceStep(essentialFocusRequester: FocusRequester, onChoose: (ExperienceMode) -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
    ) {
        Text(
            text = stringResource(R.string.experience_mode_choose_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = FetchTheme.colors.TextPrimary
        )
        ExperienceModeCard(
            title = stringResource(R.string.experience_mode_essential),
            subtitle = stringResource(R.string.experience_mode_essential_card_subtitle),
            recommended = true,
            onClick = { onChoose(ExperienceMode.ESSENTIAL) },
            modifier = Modifier.focusRequester(essentialFocusRequester)
        )
        ExperienceModeCard(
            title = stringResource(R.string.experience_mode_advanced),
            subtitle = stringResource(R.string.experience_mode_advanced_card_subtitle),
            recommended = false,
            onClick = { onChoose(ExperienceMode.ADVANCED) }
        )
        Text(
            text = stringResource(R.string.experience_mode_choose_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextTertiary
        )
    }
}

// ─── Shared pieces ───

@Composable
private fun StepIntro(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
            color = FetchTheme.colors.TextPrimary
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = FetchTheme.colors.TextSecondary
        )
    }
}

/** The phone code with what to do on the phone for this step. */
@Composable
private fun PhonePanel(
    title: String,
    qrCode: android.graphics.Bitmap?,
    phoneState: PhoneHubState,
    steps: List<String>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
            color = FetchTheme.colors.TextPrimary
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(176.dp)
                    .background(FetchTheme.colors.TextPrimary, RoundedCornerShape(FetchTheme.radii.sm))
                    .padding(FetchTheme.spacing.md),
                contentAlignment = Alignment.Center
            ) {
                if (qrCode != null) {
                    Image(
                        bitmap = qrCode.asImageBitmap(),
                        contentDescription = stringResource(R.string.first_run_phone_qr_description),
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (phoneState.error == null) {
                    LoadingIndicator()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
                steps.forEachIndexed { index, text -> NumberedStep(index + 1, text) }
            }
        }
        Text(
            text = when {
                phoneState.error != null -> phoneState.error
                phoneState.pairedPhones > 0 -> pluralStringResource(
                    R.plurals.phone_hub_paired,
                    phoneState.pairedPhones,
                    phoneState.pairedPhones
                )
                else -> stringResource(R.string.first_run_phone_network)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(FetchTheme.colors.BackgroundCard, RoundedCornerShape(FetchTheme.radii.full)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = FetchTheme.colors.TextPrimary
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = FetchTheme.colors.TextPrimary
        )
    }
}

/** A label and what is set up for it; [done] marks it so a glance shows what's left. */
@Composable
private fun StatusLine(label: String, value: String, done: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = FetchTheme.colors.TextSecondary
        )
        Text(
            text = if (done) "✓ $value" else value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (done) FontWeight.Bold else FontWeight.Normal),
            color = if (done) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextTertiary
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = FetchTheme.colors.TextTertiary
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExperienceModeCard(
    title: String,
    subtitle: String,
    recommended: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(FetchTheme.radii.md)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border),
                shape = shape
            ),
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.strokes.focus),
                shape = shape
            )
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Column(
            modifier = Modifier.padding(FetchTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = FetchTheme.colors.TextPrimary
                )
                if (recommended) {
                    Text(
                        text = stringResource(R.string.first_run_recommended),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = FetchTheme.colors.TextSecondary,
                        modifier = Modifier
                            .border(FetchTheme.spacing.hairline, FetchTheme.colors.TextTertiary, RoundedCornerShape(FetchTheme.radii.xs))
                            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xxs)
                    )
                }
            }
            Spacer(modifier = Modifier.height(FetchTheme.spacing.xxs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary,
                modifier = Modifier.width(520.dp)
            )
        }
    }
}
