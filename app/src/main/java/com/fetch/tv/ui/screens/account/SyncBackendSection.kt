@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.sync.backend.SyncBackendKind
import com.fetch.tv.core.sync.backend.SyncBackends
import com.fetch.tv.data.website.FetchWebsiteStore
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.screens.settings.SettingsActionRow
import com.fetch.tv.ui.screens.settings.SettingsDialogActionButton
import com.fetch.tv.ui.screens.settings.SettingsDialogActionRow
import com.fetch.tv.ui.screens.settings.SettingsPickerOption
import com.fetch.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.fetch.tv.ui.theme.FetchTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Settings → Account → Sync: which backend this TV uses. */
@HiltViewModel
class SyncBackendViewModel @Inject constructor(
    private val backends: SyncBackends,
    private val websiteStore: FetchWebsiteStore
) : ViewModel() {
    /** The backend in use, or chosen and not set up yet. */
    val chosen: StateFlow<SyncBackendKind> =
        combine(backends.storedChoice, websiteStore.session) { _, _ -> backends.chosen }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), backends.chosen)

    /** The connection that switching to [kind] would end, if any. */
    fun connectionEndedBy(kind: SyncBackendKind): SyncBackendKind? =
        SyncBackendKind.FETCH_WEBSITE.takeIf { kind != it && websiteStore.isPaired }

    /**
     * Uses [kind] from now on. Choosing local only unpairs the website; everything on this TV
     * stays as it is.
     */
    fun choose(kind: SyncBackendKind) {
        backends.choose(kind)
        if (kind != SyncBackendKind.FETCH_WEBSITE && websiteStore.isPaired) websiteStore.clear()
    }
}

@Composable
internal fun syncBackendLabel(kind: SyncBackendKind): String = stringResource(
    when (kind) {
        SyncBackendKind.LOCAL_ONLY -> R.string.sync_backend_local
        SyncBackendKind.FETCH_WEBSITE -> R.string.sync_backend_website
    }
)

@Composable
private fun syncBackendDescription(kind: SyncBackendKind): String = stringResource(
    when (kind) {
        SyncBackendKind.LOCAL_ONLY -> R.string.sync_backend_local_description
        SyncBackendKind.FETCH_WEBSITE -> R.string.sync_backend_website_description
    }
)

/**
 * The first row of Settings → Account: the sync backend in use, and a picker for it. Picking
 * another one asks first when it would sign this TV out of the current one.
 */
@Composable
fun SyncBackendRow(
    modifier: Modifier = Modifier,
    viewModel: SyncBackendViewModel = hiltViewModel()
) {
    val chosen by viewModel.chosen.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<SyncBackendKind?>(null) }

    SettingsActionRow(
        title = stringResource(R.string.sync_backend_title),
        subtitle = syncBackendDescription(chosen),
        value = syncBackendLabel(chosen),
        onClick = { showPicker = true },
        modifier = modifier
    )

    if (showPicker) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.sync_backend_title),
            subtitle = stringResource(R.string.sync_backend_picker_subtitle),
            options = SyncBackendKind.entries.map { kind ->
                SettingsPickerOption(kind, syncBackendLabel(kind), syncBackendDescription(kind))
            },
            selectedValue = chosen,
            onOptionSelected = { kind ->
                showPicker = false
                if (kind == chosen) return@SettingsSingleChoiceDialog
                if (viewModel.connectionEndedBy(kind) != null) pending = kind else viewModel.choose(kind)
            },
            onDismiss = { showPicker = false },
            maxHeight = 420.dp
        )
    }

    val target = pending
    val ended = target?.let(viewModel::connectionEndedBy)
    if (target != null && ended != null) {
        FetchDialog(
            onDismiss = { pending = null },
            title = stringResource(R.string.sync_backend_switch_title, syncBackendLabel(target))
        ) {
            Text(
                text = stringResource(R.string.sync_backend_switch_body, syncBackendLabel(ended)),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary
            )
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = { pending = null })
                SettingsDialogActionButton(
                    text = stringResource(R.string.sync_backend_switch_confirm),
                    primary = true,
                    onClick = {
                        viewModel.choose(target)
                        pending = null
                    }
                )
            }
        }
    }
}
