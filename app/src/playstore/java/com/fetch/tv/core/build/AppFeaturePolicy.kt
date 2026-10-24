package com.fetch.tv.core.build

object AppFeaturePolicy {
    val inAppUpdatesEnabled: Boolean = false
    val inAppTrailerPlaybackEnabled: Boolean = false
    val externalTrailerPlaybackEnabled: Boolean = true
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    val imdbRatingLogoEnabled: Boolean = false
}
