package com.fetch.tv.core.di

import com.fetch.tv.data.simkl.AndroidSimklAuthStorage
import com.fetch.tv.data.simkl.AndroidSimklSyncStorage
import com.fetch.tv.data.simkl.SimklAuthStorage
import com.fetch.tv.data.simkl.SimklApiSyncRemote
import com.fetch.tv.data.simkl.SimklSyncRemote
import com.fetch.tv.data.simkl.SimklSyncStorage
import com.fetch.tv.core.tracking.TrackingLibraryProvider
import com.fetch.tv.core.tracking.TrackingProvider
import com.fetch.tv.data.repository.TraktTrackingLibraryProvider
import com.fetch.tv.data.repository.TraktTrackingProvider
import com.fetch.tv.data.simkl.SimklLibraryService
import com.fetch.tv.core.tracking.TrackingHistoryWriter
import com.fetch.tv.core.tracking.TrackingProgressProvider
import com.fetch.tv.data.repository.TraktTrackingHistoryWriter
import com.fetch.tv.data.repository.TraktTrackingProgressProvider
import com.fetch.tv.data.simkl.SimklTrackingHistoryWriter
import com.fetch.tv.data.simkl.SimklTrackingProgressProvider
import com.fetch.tv.data.simkl.SimklTrackingProvider
import com.fetch.tv.core.profile.ProfileScopedCredentialStore
import com.fetch.tv.data.mdblist.MdbListTrackingHistoryWriter
import com.fetch.tv.data.mdblist.MdbListTrackingProgressProvider
import com.fetch.tv.data.mdblist.MdbListTrackingProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackingModule {
    @Binds
    @IntoSet
    abstract fun bindMdbListLibrary(provider: com.fetch.tv.data.mdblist.MdbListTrackingLibraryProvider): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindMdbListProvider(provider: MdbListTrackingProvider): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindMdbListProgress(provider: MdbListTrackingProgressProvider): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindMdbListHistory(writer: MdbListTrackingHistoryWriter): TrackingHistoryWriter

    @Binds
    @Singleton
    abstract fun bindSimklAuthStorage(storage: AndroidSimklAuthStorage): SimklAuthStorage

    @Binds
    @IntoSet
    abstract fun bindSimklProfileScopedCredentialStore(
        storage: AndroidSimklAuthStorage
    ): ProfileScopedCredentialStore

    @Binds
    @Singleton
    abstract fun bindSimklSyncStorage(storage: AndroidSimklSyncStorage): SimklSyncStorage

    @Binds
    @Singleton
    abstract fun bindSimklSyncRemote(remote: SimklApiSyncRemote): SimklSyncRemote

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingLibraryProvider(
        provider: TraktTrackingLibraryProvider
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingLibraryProvider(
        provider: SimklLibraryService
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProgressProvider(
        provider: TraktTrackingProgressProvider
    ): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingProgressProvider(
        provider: SimklTrackingProgressProvider
    ): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingHistoryWriter(
        writer: TraktTrackingHistoryWriter
    ): TrackingHistoryWriter

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingHistoryWriter(
        writer: SimklTrackingHistoryWriter
    ): TrackingHistoryWriter

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProvider(
        provider: TraktTrackingProvider
    ): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingProvider(
        provider: SimklTrackingProvider
    ): TrackingProvider
}
