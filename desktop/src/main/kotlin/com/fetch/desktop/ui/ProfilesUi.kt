package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.fetch.desktop.data.ProfileLimits
import com.fetch.desktop.parental.Parental
import com.fetch.desktop.parental.PinGate
import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.core.parental.ProfilePinHasher
import com.fetch.desktop.AppState
import com.fetch.desktop.ProfilesState
import com.fetch.desktop.data.DesktopProfile
import com.fetch.tv.domain.model.PROFILE_AVATAR_COLORS

fun parseHexColor(hex: String): Color =
    runCatching { Color(0xFF000000 or hex.removePrefix("#").toLong(16)) }.getOrDefault(Color(0xFF555555))

/** A round avatar in the profile's color with its initial, as on the TV app. */
@Composable
fun ProfileAvatar(profile: DesktopProfile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).clip(CircleShape).background(parseHexColor(profile.colorHex))
    ) {
        Text(
            profile.name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp
        )
    }
}

/** "Who's watching?": pick a profile, or add one. A profile with a PIN asks for it first. */
@Composable
fun ProfilePicker(profiles: ProfilesState) {
    var creating by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf<PinStep?>(null) }
    Box(Modifier.fillMaxSize().background(FetchPalette.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
            Text("Who's watching?", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                profiles.profiles.forEach { profile ->
                    PickerTile(profile.name, locked = profile.isLocked, onClick = {
                        if (profile.isLocked) {
                            pin = PinStep.Unlock(profile, "Enter the PIN for ${profile.name}.", "This profile is locked. Enter its PIN to continue.") {
                                profiles.select(profile)
                            }
                        } else {
                            profiles.select(profile)
                        }
                    }) { ProfileAvatar(profile, 120.dp) }
                }
                // Adding a profile is the main profile's call when it has a PIN; the TV app allows six.
                if (!profiles.file.isFull) PickerTile("Add profile", onClick = {
                    val main = profiles.primary
                    if (main.isLocked) {
                        pin = PinStep.Unlock(main, "Enter ${main.name}’s PIN to add a profile.", null) { creating = true }
                    } else {
                        creating = true
                    }
                }) {
                    Box(
                        Modifier.size(120.dp).clip(CircleShape).background(FetchPalette.SurfaceRaised),
                        contentAlignment = Alignment.Center
                    ) { Icon(FetchIcons.Add, contentDescription = null, tint = FetchPalette.TextSecondary, modifier = Modifier.size(48.dp)) }
                }
            }
        }
    }
    if (creating) {
        ProfileEditorDialog(
            initial = null,
            suggestedColor = profiles.file.nextColor(),
            onDismiss = { creating = false },
            onSave = { name, color, shareAddons ->
                creating = false
                profiles.add(name, color, shareAddons)
            }
        )
    }
    pin?.let { step -> PinFlow(profiles, step, onStep = { pin = it }) }
}

