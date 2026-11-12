package com.fetch.tv.domain.model

data class WatchedItem(
    val contentId: String,
    val contentType: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val watchedAt: Long,
    val poster: String? = null,
    val releaseInfo: String? = null,
    val trackingProviderId: String? = null,
    val trackingProviderItemId: String? = null,
    val trackingSourceUrl: String? = null
)

/** What identifies a watched mark: one per movie, series, or episode of a series. */
data class WatchedItemKey(
    val contentId: String,
    val season: Int?,
    val episode: Int?
)

fun WatchedItem.itemKey(): WatchedItemKey = WatchedItemKey(
    contentId = contentId,
    season = season,
    episode = episode
)
