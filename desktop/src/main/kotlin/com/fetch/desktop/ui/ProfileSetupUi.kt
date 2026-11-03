package com.fetch.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.ProfilesState
import com.fetch.desktop.data.AvatarCatalog
import com.fetch.desktop.data.AvatarCategoryLabels
import com.fetch.desktop.data.AvatarItem
import com.fetch.desktop.data.BundledAvatars
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.KidsLimits
import com.fetch.desktop.data.ProfileBackgroundItem
import com.fetch.desktop.data.ProfileBackgrounds
import com.fetch.desktop.data.ProfilePreferences
import com.fetch.desktop.data.ViewingActivityEntry
import com.fetch.desktop.data.copiedSettings
import com.fetch.desktop.data.profileDir
import com.fetch.desktop.data.progressFile
import com.fetch.desktop.data.settingsFile
import com.fetch.desktop.data.viewingActivity
import com.fetch.desktop.player.MpvArgs
import com.fetch.tv.domain.model.PROFILE_AVATAR_COLORS
import java.text.DateFormat
import java.util.Date

// --- Avatars ---

private val bundledBitmaps = HashMap<String, ImageBitmap?>()

/** A bundled avatar's picture, decoded once. */
private fun bundledBitmap(resource: String): ImageBitmap? = bundledBitmaps.getOrPut(resource) {
    runCatching {
        val bytes = DesktopProfile::class.java.getResourceAsStream(resource)?.use { it.readBytes() } ?: return@runCatching null
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

/**
 * A round avatar: the avatar's picture on the profile's colour, clipped to a circle as on TV, or
 * the name's initial when there's no picture (or it doesn't load).
 */
@Composable
fun AvatarCircle(name: String, colorHex: String?, avatarId: String?, avatarUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    val bundled = avatarId?.let(BundledAvatars::resource)?.let(::bundledBitmap)
    val url = avatarUrl?.takeIf { it.isNotBlank() } ?: if (bundled == null) AvatarCatalog.serverImageUrl(avatarId) else null
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).clip(CircleShape).background(colorHex?.let(::parseHexColor) ?: FetchPalette.SurfaceHigh)
    ) {
        when {
            bundled != null -> Image(bundled, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> {
                Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
                if (url != null) AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** [profile]'s avatar: its picture when it has one, else its initial on its colour. */
@Composable
fun ProfileAvatarImage(profile: DesktopProfile, size: Dp, modifier: Modifier = Modifier) =
    AvatarCircle(profile.name, profile.colorHex, profile.avatarId, profile.avatarUrl, size, modifier)

/**
 * The TV's avatar picker: category chips (All, then each category with avatars) over a grid.
 * Selecting the chosen avatar again clears it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPickerGrid(avatars: List<AvatarItem>, selectedId: String?, onSelect: (AvatarItem?) -> Unit) {
    var category by remember { mutableStateOf<String?>(null) }
    var hovered by remember { mutableStateOf<AvatarItem?>(null) }
    val present = avatars.map { it.category }.toSet()
    val categories = AvatarCategoryLabels.filterKeys { it in present }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipPicker(
            options = listOf(DropdownOption<String?>(null, "All")) + categories.map { DropdownOption<String?>(it.key, it.value) },
            selected = listOf(category),
            onChange = { picked -> category = (picked - listOf(category)).firstOrNull() }
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)
        ) {
            avatars.filter { category == null || it.category == category }.forEach { avatar ->
                val selected = avatar.id == selectedId
                AvatarCircle(
                    avatar.name, avatar.bgColor, avatar.id, avatar.imageUrl, 56.dp,
                    Modifier
                        .border(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                        .clickable { onSelect(if (selected) null else avatar) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .onHoverChanged { if (it) hovered = avatar else if (hovered == avatar) hovered = null }
                )
            }
        }
        Text(
            hovered?.name ?: "Point at an avatar to see its name",
            color = if (hovered != null) Color.White else FetchPalette.TextMuted,
            fontWeight = if (hovered != null) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.onHoverChanged(onChange: (Boolean) -> Unit): Modifier = this
    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { onChange(true) }
    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { onChange(false) }

// --- Profile settings files ---

/** [profile]'s settings: the open app's for the active profile, else its `settings.json`. */
private fun settingsOf(state: AppState?, profile: DesktopProfile): DesktopSettings =
    if (state != null && state.profile.id == profile.id) state.settings
    else runCatching { settingsFile(profileDir(profile)).load().migrated() }.getOrDefault(DesktopSettings())

private fun updateSettingsOf(state: AppState?, profile: DesktopProfile, change: (DesktopSettings) -> DesktopSettings) {
    if (state != null && state.profile.id == profile.id) {
        state.updateSettings(change)
    } else {
        val store = settingsFile(profileDir(profile))
        runCatching { store.save(change(store.load().migrated())) }
    }
}

/**
 * Copies [source]'s settings to [target] once, as the TV's "Copy settings" does: here at once,
 * and on the sync server with `sync_copy_profile_setup` while signed in.
 */
fun copyProfileSettings(state: AppState?, source: DesktopProfile, target: DesktopProfile, copyMissingKeys: Boolean): Boolean {
    if (source.id == target.id) return false
    val from = settingsOf(state, source)
    updateSettingsOf(state, target) { copiedSettings(from, it, copyMissingKeys) }
    state?.copyProfileSetupOnServer(source, target, copyMissingKeys)
    return true
}

private fun DesktopProfile.copySourceLabel() = if (isPrimary) "$name (Main)" else name

// --- Create and edit ---

private enum class EditorTab(val label: String) { Avatar("Avatar"), Background("Background"), Setup("Profile setup"), Preferences("Preferences") }

/**
 * The TV's profile editor. Avatar: name, colour and a picture from the avatar catalog. On a new
 * profile, Profile setup: a kids profile (PG or lower, unrated hidden), the main profile's addons
 * or its own, and settings to start with (fresh, or copied once from another profile, with
 * missing API keys too). On an existing one, Preferences: the few settings people most often
 * want to differ between profiles; and Background, the picture behind it on the profile picker,
 * when the sync server offers backgrounds. Saves on Create or Save. [onCreated] gets a new profile,
 * so the caller can suggest a parent PIN for a kids one.
 */
@Composable
fun ProfileSetupEditorDialog(
    profiles: ProfilesState,
    initial: DesktopProfile?,
    state: AppState?,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit = {},
    onCreated: (DesktopProfile) -> Unit = {},
    /** Without [state] (the profile picker): copies on the sync server, after the local copy. */
    onCopiedLocally: (source: DesktopProfile, target: DesktopProfile, copyMissingKeys: Boolean) -> Unit = { _, _, _ -> }
) {
    val creating = initial == null
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableStateOf(initial?.colorHex ?: profiles.file.nextColor()) }
    var avatarId by remember { mutableStateOf(initial?.avatarId) }
    // Removed in this editor: the account's picture is cleared too, not kept.
    var avatarCleared by remember { mutableStateOf(initial?.avatarCleared ?: false) }
    var backgroundId by remember { mutableStateOf(initial?.profileBackgroundId) }
    var backgroundUrl by remember { mutableStateOf(initial?.profileBackgroundUrl) }
    var share by remember { mutableStateOf(initial?.usesPrimaryAddons ?: true) }
    var kids by remember { mutableStateOf(false) }
    var copyFrom by remember { mutableStateOf<DesktopProfile?>(null) }
    var copyKeys by remember { mutableStateOf(false) }
    val original = remember { initial?.let { ProfilePreferences.from(settingsOf(state, it)) } }
    var prefs by remember { mutableStateOf(original) }
    var tab by remember { mutableStateOf(EditorTab.Avatar) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val backgrounds = ProfileBackgrounds.all
    val showBackgrounds = !creating && (backgrounds.isNotEmpty() || !initial?.profileBackgroundUrl.isNullOrBlank())
    val tabs = if (creating) listOf(EditorTab.Avatar, EditorTab.Setup)
    else listOfNotNull(EditorTab.Avatar, EditorTab.Background.takeIf { showBackgrounds }, EditorTab.Preferences)
    // The picture shown: the one picked here, else the profile's own until it's changed or removed.
    val keptAvatarUrl = if (!avatarCleared && avatarId == initial?.avatarId) initial?.avatarUrl else null
    val main = profiles.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AvatarCircle(name.ifBlank { "?" }, color, avatarId, keptAvatarUrl, 48.dp)
                Text(if (creating) "Create profile" else "Edit profile", color = Color.White)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(600.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEach { t ->
                        val on = t == tab
                        Text(
                            t.label,
                            color = if (on) FetchPalette.OnAccent else FetchPalette.TextPrimary,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 14.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) FetchPalette.Accent else Color.White.copy(alpha = 0.08f))
                                .clickable { tab = t }.pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                when (tab) {
                    EditorTab.Avatar -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PROFILE_AVATAR_COLORS.forEach { hex ->
                                val selected = hex.equals(color, ignoreCase = true)
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(parseHexColor(hex))
                                        .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                                        .clickable { color = hex }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                )
                            }
                        }
                        AvatarPickerGrid(AvatarCatalog.all, avatarId) { avatar ->
                            avatarId = avatar?.id
                            // Unpicking the chosen one removes the avatar, everywhere once synced.
                            avatarCleared = avatar == null && !creating
                            avatar?.bgColor?.let { color = it }
                        }
                        if (!creating && (avatarId != null || keptAvatarUrl != null)) {
                            SecondaryButton("Remove avatar", icon = null, onClick = {
                                avatarId = null
                                avatarCleared = true
                            })
                        }
                    }
                    EditorTab.Background -> ProfileBackgroundPicker(backgrounds, backgroundId, backgroundUrl) { id, url ->
                        backgroundId = id
                        backgroundUrl = url
                    }
                    EditorTab.Setup -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "A kids profile only shows titles rated PG or lower and hides unrated ones. Addons shared with ${main.name} can only be changed from ${main.name}.",
                            color = FetchPalette.TextSecondary,
                            fontSize = 14.sp
                        )
                        SettingsToggle("Kids profile", "PG or lower, unrated titles hidden. Change it later in Limits.", kids) { kids = it }
                        SettingsToggle("Use ${main.name}’s addons", "Off gives this profile its own addon list.", share) { share = it }
                        SettingsChoice(
                            "Settings",
                            "Start fresh or copy settings from another profile once.",
                            copyFrom?.id,
                            listOf(DropdownOption<Int?>(null, "Start fresh")) + profiles.profiles.map { DropdownOption<Int?>(it.id, it.copySourceLabel()) }
                        ) { id -> copyFrom = profiles.profiles.firstOrNull { it.id == id } }
                        if (copyFrom != null) {
                            SettingsToggle("Copy missing API keys", "Your own TMDB and MDBList keys, where this profile has none.", copyKeys) { copyKeys = it }
                        }
                        Text(
                            "Accounts, addons, Home layout, PIN, history and Library stay unchanged.",
                            color = FetchPalette.TextMuted,
                            fontSize = 13.sp
                        )
                    }
                    EditorTab.Preferences -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Only this profile uses these. The rest are in Settings while it is active.", color = FetchPalette.TextSecondary, fontSize = 14.sp)
                        val p = prefs ?: return@Column
                        val languages = MpvArgs.languages.map { DropdownOption<String?>(it.code, it.label) }
                        SettingsChoice("Audio language", null, p.audioLanguage, listOf(DropdownOption<String?>(null, "Default (media file)")) + languages) {
                            prefs = p.copy(audioLanguage = it)
                        }
                        val off = "__off"
                        SettingsChoice(
                            "Subtitle language",
                            null,
                            if (p.subtitlesOff) off else p.subtitleLanguage,
                            listOf(DropdownOption<String?>(off, "None"), DropdownOption<String?>(null, "Device language")) + languages
                        ) { v -> prefs = if (v == off) p.copy(subtitlesOff = true) else p.copy(subtitlesOff = false, subtitleLanguage = v) }
                        SettingsToggle("Autoplay next episode", null, p.autoplayNextEpisode) { prefs = p.copy(autoplayNextEpisode = it) }
                    }
                }
                if (initial?.isPrimary != true && !creating) {
                    SettingsToggle("Use ${main.name}’s addons", "Off gives this profile its own addon list.", share) { share = it }
                }
            }
        },
        confirmButton = {
            PrimaryButton(if (creating) "Create" else "Save", icon = null, enabled = name.isNotBlank(), onClick = {
                if (creating) {
                    val created = profiles.add(name, color, share, avatarId, if (kids) KidsLimits else com.fetch.desktop.data.ProfileLimits())
                    created?.let(onCreated)
                    val source = copyFrom
                    when {
                        created == null -> onDone("Couldn't create the profile")
                        source != null -> {
                            copyProfileSettings(state, source, created, copyKeys)
                            if (state == null) onCopiedLocally(source, created, copyKeys)
                            onDone("Profile created with settings from ${source.name}.")
                        }
                        else -> onDone("${created.name} created")
                    }
                } else {
                    val edited = initial!!.copy(
                        name = name.trim(),
                        colorHex = color,
                        usesPrimaryAddons = share,
                        avatarId = avatarId,
                        avatarUrl = keptAvatarUrl,
                        avatarCleared = avatarCleared && avatarId == null,
                        profileBackgroundId = backgroundId,
                        profileBackgroundUrl = backgroundUrl,
                        backgroundEdited = initial.backgroundEdited ||
                            backgroundId != initial.profileBackgroundId || backgroundUrl != initial.profileBackgroundUrl
                    )
                    profiles.update(edited)
                    val changed = prefs
                    if (changed != null && changed != original) updateSettingsOf(state, initial) { changed.applyTo(it) }
                    onDone("Saved")
                }
                onDismiss()
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}

/**
 * The TV's background picker: None, the server's backgrounds, and the profile's own picture when
 * it has one (set on another device; it can only be kept or replaced here).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileBackgroundPicker(
    backgrounds: List<ProfileBackgroundItem>,
    selectedId: String?,
    selectedUrl: String?,
    onSelect: (id: String?, url: String?) -> Unit
) {
    val customUrl = remember { selectedUrl?.takeIf { it.isNotBlank() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Shown behind this profile on the profile picker.", color = FetchPalette.TextSecondary, fontSize = 14.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(4.dp)
        ) {
            BackgroundTile("None", selectedId == null && selectedUrl.isNullOrBlank(), image = null) { onSelect(null, null) }
            if (customUrl != null) BackgroundTile("Your own picture", selectedUrl == customUrl, customUrl) { onSelect(null, customUrl) }
            backgrounds.forEach { bg ->
                BackgroundTile(bg.name, selectedUrl.isNullOrBlank() && selectedId == bg.id, bg.imageFile) { onSelect(bg.id, null) }
            }
        }
    }
}

@Composable
private fun BackgroundTile(label: String, selected: Boolean, image: Any?, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(168.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.width(168.dp).height(94.dp).clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background)
                .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else FetchPalette.Outline, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
        ) {
            if (image != null) AsyncImage(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(if (label == "None") "None" else "…", color = FetchPalette.TextMuted, fontSize = 14.sp)
        }
        Text(label, color = if (selected) Color.White else FetchPalette.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Copy settings to <profile>": pick a source profile; its settings replace this profile's once. */
@Composable
fun CopySettingsDialog(profiles: ProfilesState, target: DesktopProfile, state: AppState?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val sources = profiles.profiles.filterNot { it.id == target.id }
    var source by remember { mutableStateOf(sources.firstOrNull { it.isPrimary } ?: sources.firstOrNull()) }
    var copyKeys by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Copy settings to ${target.name}", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(560.dp)) {
                Text("Choose a source profile. Its settings will replace this profile’s settings once.", color = FetchPalette.TextSecondary, fontSize = 14.sp)
                SettingsChoice("Copy from", null, source?.id, sources.map { DropdownOption<Int?>(it.id, it.copySourceLabel()) }) { id ->
                    source = sources.firstOrNull { it.id == id }
                }
                SettingsToggle("Copy missing API keys", "Your own TMDB and MDBList keys, where ${target.name} has none.", copyKeys) { copyKeys = it }
                Text(
                    "Existing API keys are kept. Accounts, addons, Home layout, PIN, history, Library and device-specific settings stay unchanged.",
                    color = FetchPalette.TextMuted,
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = {
            PrimaryButton("Copy settings", icon = null, enabled = source != null, onClick = {
                val from = source ?: return@PrimaryButton
                onDone(if (copyProfileSettings(state, from, target, copyKeys)) "Settings copied from ${from.name} to ${target.name}." else "Could not copy settings.")
                onDismiss()
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}

// --- Viewing activity ---

/** What [profile] watched and when, newest first, as the TV's viewing activity shows it. */
@Composable
fun ViewingActivityDialog(profile: DesktopProfile, state: AppState?, onDismiss: () -> Unit) {
    val entries = remember(profile.id) {
        val file = if (state != null && state.profile.id == profile.id) state.progress
        else runCatching { progressFile(profileDir(profile)).load() }.getOrNull()
        file?.let(::viewingActivity).orEmpty()
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("${profile.name}’s viewing activity", color = Color.White) },
        text = {
            Column(Modifier.width(640.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entries.isEmpty()) Text("Nothing watched on this profile yet.", color = FetchPalette.TextSecondary)
                entries.forEach { ViewingActivityRow(it, dateFormat.format(Date(it.watchedAt))) }
            }
        },
        confirmButton = { PrimaryButton("Close", icon = null, onClick = onDismiss) }
    )
}

@Composable
private fun ViewingActivityRow(entry: ViewingActivityEntry, date: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.episodeLine?.let { Text(it, color = FetchPalette.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(date, color = FetchPalette.TextSecondary, fontSize = 13.sp)
            Text(entry.percentWatched?.let { "$it% watched" } ?: "Finished", color = FetchPalette.TextMuted, fontSize = 13.sp)
        }
    }
}
