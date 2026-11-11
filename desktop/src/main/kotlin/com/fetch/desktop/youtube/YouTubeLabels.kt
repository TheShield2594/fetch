package com.fetch.desktop.youtube

import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.SponsorBlockMode
import com.fetch.tv.data.youtube.YouTubeFeed
import com.fetch.tv.data.youtube.YouTubePlaylistPrivacy

// The desktop's English labels for the shared YouTube enums. The TV app takes its labels from
// string resources, so they stay out of the shared models.

/** What a skipped segment is called: "Skipped <label>". */
val SponsorBlockCategory.label: String
    get() = when (this) {
        SponsorBlockCategory.SPONSOR -> "sponsor"
        SponsorBlockCategory.SELF_PROMO -> "self-promotion"
        SponsorBlockCategory.INTERACTION -> "subscribe reminder"
        SponsorBlockCategory.INTRO -> "intro"
        SponsorBlockCategory.OUTRO -> "end cards"
        SponsorBlockCategory.PREVIEW -> "preview"
        SponsorBlockCategory.MUSIC_OFFTOPIC -> "non-music part"
        SponsorBlockCategory.FILLER -> "tangent"
    }

val SponsorBlockMode.label: String
    get() = when (this) {
        SponsorBlockMode.SKIP -> "Skip"
        SponsorBlockMode.ASK -> "Ask"
        SponsorBlockMode.SHOW -> "Show on seek bar"
        SponsorBlockMode.OFF -> "Off"
    }

val YouTubePlaylistPrivacy.label: String
    get() = when (this) {
        YouTubePlaylistPrivacy.PRIVATE -> "Private"
        YouTubePlaylistPrivacy.UNLISTED -> "Unlisted"
        YouTubePlaylistPrivacy.PUBLIC -> "Public"
    }

val YouTubeFeed.label: String
    get() = when (this) {
        YouTubeFeed.HOME -> "Home"
        YouTubeFeed.SUBSCRIPTIONS -> "Subscriptions"
        YouTubeFeed.HISTORY -> "History"
        YouTubeFeed.LIBRARY -> "Library"
        YouTubeFeed.SEARCH -> "Search"
    }
