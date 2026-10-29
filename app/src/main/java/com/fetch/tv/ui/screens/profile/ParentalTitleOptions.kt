package com.fetch.tv.ui.screens.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.parental.ParentalTitleState
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The title a poster's long-press menu was opened on, as the parental lists store it. */
fun parentalTitleOf(item: MetaPreview, originalId: String? = null): ParentalTitle = ParentalTitle(
    ids = listOfNotNull(item.id, item.imdbId, originalId).filter { it.isNotBlank() }.distinct(),
    type = item.apiType,
    name = item.name,
    poster = item.poster,
    background = item.background
)

/**
 * Hides a title from a profile, or allows it whatever its rating, from a poster's long-press
 * menu. On another profile the menu offers "Hide from <profile>" behind the parent PIN; on the
 * primary (parent) profile it opens a dialog that sets the title for each profile.
 */
@HiltViewModel
class ParentalTitleViewModel @Inject constructor(
    private val parentalControls: ParentalControls,
    private val profileManager: ProfileManager
) : ViewModel() {
    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles
    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId
    val titleLists = parentalControls.allTitleLists

    private val _editing = MutableStateFlow<ParentalTitle?>(null)
    /** The title whose per-profile dialog is open. */
    val editing: StateFlow<ParentalTitle?> = _editing.asStateFlow()

    fun hideFromActiveProfile(title: ParentalTitle) {
        val profileId = profileManager.activeProfileId.value
        viewModelScope.launch { parentalControls.setTitleState(profileId, title, ParentalTitleState.HIDDEN) }
    }

    fun edit(title: ParentalTitle) {
        _editing.value = title
    }

    fun stopEditing() {
        _editing.value = null
    }

    fun stateFor(profileId: Int, title: ParentalTitle): ParentalTitleState =
        title.ids.firstNotNullOfOrNull { id ->
            parentalControls.titleState(profileId, id, null, title.type).takeIf { it != ParentalTitleState.BY_RATING }
        } ?: ParentalTitleState.BY_RATING

    /** Steps the profile on to the next state: rating limit, hidden, always allowed. */
    fun cycle(profileId: Int, title: ParentalTitle) {
        val next = when (stateFor(profileId, title)) {
            ParentalTitleState.BY_RATING -> ParentalTitleState.HIDDEN
            ParentalTitleState.HIDDEN -> ParentalTitleState.ALLOWED
            ParentalTitleState.ALLOWED -> ParentalTitleState.BY_RATING
        }
        viewModelScope.launch { parentalControls.setTitleState(profileId, title, next) }
    }

    fun clear(profileId: Int, title: ParentalTitle) {
        viewModelScope.launch { parentalControls.setTitleState(profileId, title, ParentalTitleState.BY_RATING) }
    }
}

/**
 * The parental entry of a poster's long-press menu, inside its dialog. [onDone] closes the menu.
 * Draws nothing when there's no other profile to set the title for.
 */
@Composable
fun ParentalTitleMenuButton(
    title: ParentalTitle,
    onDone: () -> Unit,
    viewModel: ParentalTitleViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val gate = LocalParentalGate.current
    val colors = ButtonDefaults.colors(
        containerColor = FetchTheme.colors.BackgroundCard,
        contentColor = FetchTheme.colors.TextPrimary
    )
    if (activeProfileId != 1) {
        val active = profiles.firstOrNull { it.id == activeProfileId } ?: return
        Button(
            onClick = {
                onDone()
                gate.runParentGuarded { viewModel.hideFromActiveProfile(title) }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = colors
        ) {
            Text(stringResource(R.string.parental_title_hide, active.name))
        }
    } else if (profiles.any { !it.isPrimary }) {
        Button(
            onClick = {
                onDone()
                viewModel.edit(title)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = colors
        ) {
            Text(stringResource(R.string.parental_title_limits))
        }
    }
}

/**
 * The per-profile dialog [ParentalTitleMenuButton] opens on the primary profile. Place it once
 * per screen, next to the long-press menu.
 */
@Composable
fun ParentalTitleDialogHost(viewModel: ParentalTitleViewModel = hiltViewModel()) {
    val title by viewModel.editing.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    // Read so the buttons redraw when a state changes.
    val lists by viewModel.titleLists.collectAsState()
    val editing = title ?: return
    val others = profiles.filterNot { it.isPrimary }
    val firstFocus = remember(editing) { FocusRequester() }
    LaunchedEffect(editing) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    FetchDialog(
        onDismiss = viewModel::stopEditing,
        title = editing.name,
        subtitle = stringResource(R.string.parental_title_dialog_subtitle),
        width = 520.dp,
        suppressFirstKeyUp = false
    ) {
        others.forEachIndexed { index, profile ->
            val state = remember(lists, editing, profile.id) { viewModel.stateFor(profile.id, editing) }
            Button(
                onClick = { viewModel.cycle(profile.id, editing) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(
                    stringResource(
                        when (state) {
                            ParentalTitleState.BY_RATING -> R.string.parental_title_state_rating
                            ParentalTitleState.HIDDEN -> R.string.parental_title_state_hidden
                            ParentalTitleState.ALLOWED -> R.string.parental_title_state_allowed
                        },
                        profile.name
                    )
                )
            }
        }
        Button(
            onClick = viewModel::stopEditing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = FetchTheme.colors.Primary,
                contentColor = FetchTheme.colors.OnPrimary
            )
        ) {
            Text(stringResource(R.string.action_close))
        }
    }
}
