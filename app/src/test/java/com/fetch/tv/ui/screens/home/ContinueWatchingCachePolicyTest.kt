package com.fetch.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingCachePolicyTest {
    @Test
    fun `cached progress is restored while tracking progress is loading`() {
        assertTrue(
            shouldRestoreCachedInProgress(
                hasLiveInProgress = false,
                hasActiveTrackingProvider = true,
                hasCachedInProgress = true,
                hasProviderItems = false,
                hasLoadedRemoteProgress = false
            )
        )
    }

    @Test
    fun `cached progress is not restored after provider confirms empty progress`() {
        assertFalse(
            shouldRestoreCachedInProgress(
                hasLiveInProgress = false,
                hasActiveTrackingProvider = true,
                hasCachedInProgress = true,
                hasProviderItems = false,
                hasLoadedRemoteProgress = true
            )
        )
    }

    @Test
    fun `cached progress is not restored over live provider progress`() {
        assertFalse(
            shouldRestoreCachedInProgress(
                hasLiveInProgress = true,
                hasActiveTrackingProvider = true,
                hasCachedInProgress = true,
                hasProviderItems = true,
                hasLoadedRemoteProgress = true
            )
        )
    }

    @Test
    fun `cached progress is not restored without a tracking provider`() {
        assertFalse(
            shouldRestoreCachedInProgress(
                hasLiveInProgress = false,
                hasActiveTrackingProvider = false,
                hasCachedInProgress = true,
                hasProviderItems = false,
                hasLoadedRemoteProgress = false
            )
        )
    }

    @Test
    fun `empty tracker profile resolves the start-up gate once the tracker answers`() {
        assertTrue(
            isInitialContinueWatchingResolved(
                hasItems = false,
                usesTrackingProvider = true,
                hasLoadedRemoteProgress = true
            )
        )
    }

    @Test
    fun `empty tracker profile keeps the gate while the tracker is still loading`() {
        assertFalse(
            isInitialContinueWatchingResolved(
                hasItems = false,
                usesTrackingProvider = true,
                hasLoadedRemoteProgress = false
            )
        )
    }

    @Test
    fun `local profile resolves the gate on its first run even when empty`() {
        assertTrue(
            isInitialContinueWatchingResolved(
                hasItems = false,
                usesTrackingProvider = false,
                hasLoadedRemoteProgress = false
            )
        )
    }
}
