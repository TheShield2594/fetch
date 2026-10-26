package com.fetch.tv.core.di

import android.os.Build
import com.fetch.tv.BuildConfig
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.core.network.SharedHttpClient
import com.fetch.tv.core.profile.ProfileScopedCredentialStore
import com.fetch.tv.core.sync.SyncClientIdentity
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.mediaserver.AndroidServerPersistence
import com.fetch.tv.data.mediaserver.ServerRepository
import com.fetch.tv.data.mediaserver.emby.EmbyProvider
import com.fetch.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.fetch.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.fetch.tv.data.mediaserver.plex.PlexProvider
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
object MediaServerModule {
    @Provides
    @Singleton
    fun repository(
        persistence: AndroidServerPersistence,
        identity: SyncClientIdentity,
        profileDataStore: ProfileDataStore
    ): ServerRepository {
        val http = SharedHttpClient.root.newBuilder()
            .socketFactory(NoDelaySocketFactory)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
        val clientIdentity = ServerClientIdentity(
            device = Build.MODEL.orEmpty().ifBlank { "Android TV" },
            version = BuildConfig.VERSION_NAME,
            deviceId = identity::currentClientId
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = ServerRepository(
            persistence = persistence,
            providers = listOf(
                JellyfinProvider(http, clientIdentity),
                EmbyProvider(http, clientIdentity),
                PlexProvider(http, clientIdentity)
            ),
            scope = scope
        )
        scope.launch { profileDataStore.activeProfileId.collect(repository::selectProfile) }
        return repository
    }

    @Provides
    @IntoSet
    fun credentialStore(repository: ServerRepository): ProfileScopedCredentialStore = repository
}