@Composable
private fun PickerTile(label: String, onClick: () -> Unit, locked: Boolean = false, avatar: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .width(140.dp)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Box(
            Modifier.clip(CircleShape).border(if (hovered) 4.dp else 0.dp, if (hovered) Color.White else Color.Transparent, CircleShape)
                .padding(6.dp)
        ) { avatar() }
        Text(
            if (locked) "$label  🔒" else label,
            color = if (hovered) Color.White else FetchPalette.TextSecondary,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Create or edit a profile: name, color, and whether it shares the main profile's addons. */
@Composable
fun ProfileEditorDialog(
    initial: DesktopProfile?,
    suggestedColor: String,
    onDismiss: () -> Unit,
    onSave: (name: String, colorHex: String, usesPrimaryAddons: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableStateOf(initial?.colorHex ?: suggestedColor) }
    var share by remember { mutableStateOf(initial?.usesPrimaryAddons ?: true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(if (initial == null) "Create profile" else "Edit profile", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(FetchPalette.Accent),
                    decorationBox = { field ->
                        Box {
                            if (name.isEmpty()) Text("Profile name", color = FetchPalette.TextMuted, fontSize = 17.sp)
                            field()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background)
                        .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp)).padding(12.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PROFILE_AVATAR_COLORS.forEach { hex ->
                        val selected = hex.equals(color, ignoreCase = true)
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(parseHexColor(hex))
                                .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { color = hex }
                                .pointerHoverIcon(PointerIcon.Hand)
                        )
                    }
                }
                if (initial?.isPrimary != true) {
                    SettingsToggle(
                        "Use the main profile's addons",
                        "Off gives this profile its own addon list.",
                        share
                    ) { share = it }
                }
            }
        },
        confirmButton = {
            PrimaryButton(if (initial == null) "Create" else "Save", onClick = { onSave(name, color, share) }, icon = null, enabled = name.isNotBlank())
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}

/** Settings › Profiles. The main profile manages everyone; other profiles only themselves. */
@Composable
fun ProfilesSettingsPage(state: AppState) {
    val profiles = state.profiles
    val onMain = state.profile.isPrimary
    var editing by remember { mutableStateOf<DesktopProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DesktopProfile?>(null) }
    var limiting by remember { mutableStateOf<DesktopProfile?>(null) }
    var pin by remember { mutableStateOf<PinStep?>(null) }
    PageHeader("Profiles", subtitle = "Each profile has its own Library, Continue Watching, settings and accounts.")
    SettingsSection(null) {
        profiles.profiles.forEach { profile ->
            val isThis = profile.id == state.profile.id
            val canManage = onMain || isThis
            SettingsRow(
                title = profile.name + if (isThis) " (this profile)" else "",
                description = listOfNotNull(
                    "Main profile".takeIf { profile.isPrimary },
                    if (profile.usesPrimaryAddons || profile.isPrimary) "Main profile's addons" else "Its own addons",
                    "PIN".takeIf { profile.isLocked },
                    "Limits on".takeIf { profile.limits.isRestricted }
                ).joinToString(" · ")
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(profile, 36.dp)
                    if (canManage) {
                        SecondaryButton("Edit", onClick = { editing = profile })
                        // Changing your own PIN asks for it; the main profile can reset anyone else's.
                        val needsOwnPin = profile.isLocked && (isThis || profile.isPrimary)
                        if (!profile.isLocked) {
                            SecondaryButton("Set PIN", onClick = { pin = PinStep.Create(profile) })
                        } else {
                            SecondaryButton("Change PIN", onClick = {
                                pin = if (needsOwnPin) {
                                    PinStep.Unlock(profile, "Enter current PIN to change PIN for ${profile.name}.", null) { pin = PinStep.Create(profile) }
                                } else {
                                    PinStep.Create(profile)
                                }
                            })
                            SecondaryButton("Remove PIN", onClick = {
                                if (needsOwnPin) {
                                    pin = PinStep.Unlock(profile, "Enter current PIN to remove lock for ${profile.name}.", null) { profiles.setPin(profile, null) }
                                } else {
                                    profiles.setPin(profile, null)
                                }
                            })
                        }
                    }
                    if (onMain && !profile.isPrimary) {
                        SecondaryButton("Limits", onClick = { limiting = profile })
                        SecondaryButton("Delete", onClick = { deleting = profile })
                    }
                }
            }
        }
    }
    if (onMain) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Add profile", onClick = { creating = true }, icon = FetchIcons.Add, enabled = !profiles.file.isFull)
            if (profiles.profiles.size > 1) SecondaryButton("Switch profile", onClick = profiles::switchProfile)
        }
    } else {
        Text("Other profiles, limits and new profiles are managed from the main profile.", color = FetchPalette.TextMuted)
    }
    SettingsSection("At Start-up") {
        SettingsToggle(
            "Remember Last Profile",
            "Skip the profile picker and open the last profile. Turn off to ask for a profile at start-up. A profile with a PIN always asks.",
            profiles.file.rememberLastProfile
        ) { profiles.setRememberLastProfile(it) }
    }

    if (creating) {
        ProfileEditorDialog(null, profiles.file.nextColor(), onDismiss = { creating = false }) { name, color, share ->
            creating = false
            profiles.add(name, color, share)
        }
    }
    editing?.let { profile ->
        ProfileEditorDialog(profile, profile.colorHex, onDismiss = { editing = null }) { name, color, share ->
            editing = null
            profiles.update(profile.copy(name = name.trim(), colorHex = color, usesPrimaryAddons = share))
        }
    }
    limiting?.let { profile ->
        LimitsDialog(profile, onDismiss = { limiting = null }) { limits ->
            limiting = null
            profiles.setLimits(profile, limits)
        }
    }
    pin?.let { step -> PinFlow(profiles, step, onStep = { pin = it }) }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = FetchPalette.SurfaceRaised,
            title = { Text("Delete ${profile.name}?", color = Color.White) },
            text = { Text("Its Library, Continue Watching and settings are deleted too.", color = FetchPalette.TextSecondary) },
            confirmButton = {
                PrimaryButton("Delete", icon = FetchIcons.Delete, onClick = {
                    deleting = null
                    profiles.delete(profile)
                })
            },
            dismissButton = { SecondaryButton("Cancel", onClick = { deleting = null }) }
        )
    }
}

// --- PIN ---

/** One step of a PIN flow: check a PIN, then run [then]; or create a new one (enter, confirm). */
sealed interface PinStep {
    data class Unlock(val profile: DesktopProfile, val heading: String, val support: String?, val then: () -> Unit) : PinStep
    data class Create(val profile: DesktopProfile) : PinStep
    data class Confirm(val profile: DesktopProfile, val first: String) : PinStep
}

