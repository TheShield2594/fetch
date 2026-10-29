package com.fetch.tv.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.data.local.KidsRules
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileTitleLists
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

/** Autoplay caps offered for a kids profile, ending with no cap. */
internal val AutoplayCapChoices: List<Int?> = listOf(1, 2, 3, 5, null)

/** Daily screen time offered for a kids profile, in minutes, starting with no limit. */
internal val DailyMinutesChoices: List<Int?> = listOf(null, 30, 60, 90, 120, 180)

/** Bedtime starts offered for a kids profile, in minutes after midnight, starting with none. */
internal val BedtimeStartChoices: List<Int?> = listOf(null, 19 * 60, 20 * 60, 21 * 60, 22 * 60)

/** Bedtime always ends at 7:00. */
internal const val BEDTIME_END_MINUTE = 7 * 60

private fun <T> List<T>.after(current: T): T = this[(indexOf(current).coerceAtLeast(0) + 1) % size]

@Composable
private fun minuteOfDayLabel(minute: Int): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return java.time.LocalTime.of(minute / 60, minute % 60)
        .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale))
}

@Composable
internal fun dailyMinutesLabel(minutes: Int?): String = when {
    minutes == null -> stringResource(R.string.parental_level_unrestricted)
    minutes < 60 -> stringResource(R.string.kids_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.kids_hours, minutes / 60)
    else -> stringResource(R.string.kids_hours_minutes, minutes / 60, minutes % 60)
}

@Composable
internal fun ProfileLimitsDialog(
    profile: UserProfile,
    initial: ProfileRestrictions,
    parentPinSet: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProfileRestrictions, KidsRules) -> Unit,
    initialKidsRules: KidsRules = KidsRules.Default,
    /** Titles hidden from or allowed on the profile from a poster's long-press menu. */
    titleLists: ProfileTitleLists = ProfileTitleLists.Empty,
    /** Leaves a listed title to the rating limit again, straight away. */
    onClearTitle: (ParentalTitle) -> Unit = {}
) {
    var restrictions by remember(profile.id) { mutableStateOf(initial) }
    var kidsRules by remember(profile.id) { mutableStateOf(initialKidsRules) }
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
        suppressFirstKeyUp = false,
        scrollable = true
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

        if (restrictions.isKidsProfile) {
            @Composable
            fun RuleButton(text: String, onClick: () -> Unit) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = buttonColors) { Text(text) }
            }
            fun onOff(value: Boolean) = if (value) R.string.parental_on else R.string.parental_off

            RuleButton(
                stringResource(
                    R.string.kids_autoplay_cap,
                    kidsRules.autoplayEpisodeCap?.let { cap ->
                        androidx.compose.ui.res.pluralStringResource(R.plurals.kids_episodes, cap, cap)
                    } ?: stringResource(R.string.parental_level_unrestricted)
                )
            ) {
                kidsRules = kidsRules.copy(autoplayEpisodeCap = AutoplayCapChoices.after(kidsRules.autoplayEpisodeCap))
            }
            RuleButton(
                stringResource(R.string.kids_approved_only, stringResource(onOff(kidsRules.approvedTitlesOnly)))
            ) {
                kidsRules = kidsRules.copy(approvedTitlesOnly = !kidsRules.approvedTitlesOnly)
            }
            if (kidsRules.approvedTitlesOnly) {
                Text(
                    text = stringResource(R.string.kids_approved_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.TextSecondary
                )
            }
            RuleButton(stringResource(R.string.kids_daily_time, dailyMinutesLabel(kidsRules.dailyMinutes))) {
                kidsRules = kidsRules.copy(dailyMinutes = DailyMinutesChoices.after(kidsRules.dailyMinutes))
            }
            RuleButton(
                kidsRules.bedtimeStartMinute?.let { start ->
                    stringResource(
                        R.string.kids_bedtime,
                        minuteOfDayLabel(start),
                        minuteOfDayLabel(kidsRules.bedtimeEndMinute ?: BEDTIME_END_MINUTE)
                    )
                } ?: stringResource(R.string.kids_bedtime_off)
            ) {
                val start = BedtimeStartChoices.after(kidsRules.bedtimeStartMinute)
                kidsRules = kidsRules.copy(
                    bedtimeStartMinute = start,
                    bedtimeEndMinute = start?.let { BEDTIME_END_MINUTE }
                )
            }
            if (kidsRules.dailyMinutes != null || kidsRules.hasBedtime) {
                RuleButton(
                    stringResource(
                        if (kidsRules.hardStop) R.string.kids_at_limit_stop else R.string.kids_at_limit_finish
                    )
                ) {
                    kidsRules = kidsRules.copy(hardStop = !kidsRules.hardStop)
                }
            }
        }

        if (!titleLists.isEmpty) {
            Text(
                text = stringResource(R.string.parental_title_lists_hint),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextSecondary
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
            ) {
                listOf(
                    R.string.parental_hidden_titles to titleLists.hidden,
                    R.string.parental_allowed_titles to titleLists.allowed
                ).filter { it.second.isNotEmpty() }.forEach { (header, titles) ->
                    item(key = header) {
                        Text(
                            text = stringResource(header),
                            style = MaterialTheme.typography.titleSmall,
                            color = FetchTheme.colors.TextPrimary
                        )
                    }
                    items(titles, key = { "$header:${it.ids.first()}" }) { title ->
                        Button(
                            onClick = { onClearTitle(title) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = buttonColors
                        ) {
                            Text(title.name, maxLines = 1)
                        }
                    }
                }
            }
        }

        Button(
            onClick = { onSave(restrictions, kidsRules) },
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
