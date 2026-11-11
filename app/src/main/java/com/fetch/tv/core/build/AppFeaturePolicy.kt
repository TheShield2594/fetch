package com.fetch.tv.core.build

/**
 * Feature switches that used to differ between the `full` and `playstore` flavours. There is
 * one build now, so every feature is on; the checks stay as named constants so the call sites
 * still say which feature they belong to.
 */
object AppFeaturePolicy {
    val inAppUpdatesEnabled: Boolean = true
    val inAppTrailerPlaybackEnabled: Boolean = true
    val externalTrailerPlaybackEnabled: Boolean = true
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    val imdbRatingLogoEnabled: Boolean = true
    val youTubeSectionEnabled: Boolean = true
}
