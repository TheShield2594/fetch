package com.fetch.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    /** The player response's `captions` block as JSON, for callers that show subtitles. */
    val captionsJson: String? = null
)
