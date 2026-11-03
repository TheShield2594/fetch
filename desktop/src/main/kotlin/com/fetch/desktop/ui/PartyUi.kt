package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fetch.desktop.AppState
import com.fetch.desktop.PlayerSession
import com.fetch.desktop.party.ActiveParty
import com.fetch.desktop.party.MpvPartyPlayer
import com.fetch.desktop.party.PARTY_CODE_LENGTH
import com.fetch.desktop.party.PartyAction
import com.fetch.desktop.party.PartyError
import com.fetch.desktop.party.PartyMedia
import com.fetch.desktop.party.PartyMember
import com.fetch.desktop.party.PartyMemberStatus
import com.fetch.desktop.party.PartyNotice
import com.fetch.desktop.party.PartyPreview
import com.fetch.desktop.party.PartySync
import com.fetch.desktop.party.formatPartyCode
import com.fetch.desktop.party.isValidPartyCode
import com.fetch.desktop.party.partyCodeFrom
import com.fetch.desktop.party.partyErrorOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// The watch party's screens, after the TV's `ui/screens/party/`: the player's status pill and
// side panel, the Watch party settings page (join with a code) and the title page's button.

private const val EXPERIMENTAL = "Watch parties are experimental and may not work as expected."

// --- Player ---

/**
 * The party's pill at the top of the player: who's in, and what's going on ("Reconnecting",
 * "Waiting for Sam", "Sam paused", "You're 0:42 behind the party").
 */
