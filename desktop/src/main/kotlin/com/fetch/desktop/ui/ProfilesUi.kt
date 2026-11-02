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

/** "Who's watching?": pick a profile, or add one. */
@Composable
fun ProfilePicker(profiles: ProfilesState) {
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(FetchPalette.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
            Text("Who's watching?", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                profiles.profiles.forEach { profile ->
                    PickerTile(profile.name, onClick = { profiles.select(profile) }) { ProfileAvatar(profile, 120.dp) }
                }
                PickerTile("Add profile", onClick = { creating = true }) {
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
}

@Composable
private fun PickerTile(label: String, onClick: () -> Unit, avatar: @Composable () -> Unit) {
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
            label,
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

/** Settings › Profiles. */
@Composable
fun ProfilesSettingsPage(state: AppState) {
    val profiles = state.profiles
    var editing by remember { mutableStateOf<DesktopProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DesktopProfile?>(null) }
    PageHeader("Profiles", subtitle = "Each profile has its own Library, Continue Watching, settings and accounts.")
    SettingsSection(null) {
        profiles.profiles.forEach { profile ->
            SettingsRow(
                title = profile.name + if (profile.id == state.profile.id) " (this profile)" else "",
                description = listOfNotNull(
                    "Main profile".takeIf { profile.isPrimary },
                    if (profile.usesPrimaryAddons || profile.isPrimary) "Main profile's addons" else "Its own addons"
                ).joinToString(" · ")
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(profile, 36.dp)
                    SecondaryButton("Edit", onClick = { editing = profile })
                    if (!profile.isPrimary) SecondaryButton("Delete", onClick = { deleting = profile })
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("Add profile", onClick = { creating = true }, icon = FetchIcons.Add)
        if (profiles.profiles.size > 1) SecondaryButton("Switch profile", onClick = profiles::switchProfile)
    }
    SettingsSection("At Start-up") {
        SettingsToggle(
            "Remember Last Profile",
            "Skip the profile picker and open the last profile. Turn off to ask for a profile at start-up.",
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
