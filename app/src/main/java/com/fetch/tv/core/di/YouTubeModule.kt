package com.fetch.tv.core.di

import com.fetch.tv.core.profile.ProfileScopedCredentialStore
import com.fetch.tv.data.local.YouTubeCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class YouTubeModule {
    /** So deleting a profile, or signing out of the Fetch account, removes its YouTube sign-in too. */
    @Binds
    @IntoSet
    abstract fun bindYouTubeCredentialStore(store: YouTubeCredentialStore): ProfileScopedCredentialStore
}
