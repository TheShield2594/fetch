package com.fetch.tv.ui.screens.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme

/** Limits offered in the dialog, from strictest to none. */
internal val ProfileLimitChoices: List<CertificationLevel?> = listOf(
    CertificationLevel.ALL_AGES,
    CertificationLevel.OLDER_KIDS,
    CertificationLevel.PARENTAL_GUIDANCE,
    CertificationLevel.TEENS,
    CertificationLevel.MATURE,
    null
)

@Composable
internal fun certificationLevelLabel(level: CertificationLevel?): String = stringResource(
    when (level) {
        CertificationLevel.ALL_AGES -> R.string.parental_level_all_ages
        CertificationLevel.OLDER_KIDS -> R.string.parental_level_older_kids
        CertificationLevel.PARENTAL_GUIDANCE -> R.string.parental_level_pg
        CertificationLevel.TEENS -> R.string.parental_level_teens
        CertificationLevel.MATURE -> R.string.parental_level_mature
        CertificationLevel.ADULTS_ONLY, null -> R.string.parental_level_unrestricted
    }
)

@Composable
internal fun ProfileLimitsDialog(
    profile: UserProfile,
    initial: ProfileRestrictions,
    parentPinSet: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProfileRestrictions) -> Unit
) {
    var restrictions by remember(profile.id) { mutableStateOf(initial) }
    val firstFocus = remember(profile.id) { FocusRequester() }
    LaunchedEffect(profile.id) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    val buttonColors = ButtonDefaults.colors(
        containerColor = FetchTheme.colors.BackgroundCard,
        contentColor = FetchTheme.colors.TextPrimary
    )

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.parental_limits_title, profile.name),
        subtitle = stringResource(
            if (parentPinSet) R.string.parental_limits_subtitle else R.string.parental_limits_subtitle_no_pin
        ),
        width = 480.dp,
        suppressFirstKeyUp = false
    ) {
        Button(
            onClick = {
                restrictions = if (restrictions.isKidsProfile) {
                    restrictions.copy(isKidsProfile = false)
                } else {
                    ProfileRestrictions.KidsPreset
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(firstFocus),
            colors = buttonColors
        ) {
            Text(
                stringResource(
                    R.string.parental_kids_profile_toggle,
                    stringResource(if (restrictions.isKidsProfile) R.string.parental_on else R.string.parental_off)
                )
            )
        }

        Button(
            onClick = {
                val index = ProfileLimitChoices.indexOf(restrictions.maxLevel).coerceAtLeast(0)
                val next = ProfileLimitChoices[(index + 1) % ProfileLimitChoices.size]
                restrictions = restrictions.copy(maxLevel = next)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = buttonColors
        ) {
            Text(stringResource(R.string.parental_max_rating, certificationLevelLabel(restrictions.maxLevel)))
        }

        if (restrictions.maxLevel != null) {
            Button(
                onClick = { restrictions = restrictions.copy(allowUnrated = !restrictions.allowUnrated) },
                modifier = Modifier.fillMaxWidth(),
                colors = buttonColors
            ) {
                Text(
                    stringResource(
                        R.string.parental_allow_unrated,
                        stringResource(if (restrictions.allowUnrated) R.string.parental_on else R.string.parental_off)
                    )
                )
            }
        }

        Button(
            onClick = { onSave(restrictions) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = FetchTheme.colors.Primary,
                contentColor = FetchTheme.colors.OnPrimary
            )
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}
