package com.fetch.tv.ui.screens.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.server.PhoneHubState
import com.fetch.tv.domain.model.ExperienceMode
import com.fetch.tv.ui.screens.account.FetchWebsitePairingContent
import com.fetch.tv.ui.screens.account.FetchWebsiteViewModel
import com.fetch.tv.ui.screens.phone.PhoneAddonConfirmDialog
import com.fetch.tv.ui.screens.phone.PhoneHubViewModel
import com.fetch.tv.ui.screens.settings.SettingsDialogActionButton
import com.fetch.tv.ui.screens.settings.SettingsDialogActionRow
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First run: Server → Sources → Metadata → Experience, each with Skip for now. The phone code
 * stays live for the whole wizard, so whatever is typed on the phone (an addon link, an API
 * key, a sign-in) shows up on the step as it lands. Skipping everything leaves the app as a
 * fresh install was before the wizard: local only, with the default addons.
 */
@Composable
fun FirstRunWizardScreen(
    onFinished: (ExperienceMode) -> Unit,
    viewModel: FirstRunWizardViewModel = hiltViewModel(),
    phoneHubViewModel: PhoneHubViewModel = hiltViewModel(),
    websiteViewModel: FetchWebsiteViewModel = hiltViewModel()
) {
    val coroutineScope = rememberCoroutineScope()
    val steps = viewModel.steps
    var stepName by rememberSaveable { mutableStateOf(steps.first().name) }
    val step = FirstRunStep.valueOf(stepName)
    val index = steps.indexOf(step).coerceAtLeast(0)
    val status by viewModel.status.collectAsStateWithLifecycle()
    val phoneState by phoneHubViewModel.state.collectAsStateWithLifecycle()
    val qrCode by phoneHubViewModel.qrCode.collectAsStateWithLifecycle()
    val websiteSession by websiteViewModel.session.collectAsStateWithLifecycle()
    val showPhoneSetup = FirstRunStep.SOURCES in steps

    if (showPhoneSetup) {
        DisposableEffect(phoneHubViewModel) {
            phoneHubViewModel.onScreenOpened()
            onDispose { phoneHubViewModel.onScreenClosed() }
        }
    }

    BackHandler(enabled = index > 0) { stepName = steps[index - 1].name }

    fun finish(mode: ExperienceMode) {
        coroutineScope.launch {
            viewModel.choose(mode)
            onFinished(mode)
        }
    }

    fun next() {
        val following = steps.getOrNull(index + 1)
        if (following != null) stepName = following.name else finish(ExperienceMode.ESSENTIAL)
    }

    FirstRunWizardContent(
        step = step,
        steps = steps,
        status = status,
        qrCode = qrCode,
        phoneState = phoneState,
        tmdbAvailable = viewModel.tmdbAvailable,
        websiteSignedIn = websiteSession?.let { stringResource(R.string.website_signed_in_subtitle, it.deviceName, it.serverUrl) },
        serverPairing = { firstFocus ->
            FetchWebsitePairingContent(
                viewModel = websiteViewModel,
                onCancel = null,
                onDone = ::next,
                firstFocus = firstFocus
            )
        },
        onBack = { steps.getOrNull(index - 1)?.let { stepName = it.name } },
        onNext = ::next,
        onToggleTmdb = { viewModel.setTmdbEnabled(!status.tmdbEnabled) },
        onChoose = ::finish
    )

    if (showPhoneSetup) PhoneAddonConfirmDialog(phoneHubViewModel)
}

/**
 * The wizard without its view models, so it can be rendered with sample state. [serverPairing]
 * is the website pairing UI, which brings its own view model; [websiteSignedIn] replaces it
 * once this TV is signed in.
 */
