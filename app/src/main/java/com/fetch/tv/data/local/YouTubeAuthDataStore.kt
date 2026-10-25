package com.fetch.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.youtube.YouTubeAccount
import com.fetch.tv.data.youtube.YouTubeDeviceCode
import com.fetch.tv.data.youtube.YouTubeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class YouTubeAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessTokenExpiresAt: Long? = null,
    val accountName: String? = null,
    val accountHandle: String? = null,
    val accountPhotoUrl: String? = null,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val deviceCodeExpiresAt: Long? = null,
    val pollInterval: Int? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    /** The brand account requests act as; null for the Google account's own channel. */
    val pageId: String? = null
) {
    val isSignedIn: Boolean
        get() = !refreshToken.isNullOrBlank()
}

/**
 * The YouTube sign-in of each profile, so each profile signs in to its own Google account. The
 * tokens live encrypted in [YouTubeCredentialStore]; this DataStore keeps the rest, plus a revision
 * that changes with every token write so [state] follows the tokens too.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val credentials: YouTubeCredentialStore
) {
    companion object {
        private const val FEATURE = "youtube_auth_store"
    }

    private val tokenRevisionKey = longPreferencesKey("token_revision")
    private val accountNameKey = stringPreferencesKey("account_name")
    private val accountHandleKey = stringPreferencesKey("account_handle")
    private val accountPhotoUrlKey = stringPreferencesKey("account_photo_url")

    private val deviceCodeKey = stringPreferencesKey("device_code")
    private val userCodeKey = stringPreferencesKey("user_code")
    private val verificationUrlKey = stringPreferencesKey("verification_url")
    private val deviceCodeExpiresAtKey = longPreferencesKey("device_code_expires_at")
    private val pollIntervalKey = intPreferencesKey("poll_interval")

    // The TV client the sign-in was made with. A refresh token only works with the client it was issued to.
    private val clientIdKey = stringPreferencesKey("client_id")
    private val clientSecretKey = stringPreferencesKey("client_secret")
    private val pageIdKey = stringPreferencesKey("page_id")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val state: Flow<YouTubeAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { preferences ->
            val tokens = withContext(Dispatchers.IO) { credentials.read(profileId) }
            YouTubeAuthState(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accessTokenExpiresAt = tokens.accessTokenExpiresAt,
                accountName = preferences[accountNameKey],
                accountHandle = preferences[accountHandleKey],
                accountPhotoUrl = preferences[accountPhotoUrlKey],
                deviceCode = preferences[deviceCodeKey],
                userCode = preferences[userCodeKey],
                verificationUrl = preferences[verificationUrlKey],
                deviceCodeExpiresAt = preferences[deviceCodeExpiresAtKey],
                pollInterval = preferences[pollIntervalKey],
                clientId = preferences[clientIdKey],
                clientSecret = preferences[clientSecretKey],
                pageId = preferences[pageIdKey]
            )
        }
    }

    /** Whether the active profile is signed in. The YouTube sidebar entry shows only while true. */
    val isSignedIn: Flow<Boolean> = state.map { it.isSignedIn }.distinctUntilChanged()

    suspend fun getCurrentState(): YouTubeAuthState = state.first()

    suspend fun saveToken(token: YouTubeToken, nowMillis: Long = System.currentTimeMillis()) {
        updateTokens { current ->
            YouTubeTokens(
                accessToken = token.accessToken,
                // A refresh response leaves the refresh token out; keep the one already saved.
                refreshToken = token.refreshToken?.takeIf { it.isNotBlank() } ?: current.refreshToken,
                accessTokenExpiresAt = nowMillis + token.expiresInSeconds * 1000L
            )
        }
    }

    /** Marks the access token as spent, so the next request refreshes it first. */
    suspend fun expireAccessToken() {
        updateTokens { it.copy(accessTokenExpiresAt = null) }
    }

    private suspend fun updateTokens(transform: (YouTubeTokens) -> YouTubeTokens) {
        val profileId = profileManager.activeProfileId.value
        withContext(Dispatchers.IO) { credentials.write(profileId, transform(credentials.read(profileId))) }
        store(profileId).edit { preferences -> preferences[tokenRevisionKey] = (preferences[tokenRevisionKey] ?: 0L) + 1 }
    }

    suspend fun saveAccount(account: YouTubeAccount) {
        store().edit { preferences ->
            account.pageId?.let { preferences[pageIdKey] = it } ?: preferences.remove(pageIdKey)
            account.name?.let { preferences[accountNameKey] = it } ?: preferences.remove(accountNameKey)
            account.handle?.let { preferences[accountHandleKey] = it } ?: preferences.remove(accountHandleKey)
            account.photoUrl?.let { preferences[accountPhotoUrlKey] = it } ?: preferences.remove(accountPhotoUrlKey)
        }
    }

    suspend fun saveDeviceFlow(
        code: YouTubeDeviceCode,
        clientId: String,
        clientSecret: String,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        store().edit { preferences ->
            preferences[clientIdKey] = clientId
            preferences[clientSecretKey] = clientSecret
            preferences[deviceCodeKey] = code.deviceCode
            preferences[userCodeKey] = code.userCode
            preferences[verificationUrlKey] = code.verificationUrl
            preferences[deviceCodeExpiresAtKey] = nowMillis + code.expiresInSeconds * 1000L
            preferences[pollIntervalKey] = code.intervalSeconds
        }
    }

    suspend fun updatePollInterval(seconds: Int) {
        store().edit { preferences -> preferences[pollIntervalKey] = seconds }
    }

    suspend fun clearDeviceFlow() {
        store().edit { preferences ->
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(deviceCodeExpiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }

    suspend fun clearAuth() {
        val profileId = profileManager.activeProfileId.value
        withContext(Dispatchers.IO) { credentials.write(profileId, null) }
        store(profileId).edit { preferences -> preferences.clear() }
    }
}
