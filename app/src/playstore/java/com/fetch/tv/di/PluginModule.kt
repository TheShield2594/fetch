package com.fetch.tv.di

import com.fetch.tv.core.plugin.PluginManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {
    @Provides
    @Singleton
    fun providePluginManager(): PluginManager {
        return PluginManager()
    }
}
