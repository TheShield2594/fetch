package com.fetch.tv.ui.screens

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fetch.tv.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.fetch.tv.data.local.ExperienceModeDataStore
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.domain.model.ExperienceMode
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.screens.phone.PhoneHubViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ExperienceModeSelectionViewModel @Inject constructor(
    private val experienceModeDataStore: ExperienceModeDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    parentalControls: ParentalControls
) : ViewModel() {
    /** A profile behind a parent PIN sets up from the TV; the phone pages would refuse it anyway. */
    val showPhoneSetup: Boolean = !parentalControls.needsGate()

    suspend fun choose(mode: ExperienceMode) {
        experienceModeDataStore.setMode(mode)
        layoutPreferenceDataStore.markHomeSetUp()
    }
}

/**
 * First run: a phone code for setup on the left (typing addon URLs and keys with a remote is the
 * slowest part of getting started) and the Essential / Advanced choice on the right.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExperienceModeSelectionScreen(
    onContinue: (ExperienceMode) -> Unit,
    viewModel: ExperienceModeSelectionViewModel = hiltViewModel(),
    phoneHubViewModel: PhoneHubViewModel = hiltViewModel()
) {
    val coroutineScope = rememberCoroutineScope()
    val essentialFocusRequester = remember { FocusRequester() }
    val phoneState by phoneHubViewModel.state.collectAsStateWithLifecycle()
    val qrCode by phoneHubViewModel.qrCode.collectAsStateWithLifecycle()

    val showPhoneSetup = viewModel.showPhoneSetup
    if (showPhoneSetup) {
        DisposableEffect(phoneHubViewModel) {
            phoneHubViewModel.onScreenOpened()
            onDispose { phoneHubViewModel.onScreenClosed() }
        }
    }

    LaunchedEffect(Unit) {
        essentialFocusRequester.requestFocus()
    }

    fun choose(mode: ExperienceMode) {
        coroutineScope.launch {
            viewModel.choose(mode)
            onContinue(mode)
        }
    }

    ExperienceModeSelectionContent(
        showPhoneSetup = showPhoneSetup,
        qrCode = qrCode,
        phoneState = phoneState,
        essentialFocusRequester = essentialFocusRequester,
        onChoose = ::choose
    )
}

/** The first-run layout itself, without the view models, so it can also be rendered with sample state. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ExperienceModeSelectionContent(
    showPhoneSetup: Boolean,
    qrCode: android.graphics.Bitmap?,
    phoneState: com.fetch.tv.core.server.PhoneHubState,
    essentialFocusRequester: FocusRequester,
    onChoose: (ExperienceMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = FetchTheme.spacing.xxxl),
        horizontalArrangement = Arrangement.spacedBy(72.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The phone side gets more room: its steps sit beside the code and must not wrap word by word.
        if (showPhoneSetup) Column(
            modifier = Modifier.weight(1.3f),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.first_run_phone_title),
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
                    val bitmap = qrCode
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.first_run_phone_qr_description),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LoadingIndicator()
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
                    FirstRunStep(1, stringResource(R.string.first_run_phone_step_scan))
                    FirstRunStep(2, stringResource(R.string.first_run_phone_step_rules))
                    FirstRunStep(3, stringResource(R.string.first_run_phone_step_accounts))
                }
            }
            Text(
                text = when {
                    phoneState.error != null -> phoneState.error.orEmpty()
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

        Column(
            modifier = Modifier.weight(1f),
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
}

@Composable
private fun FirstRunStep(number: Int, text: String) {
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
                        color = FetchTheme.colors.OnSecondary,
                        modifier = Modifier
                            .background(FetchTheme.colors.Secondary, RoundedCornerShape(FetchTheme.radii.xs))
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