@Composable
internal fun FirstRunWizardContent(
    step: FirstRunStep,
    steps: List<FirstRunStep>,
    status: FirstRunStatus,
    qrCode: android.graphics.Bitmap?,
    phoneState: PhoneHubState,
    tmdbAvailable: Boolean,
    websiteSignedIn: String?,
    serverPairing: @Composable (FocusRequester) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onToggleTmdb: () -> Unit,
    onChoose: (ExperienceMode) -> Unit
) {
    val index = steps.indexOf(step)
    val isLast = index == steps.lastIndex
    val primaryFocus = remember(step) { FocusRequester() }
    val continueFocus = remember(step) { FocusRequester() }

    // Land on the step's own first control, or on Skip / Continue when it has none, so the
    // D-pad always has somewhere to start.
    val hasPrimary = when (step) {
        FirstRunStep.SERVER -> websiteSignedIn == null
        FirstRunStep.SOURCES -> false
        FirstRunStep.METADATA -> tmdbAvailable
        FirstRunStep.EXPERIENCE -> true
    }
    LaunchedEffect(step, hasPrimary) {
        delay(50)
        runCatching { (if (hasPrimary) primaryFocus else continueFocus).requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FetchTheme.spacing.huge, vertical = FetchTheme.spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)
    ) {
        if (steps.size > 1) StepIndicator(steps = steps, current = step)

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            when (step) {
                FirstRunStep.SERVER -> ServerStep(
                    signedIn = websiteSignedIn,
                    pairing = { serverPairing(primaryFocus) }
                )
                FirstRunStep.SOURCES -> SourcesStep(
                    qrCode = qrCode,
                    phoneState = phoneState,
                    status = status
                )
                FirstRunStep.METADATA -> MetadataStep(
                    qrCode = qrCode,
                    phoneState = phoneState,
                    status = status,
                    tmdbAvailable = tmdbAvailable,
                    onToggleTmdb = onToggleTmdb,
                    firstFocus = primaryFocus
                )
                FirstRunStep.EXPERIENCE -> ExperienceStep(
                    essentialFocusRequester = primaryFocus,
                    onChoose = onChoose
                )
            }
        }

        SettingsDialogActionRow(horizontalAlignment = Alignment.Start) {
            if (index > 0) {
                SettingsDialogActionButton(text = stringResource(R.string.first_run_back), onClick = onBack)
            }
            if (isLast) {
                // The cards are the answer here; skipping takes the recommended one.
                SettingsDialogActionButton(
                    text = stringResource(R.string.first_run_skip),
                    onClick = { onChoose(ExperienceMode.ESSENTIAL) },
                    modifier = Modifier.focusRequester(continueFocus)
                )
            } else {
                // Once something is set up the same button reads Continue; skipping and
                // continuing both just move on.
                val done = when (step) {
                    FirstRunStep.SERVER -> websiteSignedIn != null
                    FirstRunStep.SOURCES -> status.streamAddonCount > 0 || status.mediaServers.isNotEmpty()
                    FirstRunStep.METADATA -> status.tmdbEnabled || status.mdbListKeySet || status.traktConnected
                    FirstRunStep.EXPERIENCE -> true
                }
                SettingsDialogActionButton(
                    text = stringResource(if (done) R.string.first_run_continue else R.string.first_run_skip),
                    primary = done,
                    onClick = onNext,
                    modifier = Modifier.focusRequester(continueFocus)
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(steps: List<FirstRunStep>, current: FirstRunStep) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val currentIndex = steps.indexOf(current)
        steps.forEachIndexed { index, step ->
            val active = index == currentIndex
            Text(
                text = stringResource(R.string.first_run_step_label, index + 1, stringResource(stepTitle(step))),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Normal),
                color = when {
                    active -> FetchTheme.colors.TextPrimary
                    index < currentIndex -> FetchTheme.colors.TextSecondary
                    else -> FetchTheme.colors.TextTertiary
                }
            )
        }
    }
}

private fun stepTitle(step: FirstRunStep): Int = when (step) {
    FirstRunStep.SERVER -> R.string.first_run_step_server
    FirstRunStep.SOURCES -> R.string.first_run_step_sources
    FirstRunStep.METADATA -> R.string.first_run_step_metadata
    FirstRunStep.EXPERIENCE -> R.string.first_run_step_experience
}