@Composable
fun PinFlow(profiles: ProfilesState, step: PinStep, onStep: (PinStep?) -> Unit) {
    var error by remember(step) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    when (step) {
        is PinStep.Unlock -> PinDialog(step.heading, step.support, error, onDismiss = { onStep(null) }) { entered ->
            // A PIN set on another device is checked with the sync server.
            scope.launch {
                when (val result = profiles.checkPin(step.profile, entered)) {
                    PinGate.Result.Ok -> {
                        onStep(null)
                        step.then()
                    }
                    is PinGate.Result.Wrong -> error = result.message.takeIf { it.startsWith("Connect") }
                        ?: "Incorrect PIN for ${step.profile.name}. Try again."
                    is PinGate.Result.Locked -> error = "Profile is locked. Try again in ${result.seconds}s."
                }
            }
        }
        is PinStep.Create -> PinDialog(
            "Create a PIN for ${step.profile.name}.",
            "4 to 8 digits. It will be required before opening this profile.",
            error,
            onDismiss = { onStep(null) }
        ) { entered ->
            if (ProfilePinHasher.isValidPin(entered)) onStep(PinStep.Confirm(step.profile, entered)) else error = "Use 4 to 8 digits."
        }
        is PinStep.Confirm -> PinDialog("Confirm your new PIN.", "Re-enter the same digits to finish setup.", error, onDismiss = { onStep(null) }) { entered ->
            if (entered == step.first) {
                profiles.setPin(step.profile, entered)
                onStep(null)
            } else {
                onStep(PinStep.Create(step.profile))
            }
        }
    }
}

/** Asks for a PIN: digits only, hidden. */
@Composable
fun PinDialog(heading: String, support: String?, error: String?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember(heading) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(heading) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(heading, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (support != null) Text(support, color = FetchPalette.TextSecondary)
                BasicTextField(
                    value = value,
                    onValueChange = { input -> value = input.filter { it.isDigit() }.take(8) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 28.sp, letterSpacing = 12.sp),
                    cursorBrush = SolidColor(FetchPalette.Accent),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.width(260.dp).focusRequester(focus)
                        .clip(RoundedCornerShape(10.dp)).background(FetchPalette.Background)
                        .border(1.dp, FetchPalette.Outline, RoundedCornerShape(10.dp)).padding(14.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && value.length >= 4) {
                                onSubmit(value)
                                value = ""
                                true
                            } else false
                        }
                )
                if (error != null) Text(error, color = FetchPalette.Error)
            }
        },
        confirmButton = {
            PrimaryButton("OK", icon = null, enabled = value.length >= 4, onClick = {
                onSubmit(value)
                value = ""
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}

// --- Limits ---

private val DailyChoices: List<Int?> = listOf(null, 30, 60, 90, 120, 180)
private val BedtimeChoices: List<Int?> = listOf(null, 19 * 60, 20 * 60, 21 * 60, 22 * 60)
private val AutoplayChoices: List<Int?> = listOf(null, 1, 2, 3, 5)

private fun minutesLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

private fun clockLabel(minuteOfDay: Int): String = "%d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** The TV app's limits for a profile, set from the main profile. */
@Composable
fun LimitsDialog(profile: DesktopProfile, onDismiss: () -> Unit, onSave: (ProfileLimits) -> Unit) {
    var limits by remember { mutableStateOf(profile.limits) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Limits for ${profile.name}", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(560.dp)) {
                SettingsToggle(
                    "Kids profile",
                    "A simpler Home: no hero or recommendations, a few rows of bigger cards, and Switch profile first in the sidebar.",
                    limits.kidsProfile
                ) { limits = limits.copy(kidsProfile = it) }
                SettingsChoice(
                    "Maturity rating",
                    "Titles rated above this are hidden and can't be played.",
                    CertificationLevel.fromName(limits.maxRating),
                    Parental.RatingChoices.map { DropdownOption(it, Parental.ratingLabel(it)) }
                ) { limits = limits.copy(maxRating = it?.name) }
                if (limits.maxRating != null) {
                    SettingsToggle(
                        "Allow unrated titles",
                        "Ratings come from your addons, else TMDB (Settings › TMDB). Off hides titles with none.",
                        limits.allowUnrated
                    ) { limits = limits.copy(allowUnrated = it) }
                }
                SettingsChoice(
                    "Daily screen time",
                    null,
                    limits.dailyMinutes,
                    DailyChoices.map { DropdownOption(it, it?.let(::minutesLabel) ?: "No limit") }
                ) { limits = limits.copy(dailyMinutes = it) }
                SettingsChoice(
                    "Bedtime",
                    "No playback from this time until 7:00.",
                    limits.bedtimeStartMinute,
                    BedtimeChoices.map { DropdownOption(it, it?.let(::clockLabel) ?: "Off") }
                ) { limits = limits.copy(bedtimeStartMinute = it) }
                SettingsChoice(
                    "Autoplay in a row",
                    "Episodes Up Next plays before stopping.",
                    limits.autoplayCap,
                    AutoplayChoices.map { DropdownOption(it, it?.toString() ?: "No limit") }
                ) { limits = limits.copy(autoplayCap = it) }
            }
        },
        confirmButton = { PrimaryButton("Save", icon = null, onClick = { onSave(limits) }) },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}
