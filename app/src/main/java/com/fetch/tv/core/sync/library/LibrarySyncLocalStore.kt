package com.fetch.tv.core.sync.library

import com.fetch.tv.domain.model.LibraryDeltaApplyResult
import com.fetch.tv.domain.model.LibraryDeltaEvent
import com.fetch.tv.domain.model.LibrarySnapshotApplyResult
import com.fetch.tv.domain.model.LibrarySyncState
import com.fetch.tv.domain.model.SavedLibraryItem

interface LibrarySyncLocalStore {
    suspend fun getSyncState(profileId: Int): LibrarySyncState

    suspend fun applyRemoteSnapshot(
        profileId: Int,
        remoteItems: Collection<SavedLibraryItem>,
        cursorEventId: Long
    ): LibrarySnapshotApplyResult

    suspend fun applyRemoteDelta(
        profileId: Int,
        events: Collection<LibraryDeltaEvent>
    ): LibraryDeltaApplyResult

    suspend fun queueAllItemsForPush(profileId: Int): LibrarySyncState

    suspend fun acknowledgePush(
        profileId: Int,
        expectedMutationRevision: Long
    ): Boolean
}
