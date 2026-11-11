package com.fetch.tv.core.di

import android.content.Context
import androidx.room.Room
import com.fetch.tv.data.local.RoomWatchedItemStore
import com.fetch.tv.data.local.WatchedItemStore
import com.fetch.tv.data.local.db.FetchDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {
    @Binds
    abstract fun watchedItemStore(store: RoomWatchedItemStore): WatchedItemStore

    companion object {
        // Building it doesn't open the file; the first query does, off the main thread.
        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): FetchDatabase =
            Room.databaseBuilder(context, FetchDatabase::class.java, FetchDatabase.NAME).build()
    }
}
