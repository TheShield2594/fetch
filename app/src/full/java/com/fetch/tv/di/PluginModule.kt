package com.fetch.tv.di

import com.fetch.tv.core.auth.AuthManager
import com.fetch.tv.core.plugin.PluginManager
import com.fetch.tv.core.plugin.PluginRuntime
import com.fetch.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.fetch.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.fetch.tv.core.plugin.cloudstream.ExternalRepoParser
import com.fetch.tv.core.sync.PluginSyncService
import com.fetch.tv.data.local.PluginDataStore
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
    fun providePluginRuntime(): PluginRuntime {
        return PluginRuntime()
    }

    @Provides
    @Singleton
    fun providePluginManager(
        dataStore: PluginDataStore,
        runtime: PluginRuntime,
        pluginSyncService: PluginSyncService,
        authManager: AuthManager,
        externalRepoParser: ExternalRepoParser,
        externalExtensionLoader: ExternalExtensionLoader,
        externalExtensionRunner: ExternalExtensionRunner
    ): PluginManager {
        return PluginManager(
            dataStore, runtime, pluginSyncService, authManager,
            externalRepoParser, externalExtensionLoader, externalExtensionRunner
        )
    }
}
