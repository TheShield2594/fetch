package com.fetch.tv.core.di

import com.fetch.tv.BuildConfig
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.core.network.SharedHttpClient
import com.fetch.tv.core.profile.ProfileScopedCredentialStore
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.mdblist.AndroidMdbListAuthPersistence
import com.fetch.tv.data.mdblist.AndroidMdbListSyncStorage
import com.fetch.tv.data.mdblist.MdbListSyncRepository
import com.fetch.tv.data.mdblist.MdbListApiClient
import com.fetch.tv.data.mdblist.MdbListAuthRepository
import com.fetch.tv.data.mdblist.MdbListAuthStore
import com.fetch.tv.data.mdblist.MdbListConfiguration
import com.fetch.tv.data.mdblist.MdbListHttpClient
import com.fetch.tv.data.mdblist.OkHttpMdbListEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Module
@InstallIn(SingletonComponent::class)
object MdbListModule {
    @Provides
    @Singleton
    fun library(
        api: MdbListApiClient,
        sync: MdbListSyncRepository,
        auth: MdbListAuthStore,
        profiles: ProfileManager
    ) = com.fetch.tv.data.mdblist.MdbListLibraryService(
        api, sync, auth, profiles.activeProfileId, CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    @Provides
    @Singleton
    fun configuration(): MdbListConfiguration = MdbListConfiguration(
        clientId = BuildConfig.MDBLIST_CLIENT_ID,
        appVersion = BuildConfig.VERSION_NAME
    )

    @Provides
    @Singleton
    fun http(configuration: MdbListConfiguration): MdbListHttpClient = MdbListHttpClient(
        OkHttpMdbListEngine(
            SharedHttpClient.root.newBuilder()
                .socketFactory(NoDelaySocketFactory)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build(),
            configuration
        )
    )

    @Provides
    @Singleton
    fun authStore(persistence: AndroidMdbListAuthPersistence, profileDataStore: ProfileDataStore): MdbListAuthStore {
        val store = MdbListAuthStore(persistence)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: MdbListAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun auth(http: MdbListHttpClient, configuration: MdbListConfiguration, store: MdbListAuthStore) =
        MdbListAuthRepository(http, configuration, store)

    @Provides
    @Singleton
    fun api(http: MdbListHttpClient, auth: MdbListAuthRepository, store: MdbListAuthStore) =
        MdbListApiClient(http, auth, store)

    @Provides
    @Singleton
    fun syncRepository(
        storage: AndroidMdbListSyncStorage,
        auth: MdbListAuthStore,
        api: MdbListApiClient,
        profiles: ProfileManager
    ) = MdbListSyncRepository(
        storage, auth, api, profiles.activeProfileId,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )
}
