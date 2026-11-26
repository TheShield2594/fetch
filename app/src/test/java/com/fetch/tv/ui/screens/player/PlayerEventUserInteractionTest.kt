package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerEventUserInteractionTest {

    @Test
    fun `events overlays and effects send on their own are not user interaction`() {
        assertFalse(PlayerEvent.OnParentalGuideHide.isUserInteraction())
        assertFalse(PlayerEvent.OnHideDisplayModeInfo.isUserInteraction())
        assertFalse(PlayerEvent.OnQueuePlaybackHandled.isUserInteraction())
    }

    @Test
    fun `remote presses count as user interaction`() {
        assertTrue(PlayerEvent.OnPlayPause.isUserInteraction())
        assertTrue(PlayerEvent.OnSeekBy(10_000L).isUserInteraction())
        assertTrue(PlayerEvent.OnToggleControls.isUserInteraction())
        assertTrue(PlayerEvent.OnDismissPauseOverlay.isUserInteraction())
    }
}
