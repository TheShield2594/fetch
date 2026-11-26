package com.fetch.tv.ui.screens.home

internal fun shouldRestoreCachedInProgress(
    hasLiveInProgress: Boolean,
    hasActiveTrackingProvider: Boolean,
    hasCachedInProgress: Boolean,
    hasProviderItems: Boolean,
    hasLoadedRemoteProgress: Boolean
): Boolean =
    !hasLiveInProgress &&
        hasActiveTrackingProvider &&
        hasCachedInProgress &&
        !hasProviderItems &&
        !hasLoadedRemoteProgress

/**
 * Whether a Continue Watching pipeline run settles the start-up gate. A local-only profile is
 * settled by its first run; a tracker profile is settled once it has items, or once the tracker
 * has answered — an empty answer included, so a new tracker user isn't held behind the gate.
 */
internal fun isInitialContinueWatchingResolved(
    hasItems: Boolean,
    usesTrackingProvider: Boolean,
    hasLoadedRemoteProgress: Boolean
): Boolean = hasItems || !usesTrackingProvider || hasLoadedRemoteProgress
