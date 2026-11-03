package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.ProfilesState
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.parental.ParentalTitle
import com.fetch.desktop.parental.ParentalTitleState
import com.fetch.desktop.parental.parentalTitleOf
import com.fetch.tv.domain.model.MetaPreview

/**
 * The parental entry of a poster's right-click menu, as on TV: on another profile "Hide from
 * <profile>" (behind the main profile's PIN), on the main profile "Parental limits for this
 * title", which sets it for each profile. Nothing when there's no other profile.
 */
fun parentalTitleMenu(state: AppState, item: MetaPreview): List<ContextMenuItem> {
    val profiles = state.profiles
    val title = parentalTitleOf(item)
    return when {
        !state.profile.isPrimary -> listOf(ContextMenuItem(tr("Hide from %1\$s", state.profile.name)) {
            profiles.titleLists.pendingHide = state.profile.id to title
        })
        profiles.profiles.any { !it.isPrimary } -> listOf(ContextMenuItem(tr("Parental limits for this title…")) {
            profiles.titleLists.editing = title
        })
        else -> emptyList()
    }
}

/** The dialog and PIN prompt [parentalTitleMenu] opens. Placed once, over the app. */
@Composable
fun ParentalTitleDialogHost(state: AppState) {
    val profiles = state.profiles
    val lists = profiles.titleLists
    lists.pendingHide?.let { (profileId, title) ->
        val hide = {
            lists.pendingHide = null
            lists.setState(profileId, title, ParentalTitleState.HIDDEN)
            state.message = "${title.name} is hidden from ${profiles.profiles.firstOrNull { it.id == profileId }?.name ?: "this profile"}"
        }
        val main = profiles.primary
        if (!main.isLocked) {
            androidx.compose.runtime.LaunchedEffect(profileId, title) { hide() }
        } else {
            val step = androidx.compose.runtime.remember(profileId, title) {
                PinStep.Unlock(main, "Enter ${main.name}’s PIN to hide ${title.name}.", "A parent’s PIN is needed to hide a title from this profile.") { hide() }
            }
            PinFlow(profiles, step, onStep = { if (it == null) lists.pendingHide = null })
        }
    }
    lists.editing?.let { title -> TitleProfilesDialog(profiles, title, onDismiss = { lists.editing = null }) }
}

/** "Choose how each profile treats this title": each other profile's button steps through the states. */
@Composable
private fun TitleProfilesDialog(profiles: ProfilesState, title: ParentalTitle, onDismiss: () -> Unit) {
    val lists = profiles.titleLists
    // Read so the buttons redraw when a state changes.
    lists.all
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(title.name, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(520.dp)) {
                Text(
                    "Choose how each profile treats this title. A hidden title is left out of that profile’s rows, search, " +
                        "Continue Watching and autoplay. An allowed one shows whatever its rating.",
                    color = FetchPalette.TextSecondary,
                    fontSize = 14.sp
                )
                profiles.profiles.filterNot { it.isPrimary }.forEach { profile ->
                    val current = lists.stateFor(profile.id, title)
                    TitleStateRow(profile, current) { lists.cycle(profile.id, title) }
                }
            }
        },
        confirmButton = { PrimaryButton("Close", icon = null, onClick = onDismiss) }
    )
}

@Composable
private fun TitleStateRow(profile: DesktopProfile, current: ParentalTitleState, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FetchPalette.Background)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        ProfileAvatar(profile, 32.dp)
        Text(profile.name, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(
            current.label.replaceFirstChar { it.uppercase() },
            color = when (current) {
                ParentalTitleState.HIDDEN -> FetchPalette.Error
                ParentalTitleState.ALLOWED -> FetchPalette.Accent
                ParentalTitleState.BY_RATING -> FetchPalette.TextSecondary
            }
        )
    }
}

/**
 * The profile's hidden and always allowed titles, in its limits dialog: selecting one leaves
 * it to the rating limit again, as on TV. Nothing when both lists are empty.
 */
@Composable
fun ProfileTitleListsSection(profiles: ProfilesState, profile: DesktopProfile) {
    val lists = profiles.titleLists.listsFor(profile.id)
    if (lists.isEmpty) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
        Text(tr("Select a title to leave it to the rating limit again"), color = FetchPalette.TextSecondary, fontSize = 13.sp)
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Hidden titles" to lists.hidden, "Always allowed titles" to lists.allowed)
                .filter { it.second.isNotEmpty() }
                .forEach { (header, titles) ->
                    Text(header, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    titles.forEach { title ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background)
                                .clickable { profiles.titleLists.setState(profile.id, title, ParentalTitleState.BY_RATING) }
                                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(title.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.size(8.dp))
                            Text(tr("Clear"), color = FetchPalette.TextMuted, fontSize = 13.sp)
                        }
                    }
                }
        }
    }
}
