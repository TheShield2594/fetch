package com.fetch.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    /** The player response's `captions` block as JSON, for callers that show subtitles. */
    val captionsJson: String? = null,
    /** The player response's storyboard spec, for callers that preview frames while seeking. */
    val storyboardSpec: String? = null,
    /** The quality class of the video stream picked ("1080" for 1080p), when known. */
    val videoHeight: Int? = null,
    /**
     * The quality classes this video has that the device can play, tallest first. Empty when the
     * source is not a set of adaptive streams (an HLS manifest adapts by itself).
     */
    val videoQualities: List<Int> = emptyList()
)
