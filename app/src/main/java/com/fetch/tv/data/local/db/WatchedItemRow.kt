package com.fetch.tv.data.local.db

import androidx.room.Entity
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.model.WatchedMutationKey

/**
 * One watched movie, episode or series of one profile. Season and episode are part of the key, so
 * "none" is stored as [NO_NUMBER] rather than null (a key column can't hold null in SQLite's
 * uniqueness rules); [toWatchedItem] turns it back into null.
 */
@Entity(
    tableName = "watched_items",
    primaryKeys = ["profileId", "contentId", "season", "episode"]
)
data class WatchedItemRow(
    val profileId: Int,
    val contentId: String,
    val season: Int,
    val episode: Int,
    val contentType: String,
    val title: String,
    val watchedAt: Long,
    val poster: String?,
    val releaseInfo: String?,
    val trackingProviderId: String?,
    val trackingProviderItemId: String?,
    val trackingSourceUrl: String?
) {
    fun toWatchedItem(): WatchedItem = WatchedItem(
        contentId = contentId,
        contentType = contentType,
        title = title,
        season = season.takeUnless { it == NO_NUMBER },
        episode = episode.takeUnless { it == NO_NUMBER },
        watchedAt = watchedAt,
        poster = poster,
        releaseInfo = releaseInfo,
        trackingProviderId = trackingProviderId,
        trackingProviderItemId = trackingProviderItemId,
        trackingSourceUrl = trackingSourceUrl
    )

    companion object {
        /** A season or episode that isn't there: a movie, or a whole series. Real numbers are never negative. */
        const val NO_NUMBER = -1

        fun of(profileId: Int, item: WatchedItem): WatchedItemRow = WatchedItemRow(
            profileId = profileId,
            contentId = item.contentId,
            season = item.season ?: NO_NUMBER,
            episode = item.episode ?: NO_NUMBER,
            contentType = item.contentType,
            title = item.title,
            watchedAt = item.watchedAt,
            poster = item.poster,
            releaseInfo = item.releaseInfo,
            trackingProviderId = item.trackingProviderId,
            trackingProviderItemId = item.trackingProviderItemId,
            trackingSourceUrl = item.trackingSourceUrl
        )
    }
}

internal fun WatchedMutationKey.seasonColumn(): Int = season ?: WatchedItemRow.NO_NUMBER
internal fun WatchedMutationKey.episodeColumn(): Int = episode ?: WatchedItemRow.NO_NUMBER
