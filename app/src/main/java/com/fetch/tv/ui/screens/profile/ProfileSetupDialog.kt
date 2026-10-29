package com.fetch.tv.ui.screens.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme

/** Choices for a new profile beyond its name and look: kids limits, shared addons, copied settings. */
@Composable
internal fun ProfileSetupDialog(
    mainProfileName: String,
    isKidsProfile: Boolean,
    usesMainAddons: Boolean,
    settingsSourceLabel: String,
    onKidsProfileChange: (Boolean) -> Unit,
    onUsesMainAddonsChange: (Boolean) -> Unit,
    onChooseSettingsSource: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    val buttonShape = ButtonDefaults.shape(FetchTheme.shapes.button)
    val buttonColors = ButtonDefaults.colors(
        containerColor = FetchTheme.colors.BackgroundCard,
        contentColor = FetchTheme.colors.TextPrimary
    )

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.profile_setup_title),
        subtitle = stringResource(R.string.profile_setup_subtitle, mainProfileName),
        width = 480.dp,
        suppressFirstKeyUp = false,
        scrollable = true
    ) {
        Button(
            onClick = { onKidsProfileChange(!isKidsProfile) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(firstFocus),
            shape = buttonShape,
            colors = buttonColors
        ) {
            Text(
                stringResource(
                    R.string.parental_kids_profile_toggle,
                    stringResource(if (isKidsProfile) R.string.parental_on else R.string.parental_off)
                )
            )
        }

        Button(
            onClick = { onUsesMainAddonsChange(!usesMainAddons) },
            modifier = Modifier.fillMaxWidth(),
            shape = buttonShape,
            colors = buttonColors
        ) {
            Text(profileAddonsLabel(usesMainAddons, mainProfileName))
        }

        Button(
            onClick = onChooseSettingsSource,
            modifier = Modifier.fillMaxWidth(),
            shape = buttonShape,
            colors = buttonColors
        ) {
            Text(settingsSourceLabel)
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = FetchTheme.colors.Primary,
                contentColor = FetchTheme.colors.OnPrimary
            )
        ) {
            Text(stringResource(R.string.profile_manage_done))
        }
    }
}

@Composable
internal fun profileAddonsLabel(usesMainAddons: Boolean, mainProfileName: String): String =
    if (usesMainAddons) {
        stringResource(R.string.profile_addons_shared, mainProfileName)
    } else {
        stringResource(R.string.profile_addons_own)
    }

/**
 * Asks for a PIN on the main profile after a profile gets parental limits, since without one
 * anyone can change the limits or switch profiles.
 */
@Composable
internal fun ParentPinNudgeDialog(
    restrictedProfile: UserProfile,
    mainProfile: UserProfile,
    onSetPin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    FetchDialog(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.parental_pin_nudge_title, restrictedProfile.name),
        subtitle = stringResource(R.string.parental_pin_nudge_subtitle, mainProfile.name, restrictedProfile.name),
        width = 480.dp,
        suppressFirstKeyUp = false
    ) {
        Button(
            onClick = onSetPin,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(firstFocus),
            colors = ButtonDefaults.colors(
                containerColor = FetchTheme.colors.Primary,
                contentColor = FetchTheme.colors.OnPrimary
            )
        ) {
            Text(stringResource(R.string.parental_pin_nudge_set, mainProfile.name))
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                contentColor = FetchTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.parental_pin_nudge_later))
        }
    }
}

/**
 * Offers to lock the profiles with no limits and no PIN, after [restrictedProfile] gets limits:
 * otherwise it can open one of them from the profile picker and skip every limit.
 */
@Composable
internal fun LockProfilesOfferDialog(
    restrictedProfile: UserProfile,
    unlockedProfiles: List<UserProfile>,
    mainProfile: UserProfile,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    val names = unlockedProfiles.joinToString(", ") { it.name }
    FetchDialog(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.parental_lock_profiles_offer_title),
        subtitle = stringResource(
            R.string.parental_lock_profiles_offer_subtitle,
            restrictedProfile.name,
            names,
            mainProfile.name
        ),
        width = 480.dp,
        suppressFirstKeyUp = false
    ) {
        Button(
            onClick = onAccept,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(firstFocus),
            colors = ButtonDefaults.colors(
                containerColor = FetchTheme.colors.Primary,
                contentColor = FetchTheme.colors.OnPrimary
            )
        ) {
            Text(stringResource(R.string.parental_lock_profiles_offer_accept, mainProfile.name))
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                contentColor = FetchTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.parental_pin_nudge_later))
        }
    }
}
