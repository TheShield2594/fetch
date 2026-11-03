package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.fetch.desktop.party.PartySourceMarks
import com.fetch.desktop.party.PartyStream
import com.fetch.desktop.party.partyMediaKey

/** The watch party's marks for the sources of a title or episode; null when the party isn't watching it. */
@Composable
fun rememberPartySourceMarks(state: AppState, contentType: String?, contentId: String?, season: Int?, episode: Int?): PartySourceMarks? {
    val partyState by state.party.state.collectAsState()
    val shared by state.party.sharedStream.collectAsState()
    if (contentType == null || contentId == null) return null
    return PartySourceMarks.of(partyState.party, shared, partyMediaKey(contentType, contentId, season, episode))
}

/** The overline on the party's source, the TV's "Party source". */
const val PARTY_SOURCE_LABEL = "PARTY SOURCE"

/** "Shared by <host>": the stream the party's host shares, first in the list (the TV's party card). */
@Composable
fun PartySharedSourceCard(stream: PartyStream, hostName: String?, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FetchPalette.Surface.copy(alpha = 0.92f))
            .border(2.dp, if (hovered) Color.White else FetchPalette.Accent, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onPlay)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Text(
            (hostName?.takeIf { it.isNotBlank() }?.let { "SHARED BY ${it.uppercase()}" } ?: "SHARED BY THE HOST"),
            style = OverlineStyle,
            color = FetchPalette.Accent
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stream.addonName.ifBlank { stream.name ?: "Source" },
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(PartyIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        (stream.filename ?: stream.description ?: stream.name)?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = FetchPalette.TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
