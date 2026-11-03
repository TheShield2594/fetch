package com.fetch.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.fetch.desktop.data.DesktopProfile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Collapsed width; the page content starts after it. */
val SidebarWidth = 88.dp
private val SidebarExpandedWidth = 240.dp

data class SidebarItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * The TV app's floating sidebar: icons on the page's edge, the current page filled in red. With
 * the pointer over it, it opens over the page and shows each label.
 */
@Composable
fun Sidebar(
    items: List<SidebarItem>,
    modifier: Modifier = Modifier,
    profile: DesktopProfile? = null,
    onProfile: () -> Unit = {},
    /** A kids profile: Switch profile leads the sidebar instead of sitting at the bottom, as on TV. */
    profileFirst: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val expanded by interaction.collectIsHoveredAsState()
    val width by animateDpAsState(if (expanded) SidebarExpandedWidth else SidebarWidth, tween(180))
    Box(
        modifier
            .fillMaxHeight()
            .width(width)
            .hoverable(interaction)
            .background(
                if (expanded) {
                    Brush.horizontalGradient(listOf(Color(0xF20B0B0C), Color(0xE60B0B0C)))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xCC0B0B0C), Color(0x660B0B0C)))
                }
            )
    ) {
        Column(Modifier.padding(top = 28.dp, start = 18.dp, end = 18.dp)) {
            Text(
                if (expanded) "FETCH" else "F",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 14.dp).height(32.dp)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 48.dp)
            ) {
                if (profile != null && profileFirst) {
                    ProfileEntry(profile, expanded, onProfile, Modifier.padding(bottom = 14.dp))
                }
                items.forEach { SidebarEntry(it, expanded) }
            }
        }
        // The profile at the bottom, as on TV: click to switch.
        if (profile != null && !profileFirst) {
            ProfileEntry(profile, expanded, onProfile, Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 28.dp))
        }
    }
}

@Composable
private fun ProfileEntry(profile: DesktopProfile, expanded: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).border(if (hovered) 3.dp else 0.dp, Color.White, CircleShape).padding(4.dp)
        ) { ProfileAvatar(profile, 44.dp) }
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Text(profile.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("Switch profile", color = FetchPalette.TextMuted, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SidebarEntry(item: SidebarItem, expanded: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = item.onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(shape)
                .background(
                    when {
                        hovered -> Color.White
                        item.selected -> FetchPalette.AccentDim
                        else -> Color.Transparent
                    }
                )
                .then(if (hovered) Modifier.border(2.dp, Color.White, shape) else Modifier)
        ) {
            Icon(
                item.icon,
                contentDescription = item.label,
                tint = when {
                    hovered -> Color.Black
                    item.selected -> FetchPalette.Accent
                    else -> FetchPalette.TextSecondary
                },
                modifier = Modifier.size(26.dp)
            )
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Text(
                item.label,
                color = if (hovered || item.selected) Color.White else FetchPalette.TextSecondary,
                fontSize = 18.sp,
                fontWeight = if (hovered || item.selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}