@Composable
fun PartyPlayerPill(state: AppState, session: PlayerSession, controlsVisible: Boolean, modifier: Modifier = Modifier) {
    val partyState by state.party.state.collectAsState()
    val playback by state.party.playback.collectAsState()
    val party = partyState.party ?: return
    val sync = session.party
    var notice by remember { mutableStateOf<PartyNotice?>(null) }
    LaunchedEffect(state.party) {
        state.party.notices.collectLatest { next ->
            notice = next
            delay(3_000)
            notice = null
        }
    }
    val hintSerial = sync?.hintSerial ?: 0
    LaunchedEffect(hintSerial) {
        delay(2_200)
        sync?.clearHint(hintSerial)
    }
    val waitingNames = if (playback?.paused == true && playback?.waiting == true) {
        PartySync.waitingMemberIds(party, emptySet()).filter { it != party.memberId }.mapNotNull { party.member(it)?.displayName }
    } else emptyList()
    val behind = sync?.behind == true
    val pausedBehind = behind && session.mpv.paused && !session.mpv.buffering
    val message = when {
        !party.connected -> "Reconnecting"
        sync?.hint != null -> sync.hint
        waitingNames.isNotEmpty() -> "Waiting for ${waitingNames.joinToString()}"
        playback?.waiting == true -> "Waiting for everyone"
        notice != null -> notice?.let(::noticeText)
        pausedBehind -> "Paused for you"
        behind -> sync?.behindMs?.let { "${clock(it / 1000.0)} behind" }
        else -> null
    }
    val visible = (controlsVisible || message != null) && !session.partyPanelOpen && session.mpv.error == null
    if (!visible) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { session.panel = null; session.partyPanelOpen = true }
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = if (message != null) 18.dp else 8.dp)
    ) {
        AvatarStack(party.members, size = 30.dp)
        if (message != null) {
            Text(message, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun noticeText(notice: PartyNotice): String = when (notice.action) {
    PartyAction.Paused -> "${notice.name} paused"
    PartyAction.Resumed -> "${notice.name} resumed"
    PartyAction.Seeked -> "${notice.name} skipped to ${clock(notice.positionMs / 1000.0)}"
    PartyAction.Speed -> "${notice.name} changed the speed to ${speedLabel(notice.rate.toDouble())}"
}

/** The Watch party side panel in the built-in player (the TV's `PartyPanel`). */
@Composable
fun PartyPlayerPanel(state: AppState, session: PlayerSession, modifier: Modifier = Modifier) {
    val partyState by state.party.state.collectAsState()
    val available by state.party.available.collectAsState()
    val party = partyState.party
    Column(
        modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(FetchPalette.Background.copy(alpha = 0.96f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(top = 24.dp, start = 24.dp, end = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, bottom = 16.dp)) {
            Text(tr("Watch party"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CircleIconButton(FetchIcons.Close, "Close", onClick = { session.partyPanelOpen = false }, size = 40.dp)
        }
        val scroll = rememberScrollState()
        ScrollbarBox(scroll, Modifier.weight(1f)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.verticalScroll(scroll).padding(end = 14.dp, bottom = 24.dp)
            ) {
                if (party == null) {
                    StartPartyContent(
                        state = state,
                        busy = partyState.busy,
                        available = available,
                        canStart = MpvPartyPlayer(session).currentMedia() != null
                    )
                } else {
                    PlayerPartyContent(state, session, party)
                }
            }
        }
    }
}

@Composable
private fun StartPartyContent(state: AppState, busy: Boolean, available: Boolean, canStart: Boolean) {
    SeatsHero(state.party.currentProfile.name, state.party.currentProfile.color, state.profiles.active?.avatarId)
    Text(tr("Watch together"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Text(
        tr("Invite your friends. Everyone plays from their own sources and stays in sync."),
        color = FetchPalette.TextSecondary
    )
    when {
        !available -> Text(PartyError.Unavailable.message, color = FetchPalette.TextMuted)
        !state.party.canUse -> Text(PartyError.SignInRequired.message, color = FetchPalette.TextMuted)
        !canStart -> Text(tr("This can't be watched in a party."), color = FetchPalette.TextMuted)
        else -> PrimaryButton("Start watch party", onClick = state::startPartyInPlayer, icon = null, enabled = !busy, modifier = Modifier.fillMaxWidth())
    }
    Text(EXPERIMENTAL, color = FetchPalette.TextMuted, fontSize = 13.sp)
}

@Composable
private fun PlayerPartyContent(state: AppState, session: PlayerSession, party: ActiveParty) {
    val sync = session.party
    val player = remember(session) { MpvPartyPlayer(session) }
    val playerMedia = player.currentMedia()
    var confirmSwitch by remember { mutableStateOf(false) }
    CodeSection(party)
    if (sync?.behind == true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("You’re ${clock((sync.behindMs ?: 0L) / 1000.0)} behind the party", color = Color.White, fontSize = 16.sp)
            PrimaryButton("Catch up", icon = null, onClick = {
                sync.catchUp()
                session.partyPanelOpen = false
            }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (playerMedia?.key != party.media.key) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("The party is watching ${party.media.displayTitle}", color = Color.White, fontSize = 16.sp)
            PrimaryButton("Watch with the party", icon = null, onClick = {
                session.partyPanelOpen = false
                state.watchWithParty(party.media)
            }, modifier = Modifier.fillMaxWidth())
            if (party.isHost && playerMedia != null) {
                SecondaryButton("Switch the party to ${playerMedia.displayTitle}", onClick = { confirmSwitch = true }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    MembersSection(state, party, selfBehindMs = sync?.behindMs?.takeIf { sync.behind })
    if (party.isHost) {
        HostSettings(state, party, sharingNote = "This stream can’t be shared.".takeIf { player.currentStream() == null })
    } else if (party.hostOnly) {
        HostOnlyNote()
    }
    PartyActions(state, party, onDone = { session.partyPanelOpen = false })
    if (confirmSwitch && playerMedia != null) {
        ConfirmDialog(
            title = tr("Switch the party?"),
            message = "Everyone will start watching ${playerMedia.displayTitle} with you.",
            confirm = "Switch",
            onConfirm = {
                confirmSwitch = false
                state.switchPartyToPlayer()
            },
            onDismiss = { confirmSwitch = false }
        )
    }
}

// --- Settings › Watch party ---

/** Settings › Watch party: join with a code, or the party you're in (the TV's PartyScreen). */
@Composable
fun WatchPartyPage(state: AppState) {
    val partyState by state.party.state.collectAsState()
    val available by state.party.available.collectAsState()
    val party = partyState.party
    val host = party?.member(party.hostMemberId)?.displayName
    PageHeader(
        "Watch party",
        subtitle = if (party != null && host != null) "Hosted by $host" else "Join friends with a party code"
    )
    if (party != null) {
        SettingsSection(null) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(20.dp)) {
                MediaHeader(party.media)
                CodeSection(party)
                PrimaryButton("Watch with the party", onClick = { state.watchWithParty(party.media) })
            }
        }
        SettingsSection("Members") {
            Column(Modifier.padding(16.dp)) { MembersSection(state, party) }
        }
        if (party.isHost) {
            SettingsSection("Host") { Column(Modifier.padding(16.dp)) { HostSettings(state, party, sharingNote = null) } }
        } else if (party.hostOnly) {
            HostOnlyNote()
        }
        SettingsSection(null) { Column(Modifier.padding(16.dp)) { PartyActions(state, party, onDone = {}) } }
        return
    }
    JoinPartySection(state, available, busy = partyState.busy)
}

@Composable
private fun JoinPartySection(state: AppState, available: Boolean, busy: Boolean) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<PartyError?>(null) }
    var preview by remember { mutableStateOf<PartyPreview?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var joined by remember { mutableStateOf<ActiveParty?>(null) }
    val blocked = when {
        !available -> PartyError.Unavailable
        !state.party.canUse -> PartyError.SignInRequired
        else -> null
    }
    val shownError = blocked ?: error

    fun lookUp(target: String) {
        previewJob?.cancel()
        previewing = true
        previewJob = scope.launch {
            val result = state.party.preview(target)
            previewing = false
            val failure = partyErrorOf(result.error)
            when {
                failure == null && result.media != null -> {
                    preview = result
                    if (result.isFull) error = PartyError.Full
                }
                failure != null && failure != PartyError.Network -> error = failure
            }
        }
    }

    fun changeCode(input: String) {
        val value = partyCodeFrom(input)
        if (busy || value == code) return
        code = value
        error = null
        preview = null
        previewJob?.cancel()
        previewing = false
        if (isValidPartyCode(value)) lookUp(value)
    }

    // A fetch://party/<code> link fills the code in and looks it up.
    LaunchedEffect(state.pendingPartyCode) {
        state.pendingPartyCode?.let { pending ->
            state.pendingPartyCode = null
            changeCode(pending)
        }
    }

    fun submit() {
        if (!isValidPartyCode(code) || busy || blocked != null) return
        scope.launch {
            error = null
            val result = state.party.join(code)
            val now = state.party.state.value.party
            if (result == null && now != null) joined = now else error = result ?: PartyError.Network
        }
    }

    // "You're in", then off to what the party is watching, as on TV.
    joined?.let { party ->
        LaunchedEffect(party.id) {
            delay(1_600)
            state.watchWithParty(party.media)
        }
        SettingsSection(null) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(28.dp)) {
                AvatarStack(party.members, size = 56.dp)
                Text(tr("You’re in"), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(party.media.displayTitle, color = FetchPalette.TextSecondary, fontSize = 17.sp)
            }
        }
        return
    }

    SettingsSection(null) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(24.dp)) {
            val current = preview
            if (current?.media != null) {
                MediaHeader(current.media)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    current.host?.let { host ->
                        Avatar(host.displayName, host.avatarColor, 28.dp, host.avatarUrl)
                        Text("Hosted by ${host.displayName}", color = FetchPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${current.members} of ${current.maxMembers} seats", color = FetchPalette.TextMuted, fontSize = 13.sp)
                }
            } else {
                SeatsHero(state.party.currentProfile.name, state.party.currentProfile.color, state.profiles.active?.avatarId)
                Text(tr("Join a watch party"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Enter the 6-character code your friend shared, or paste their invite link."), color = FetchPalette.TextSecondary)
            }
            CodeEntry(code, enabled = blocked == null && !busy, onChange = ::changeCode, onSubmit = ::submit)
            Text(shownError?.message.orEmpty(), color = FetchPalette.Error, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    "Join",
                    icon = null,
                    onClick = ::submit,
                    enabled = blocked == null && !busy && isValidPartyCode(code) &&
                        error !in setOf(PartyError.NotFound, PartyError.Removed, PartyError.RateLimited, PartyError.Full)
                )
                if (busy || previewing) CircularProgressIndicator(Modifier.size(22.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
            }
            Text(EXPERIMENTAL, color = FetchPalette.TextMuted, fontSize = 13.sp)
            if (current == null) Text(tr("To host, use Watch together on a title page, or Watch party in the player."), color = FetchPalette.TextMuted, fontSize = 13.sp)
        }
    }
}

/** Six slots the code fills in; typing or pasting (a code or a `fetch://party/` link) goes in. */
@Composable
private fun CodeEntry(code: String, enabled: Boolean, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    BasicTextField(
        value = code,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = TextStyle(color = Color.Transparent),
        modifier = Modifier.onEnterKey(onSubmit),
        decorationBox = { field ->
            Box {
                Box(Modifier.size(1.dp)) { field() }
                CodeSlots(code, slotHeight = 60.dp, active = if (enabled) code.length.coerceAtMost(PARTY_CODE_LENGTH - 1) else null)
            }
        }
    )
}

private fun Modifier.onEnterKey(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
        action()
        true
    } else false
}

/** Material Symbols' "group", for Watch party buttons. */
internal val PartyIcon: ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(
        pathData = addPathNodes(
            "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3z" +
                "M8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z"
        ),
        fill = SolidColor(Color.Black)
    ).build()

// --- Title page ---

/** The title page's "Watch together", or the party you're in. */
@Composable
fun PartyDetailButton(state: AppState) {
    val partyState by state.party.state.collectAsState()
    val available by state.party.available.collectAsState()
    val party = partyState.party
    var open by remember { mutableStateOf(false) }
    when {
        party != null -> SecondaryButton("Watch party · ${formatPartyCode(party.code)}", onClick = { open = true }, icon = PartyIcon)
        available && state.party.canUse && state.sources != null ->
            SecondaryButton("Watch together", onClick = state::startPartyFromDetail, icon = PartyIcon, enabled = !partyState.busy)
    }
    if (open && party != null) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = FetchPalette.SurfaceRaised,
            title = { Text(tr("Watch party"), color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(480.dp).verticalScroll(rememberScrollState())) {
                    Text("The party is watching ${party.media.displayTitle}", color = FetchPalette.TextSecondary)
                    CodeSection(party)
                    MembersSection(state, party)
                    if (party.isHost) HostSettings(state, party, sharingNote = null)
                    PartyActions(state, party, onDone = { open = false })
                }
            },
            confirmButton = {
                PrimaryButton("Watch with the party", icon = null, onClick = {
                    open = false
                    state.watchWithParty(party.media)
                })
            },
            dismissButton = { SecondaryButton("Close", onClick = { open = false }) }
        )
    }
}

// --- Pieces ---

@Composable
private fun MediaHeader(media: PartyMedia) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(media.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        media.episodeLabel?.let { Text(it, color = FetchPalette.TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        media.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = FetchPalette.TextSecondary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Party code" (or "Reconnecting") over the code, with Copy for the code and the invite link. */
@Composable
private fun CodeSection(party: ActiveParty) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (party.connected) {
            Text(tr("PARTY CODE"), style = OverlineStyle, color = FetchPalette.TextMuted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(FetchPalette.Accent))
                Text(tr("RECONNECTING"), style = OverlineStyle, color = FetchPalette.Accent)
            }
        }
        CodeSlots(party.code, slotHeight = 48.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Copy code", onClick = { clipboard.setText(AnnotatedString(party.code)) })
            SecondaryButton("Copy invite link", onClick = { clipboard.setText(AnnotatedString(party.inviteLink)) })
        }
        // The invite link as a QR code, as on TV: a phone scans it to join.
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            QrImage(party.inviteLink, 132.dp, description = tr("Watch party invite QR code"))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(tr("Scan with a phone to join"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Or share the code. Friends can enter it in Watch party on any Fetch app."), color = FetchPalette.TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun CodeSlots(code: String, slotHeight: Dp, active: Int? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PARTY_CODE_LENGTH) { index ->
            if (index == 3) Spacer6()
            val char = code.getOrNull(index)?.toString().orEmpty()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = slotHeight * 0.8f, height = slotHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FetchPalette.Background)
                    .border(if (index == active) 2.dp else 1.dp, if (index == active) FetchPalette.Accent else FetchPalette.Outline, RoundedCornerShape(10.dp))
            ) {
                Text(char, color = Color.White, fontSize = (slotHeight.value * 0.45f).sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Spacer6() = Box(Modifier.width(6.dp))

@Composable
private fun MembersSection(state: AppState, party: ActiveParty, selfBehindMs: Long? = null) {
    var removing by remember { mutableStateOf<PartyMember?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("${party.members.size} of ${party.maxMembers} seats", color = FetchPalette.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(party.maxMembers) { index ->
                    Box(Modifier.size(width = 14.dp, height = 4.dp).clip(CircleShape).background(if (index < party.members.size) FetchPalette.Accent else FetchPalette.Outline))
                }
            }
        }
        party.members.forEach { member ->
            key(member.id) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Box {
                        Avatar(member.displayName, member.avatarColor, 40.dp, member.avatarUrl)
                        PresenceDot(party.presenceState(member), Modifier.align(Alignment.BottomEnd))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (member.id == party.memberId) "${member.displayName} (You)" else member.displayName,
                                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            )
                            if (member.id == party.hostMemberId) Text(tr("Host"), color = FetchPalette.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        val behind = if (member.id == party.memberId) selfBehindMs else party.behindMs(member)
                        Text(
                            behind?.let { "${clock(it / 1000.0)} behind" } ?: party.statusLabel(member),
                            color = FetchPalette.TextMuted, fontSize = 13.sp, maxLines = 1
                        )
                    }
                    if (party.isHost && member.id != party.memberId) {
                        CircleIconButton(FetchIcons.Close, tr("Remove %1\$s", member.displayName), onClick = { removing = member }, size = 32.dp)
                    }
                }
            }
        }
        if (party.members.size < party.maxMembers) {
            Text(tr("Invite a friend: share the code above."), color = FetchPalette.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    removing?.let { member ->
        ConfirmDialog(
            title = tr("Remove %1\$s?", member.displayName),
            message = tr("They won’t be able to join this party again."),
            confirm = "Remove",
            onConfirm = {
                removing = null
                state.party.removeMember(member.id)
            },
            onDismiss = { removing = null }
        )
    }
}

@Composable
private fun HostSettings(state: AppState, party: ActiveParty, sharingNote: String?) {
    Column {
        SettingsToggle(
            "Only I can control playback",
            "Others can still pause or rewind for themselves.",
            checked = party.hostOnly,
            onChange = state.party::setHostOnly
        )
        SettingsToggle(
            "Share my stream",
            "Friends can play your link instead of picking their own. It can include your debrid key.",
            checked = party.sharing,
            onChange = state.party::setSharing
        )
        if (party.sharing && sharingNote != null) Text(sharingNote, color = FetchPalette.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun HostOnlyNote() {
    Text(tr("Only the host can control playback"), color = FetchPalette.TextSecondary, fontSize = 14.sp)
}

@Composable
private fun PartyActions(state: AppState, party: ActiveParty, onDone: () -> Unit) {
    var confirmEnd by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Leave party", onClick = {
            onDone()
            state.party.leave()
        })
        if (party.isHost && party.members.size > 1) SecondaryButton("End for everyone", onClick = { confirmEnd = true })
    }
    if (confirmEnd) {
        ConfirmDialog(
            title = tr("End the watch party?"),
            message = tr("Everyone will be disconnected from the party."),
            confirm = "End for everyone",
            onConfirm = {
                confirmEnd = false
                onDone()
                state.party.end()
            },
            onDismiss = { confirmEnd = false }
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(title, color = Color.White) },
        text = { Text(message, color = FetchPalette.TextSecondary) },
        confirmButton = { PrimaryButton(confirm, icon = null, onClick = onConfirm) },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}

@Composable
private fun Avatar(name: String, colorHex: String?, size: Dp, imageUrl: String? = null) {
    // The member's picture from the server's storage (as on TV), else their initial on their colour.
    AvatarCircle(name, colorHex?.takeIf { parseColor(it) != null }, avatarId = null, avatarUrl = imageUrl, size = size)
}

@Composable
private fun AvatarStack(members: List<PartyMember>, size: Dp) {
    val shown = members.take(3)
    val extra = members.size - shown.size
    val step = size * 0.7f
    val slots = shown.size + if (extra > 0) 1 else 0
    Box(Modifier.width(size + step * (slots - 1).coerceAtLeast(0))) {
        shown.forEachIndexed { index, member ->
            Box(Modifier.offset(x = step * index).zIndex((slots - index).toFloat()).border(2.dp, Color.Black.copy(alpha = 0.6f), CircleShape)) {
                Avatar(member.displayName, member.avatarColor, size, member.avatarUrl)
            }
        }
        if (extra > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.offset(x = step * shown.size).size(size).clip(CircleShape).background(FetchPalette.SurfaceHigh)
            ) { Text("+$extra", color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** You, and two open seats. */
@Composable
private fun SeatsHero(name: String, color: String?, avatarId: String? = null) {
    val size = 56.dp
    val step = size * 0.72f
    Box(Modifier.width(size + step * 2)) {
        repeat(2) { index ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.offset(x = step * (2 - index)).size(size).clip(CircleShape).border(1.dp, FetchPalette.TextMuted, CircleShape)
            ) { Text("+", color = FetchPalette.TextSecondary, fontSize = 22.sp) }
        }
        Box(Modifier.zIndex(1f).border(3.dp, FetchPalette.Background, CircleShape)) { AvatarCircle(name, color, avatarId, null, size) }
    }
}

private enum class PresenceLook { Watching, Busy, Away, Offline }

@Composable
private fun PresenceDot(look: PresenceLook, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(FetchPalette.Background)
            .padding(2.dp)
            .clip(CircleShape)
            .then(
                when (look) {
                    PresenceLook.Watching, PresenceLook.Busy -> Modifier.background(FetchPalette.Accent)
                    PresenceLook.Away -> Modifier.background(FetchPalette.TextMuted)
                    PresenceLook.Offline -> Modifier.border(1.dp, FetchPalette.TextMuted, CircleShape)
                }
            )
    )
}

private fun ActiveParty.presenceOf(member: PartyMember) =
    presence[member.id] ?: com.fetch.desktop.party.PartyPresence(memberId = member.id).takeIf { member.id == memberId }

private fun ActiveParty.presenceState(member: PartyMember): PresenceLook {
    val presence = presenceOf(member) ?: return PresenceLook.Offline
    if (presence.mediaKey != media.key) return PresenceLook.Away
    return when (presence.status) {
        PartyMemberStatus.Ready -> PresenceLook.Watching
        PartyMemberStatus.Loading, PartyMemberStatus.Buffering -> PresenceLook.Busy
        PartyMemberStatus.Browsing, PartyMemberStatus.Behind -> PresenceLook.Away
    }
}

private fun ActiveParty.behindMs(member: PartyMember): Long? =
    presenceOf(member)?.takeIf { it.status == PartyMemberStatus.Behind && it.mediaKey == media.key }?.let { it.behindMs ?: 0L }

/** The TV's member status line. */
internal fun ActiveParty.statusLabel(member: PartyMember): String {
    val presence = presenceOf(member) ?: return "Offline"
    if (presence.mediaKey != media.key) {
        return if (presence.status == PartyMemberStatus.Browsing) "Choosing a source" else "Watching something else"
    }
    return when (presence.status) {
        PartyMemberStatus.Ready, PartyMemberStatus.Behind -> "Watching"
        PartyMemberStatus.Buffering -> "Buffering"
        PartyMemberStatus.Loading -> "Loading"
        PartyMemberStatus.Browsing -> "Choosing a source"
    }
}

private fun parseColor(hex: String?): Color? {
    val value = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or value)
}
