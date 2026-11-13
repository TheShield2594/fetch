package com.fetch.tv.data.anilist

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A profile's AniList sign-in: an access token from AniList's implicit grant (it lasts a year
 * and can't be refreshed, so an expired one just means signing in again).
 */
data class AniListAuthState(
    val accessToken: String? = null,
    /** Epoch ms; 0 when unknown. */
    val expiresAt: Long = 0L,
    val userId: Long? = null,
    val username: String? = null
) {
    fun isAuthenticated(nowMs: Long = System.currentTimeMillis()): Boolean =
        !accessToken.isNullOrBlank() && userId != null && (expiresAt <= 0L || expiresAt > nowMs)
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AniListAuthStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenKey = stringPreferencesKey("access_token")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val userIdKey = longPreferencesKey("user_id")
    private val usernameKey = stringPreferencesKey("username")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    val state: Flow<AniListAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { prefs ->
            AniListAuthState(
                accessToken = prefs[tokenKey],
                expiresAt = prefs[expiresAtKey] ?: 0L,
                userId = prefs[userIdKey],
                username = prefs[usernameKey]
            )
        }
    }

    val isAuthenticated: StateFlow<Boolean> = state
        .map { it.isAuthenticated() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun activeProfileId(): Int = profileManager.activeProfileId.value

    suspend fun current(profileId: Int = activeProfileId()): AniListAuthState =
        store(profileId).data.first().let { prefs ->
            AniListAuthState(
                accessToken = prefs[tokenKey],
                expiresAt = prefs[expiresAtKey] ?: 0L,
                userId = prefs[userIdKey],
                username = prefs[usernameKey]
            )
        }

    suspend fun save(profileId: Int, token: String, expiresAt: Long, userId: Long, username: String) {
        store(profileId).edit { prefs ->
            prefs[tokenKey] = token
            prefs[expiresAtKey] = expiresAt
            prefs[userIdKey] = userId
            prefs[usernameKey] = username
        }
    }

    suspend fun clear(profileId: Int = activeProfileId()) {
        store(profileId).edit { it.clear() }
    }

    private companion object {
        const val FEATURE = "anilist_auth_store"
    }
}
