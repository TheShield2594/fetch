package com.fetch.tv.core.sync.library

import com.fetch.tv.domain.model.LibraryDeltaEvent
import com.fetch.tv.domain.model.LibrarySyncKey
import com.fetch.tv.domain.model.SavedLibraryItem

interface LibrarySyncRemoteDataSource {
    suspend fun pullSnapshot(
        profileId: Int,
        pageSize: Int
    ): List<SavedLibraryItem>

    suspend fun getDeltaCursor(profileId: Int): Long

    suspend fun pullDelta(
        profileId: Int,
        sinceEventId: Long,
        limit: Int
    ): List<LibraryDeltaEvent>

    suspend fun pushItems(
        profileId: Int,
        items: Collection<SavedLibraryItem>
    )

    suspend fun deleteItems(
        profileId: Int,
        keys: Collection<LibrarySyncKey>
    )
}
