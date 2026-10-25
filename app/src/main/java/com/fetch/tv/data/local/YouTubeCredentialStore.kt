package com.fetch.tv.data.local

import android.content.Context
import com.fetch.tv.core.profile.ProfileScopedCredentialStore
import com.fetch.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A profile's YouTube tokens. The refresh token signs in to the Google account, so it's kept encrypted. */
@Serializable
data class YouTubeTokens(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessTokenExpiresAt: Long? = null
)

/**
 * Keeps each profile's YouTube tokens encrypted with an Android Keystore key, like the MDBList and
 * media server credentials, and out of the plain DataStore files and their shadow copies.
 */
@Singleton
class YouTubeCredentialStore @Inject constructor(
    @ApplicationContext context: Context
) : ProfileScopedCredentialStore {
    private val preferences = ProtectedProfilePreferences(context, "youtube_auth", "com.fetch.tv.youtube.credentials.v1")
    private val json = Json { ignoreUnknownKeys = true }

    fun read(profileId: Int): YouTubeTokens =
        preferences.read(profileId)
            ?.let { runCatching { json.decodeFromString(YouTubeTokens.serializer(), it) }.getOrNull() }
            ?: YouTubeTokens()

    fun write(profileId: Int, tokens: YouTubeTokens?) {
        preferences.write(profileId, tokens?.let { json.encodeToString(YouTubeTokens.serializer(), it) })
    }

    override fun removeProfile(profileId: Int) = write(profileId, null)

    override fun clearAllProfiles() = preferences.clear()
}
