package com.fetch.tv.data.mal

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A profile's MyAnimeList sign-in. The access token lasts about a month and is renewed with the refresh token. */
data class MalAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** Epoch ms the access token stops working; 0 when unknown. */
    val expiresAt: Long = 0L,
    val userId: Long? = null,
    val username: String? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank() && userId != null
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MalAuthStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val api: MalApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val userIdKey = longPreferencesKey("user_id")
    private val usernameKey = stringPreferencesKey("username")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    val state: Flow<MalAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { prefs -> prefs.toState() }
    }

    val isAuthenticated: StateFlow<Boolean> = state
        .map { it.isAuthenticated }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun activeProfileId(): Int = profileManager.activeProfileId.value

    suspend fun current(profileId: Int = activeProfileId()): MalAuthState = store(profileId).data.first().toState()

    suspend fun save(profileId: Int, tokens: MalTokens, user: MalUser) {
        store(profileId).edit { prefs ->
            prefs[accessKey] = tokens.accessToken
            prefs[refreshKey] = tokens.refreshToken
            prefs[expiresAtKey] = tokens.expiresAt
            prefs[userIdKey] = user.id
            prefs[usernameKey] = user.name
        }
    }

    suspend fun clear(profileId: Int = activeProfileId()) {
        store(profileId).edit { it.clear() }
    }

    /**
     * An access token for [profileId] that is good for at least a day, renewing it first when
     * needed. Null when signed out, or when MAL refused the refresh token (then the sign-in is
     * removed, so the TV shows it as disconnected rather than failing quietly).
     */
    suspend fun validToken(profileId: Int): String? = refreshLock.withLock {
        val state = current(profileId)
        if (!state.isAuthenticated) return null
        val token = state.accessToken ?: return null
        if (state.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) return token
        val refreshed = try {
            api.refresh(state.refreshToken ?: return null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: MalApiException) {
            if (error.unauthorized) {
                Log.w(TAG, "MyAnimeList refused the refresh token; signing out", error)
                // Only if this is still the sign-in that was refused: the phone may have signed in again meanwhile.
                var cleared = false
                store(profileId).edit { prefs ->
                    if (prefs[refreshKey] == state.refreshToken) {
                        prefs.clear()
                        cleared = true
                    }
                }
                // A sign-in that replaced the refused one is still good: use it.
                return if (cleared) null else current(profileId).accessToken
            }
            Log.w(TAG, "MyAnimeList token refresh failed", error)
            // Still usable until it actually expires.
            return token.takeIf { state.expiresAt > System.currentTimeMillis() }
        } catch (error: Exception) {
            Log.w(TAG, "MyAnimeList token refresh failed", error)
            return token.takeIf { state.expiresAt > System.currentTimeMillis() }
        }
        var applied = false
        store(profileId).edit { prefs ->
            // A new sign-in landed while the refresh was out: keep it, and drop this stale result.
            if (prefs[refreshKey] != state.refreshToken) return@edit
            prefs[accessKey] = refreshed.accessToken
            prefs[refreshKey] = refreshed.refreshToken
            prefs[expiresAtKey] = refreshed.expiresAt
            applied = true
        }
        if (applied) refreshed.accessToken else current(profileId).accessToken
    }

    private fun androidx.datastore.preferences.core.Preferences.toState() = MalAuthState(
        accessToken = this[accessKey],
        refreshToken = this[refreshKey],
        expiresAt = this[expiresAtKey] ?: 0L,
        userId = this[userIdKey],
        username = this[usernameKey]
    )

    private companion object {
        const val FEATURE = "mal_auth_store"
        const val TAG = "MalAuth"
        const val REFRESH_MARGIN_MS = 24L * 60 * 60 * 1000
    }
}
