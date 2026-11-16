package com.fetch.tv.core.di

import com.fetch.tv.core.profile.ProfileScopedCredentialStore
import com.fetch.tv.data.local.YouTubeCredentialStore
import com.fetch.tv.data.trailer.InAppYouTubeExtractor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The extractor the YouTube section plays videos with. Its own instance, so its own rate-limit
 * pause: a browsing session YouTube starts refusing never stops trailers (or YouTube-hosted
 * streams) elsewhere in the app, and the reverse.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class YouTubeSectionExtractor

@Module
@InstallIn(SingletonComponent::class)
abstract class YouTubeModule {
    /** So deleting a profile, or signing out of the Fetch account, removes its YouTube sign-in too. */
    @Binds
    @IntoSet
    abstract fun bindYouTubeCredentialStore(store: YouTubeCredentialStore): ProfileScopedCredentialStore

    companion object {
        @Provides
        @Singleton
        @YouTubeSectionExtractor
        fun provideYouTubeSectionExtractor(): InAppYouTubeExtractor = InAppYouTubeExtractor()
    }
}
