package com.fetch.desktop.sync

import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem

/** The shared model the TV app's library reducer works on. `logo` is not synced. */
fun RemoteLibraryItem.toSavedLibraryItem(): SavedLibraryItem = SavedLibraryItem(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterShape = PosterShape.fromString(posterShape),
    background = background,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    addedAt = addedAtEpochMs,
)

fun SavedLibraryItem.toRemoteLibraryItem(): RemoteLibraryItem = RemoteLibraryItem(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterShape = posterShape.name,
    background = background,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    addedAtEpochMs = addedAt,
)
