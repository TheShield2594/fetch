package com.fetch.tv.ui.screens.settings

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * The row a settings search result asked to land on: the one on [category]'s page titled [title].
 * A title is a row's key, since search lists each title once per page. The row registers its
 * [requester] while it is composed, and SettingsScreen asks that for focus once the page is up.
 */
@Stable
internal class SettingsRowTarget(val category: SettingsCategory, val title: String) {
    var requester: FocusRequester? by mutableStateOf(null)
}

/** The target for the page being shown, or null when it was opened from the rail. */
internal val LocalSettingsRowTarget = compositionLocalOf<SettingsRowTarget?> { null }

/**
 * Set by a collapsible section around its content: what to do when the target turns out to be
 * inside it, which is to expand it for good.
 */
internal val LocalSettingsTargetFound = compositionLocalOf<(() -> Unit)?> { null }

/** Whether a collapsible section should open while the target is still being looked for. */
@Composable
internal fun settingsRowTargetPending(): Boolean =
    LocalSettingsRowTarget.current?.let { it.requester == null } ?: false

/** Makes this row the one focus goes to when it is titled as the search target. */
@Composable
internal fun Modifier.settingsRowTarget(title: String?): Modifier {
    val target = LocalSettingsRowTarget.current
    if (target == null || title == null || target.title != title) return this
    val onFound = LocalSettingsTargetFound.current
    val requester = remember(target) { FocusRequester() }
    DisposableEffect(target, requester) {
        target.requester = requester
        onFound?.invoke()
        onDispose {
            if (target.requester === requester) target.requester = null
        }
    }
    return focusRequester(requester)
}

/**
 * Pages that list their rows lazily only compose what is on screen, so a target further down has
 * nothing to register until it is scrolled to. This pages through [state] until it does, and goes
 * back to the top when the page turns out not to have it.
 */
@Composable
internal fun SettingsRowTargetScroller(state: LazyListState) {
    val target = LocalSettingsRowTarget.current ?: return
    LaunchedEffect(target, state) {
        withFrameNanos { }
        while (target.requester == null && state.canScrollForward) {
            val step = state.layoutInfo.viewportSize.height * 0.8f
            if (step <= 0f) break
            state.scrollBy(step)
            withFrameNanos { }
        }
        if (target.requester == null) state.scrollToItem(0)
    }
}
