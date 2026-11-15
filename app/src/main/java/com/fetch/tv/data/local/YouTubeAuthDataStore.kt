package com.fetch.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.youtube.YouTubeAccount
import com.fetch.tv.data.youtube.YouTubeAuthStorage
import com.fetch.tv.data.youtube.YouTubeDeviceCode
import com.fetch.tv.data.youtube.YouTubeStoredAuth
import com.fetch.tv.data.youtube.YouTubeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private fun store(profileId: Int) =
        factory.get(profileId, FEATURE)

    val state: Flow<YouTubeAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { preferences -> stateOf(profileId, preferences) }
    }.catch { error ->
        // The encrypted tokens couldn't be read (a keystore fault): show signed out rather than
        // crash whatever collects this, start-up's sidebar check among them.
        android.util.Log.w("YouTubeAuthDataStore", "Could not read the YouTube sign-in", error)
        emit(YouTubeAuthState())
    }

    private suspend fun stateOf(profileId: Int, preferences: androidx.datastore.preferences.core.Preferences): YouTubeAuthState {
        val tokens = withContext(Dispatchers.IO) { credentials.read(profileId) }
        return YouTubeAuthState(
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

    /** [profileId]'s sign-in, whichever profile is active. */
    suspend fun stateOf(profileId: Int): YouTubeAuthState = stateOf(profileId, store(profileId).data.first())

    // Token writes read, change and write the encrypted tokens; one at a time, so none is lost.
    private val tokenMutex = Mutex()

    /** Whether the active profile is signed in. The YouTube sidebar entry shows only while true. */
    val isSignedIn: Flow<Boolean> = state.map { it.isSignedIn }.distinctUntilChanged()

    suspend fun getCurrentState(): YouTubeAuthState = state.first()

    /** Saves a newly issued token for [profileId] (the active profile unless named). */
    suspend fun saveToken(
        token: YouTubeToken,
        nowMillis: Long = System.currentTimeMillis(),
        profileId: Int = profileManager.activeProfileId.value
    ) {
        updateTokens(profileId) { current -> current.with(token, nowMillis) }
    }

    /** Marks the access token as spent, so the next request refreshes it first. */
    suspend fun expireAccessToken() {
        updateTokens(profileManager.activeProfileId.value) { it.copy(accessTokenExpiresAt = null) }
    }

    /**
     * Changes [profileId]'s tokens. [transform] sees the tokens as they are and returns null to leave
     * them, so a write worked out from an older read can step aside.
     */
    private suspend fun updateTokens(profileId: Int, transform: (YouTubeTokens) -> YouTubeTokens?) {
        val changed = tokenMutex.withLock {
            withContext(Dispatchers.IO) {
                transform(credentials.read(profileId))?.also { credentials.write(profileId, it) } != null
            }
        }
        if (changed) bumpRevision(profileId)
    }

    private suspend fun bumpRevision(profileId: Int) {
        store(profileId).edit { preferences -> preferences[tokenRevisionKey] = (preferences[tokenRevisionKey] ?: 0L) + 1 }
    }

    private fun YouTubeTokens.with(token: YouTubeToken, nowMillis: Long) = YouTubeTokens(
        accessToken = token.accessToken,
        // A refresh response leaves the refresh token out; keep the one already saved.
        refreshToken = token.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken,
        accessTokenExpiresAt = nowMillis + token.expiresInSeconds * 1000L
    )

    /**
     * Where the shared YouTube client keeps the sign-in. Each read names the profile it came from,
     * and each write goes back to that profile, and only while it still holds the same sign-in: a
     * profile switch, a sign-out or a new sign-in during a request always wins.
     */
    val storage: YouTubeAuthStorage = object : YouTubeAuthStorage {
        override suspend fun read(): YouTubeStoredAuth {
            val profileId = profileManager.activeProfileId.value
            val state = stateOf(profileId)
            return YouTubeStoredAuth(
                location = profileId,
                accessToken = state.accessToken,
                refreshToken = state.refreshToken,
                accessTokenExpiresAt = state.accessTokenExpiresAt,
                clientId = state.clientId,
                clientSecret = state.clientSecret,
                pageId = state.pageId
            )
        }

        override suspend fun saveRefreshedToken(from: YouTubeStoredAuth, token: YouTubeToken, nowMs: Long) {
            val profileId = from.location as? Int ?: return
            updateTokens(profileId) { current -> current.takeIf { it.refreshToken == from.refreshToken }?.with(token, nowMs) }
        }

        override suspend fun expireAccessToken(from: YouTubeStoredAuth) {
            val profileId = from.location as? Int ?: return
            updateTokens(profileId) { current ->
                current.takeIf { it.refreshToken == from.refreshToken && it.accessToken == from.accessToken }
                    ?.copy(accessTokenExpiresAt = null)
            }
        }

        override suspend fun clearRevokedSignIn(from: YouTubeStoredAuth) {
            val profileId = from.location as? Int ?: return
            val cleared = tokenMutex.withLock {
                withContext(Dispatchers.IO) {
                    val current = credentials.read(profileId)
                    (current.refreshToken == from.refreshToken).also { if (it) credentials.write(profileId, null) }
                }
            }
            if (cleared) store(profileId).edit { preferences -> preferences.clear() }
        }
    }

    suspend fun saveAccount(account: YouTubeAccount, profileId: Int = profileManager.currentProfileId()) {
        store(profileId).edit { preferences ->
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
        store(profileManager.currentProfileId()).edit { preferences ->
            preferences[clientIdKey] = clientId
            preferences[clientSecretKey] = clientSecret
            preferences[deviceCodeKey] = code.deviceCode
            preferences[userCodeKey] = code.userCode
            preferences[verificationUrlKey] = code.verificationUrl
            preferences[deviceCodeExpiresAtKey] = nowMillis + code.expiresInSeconds * 1000L
            preferences[pollIntervalKey] = code.intervalSeconds
        }
    }

    suspend fun updatePollInterval(seconds: Int, profileId: Int = profileManager.currentProfileId()) {
        store(profileId).edit { preferences -> preferences[pollIntervalKey] = seconds }
    }

    suspend fun clearDeviceFlow(profileId: Int = profileManager.currentProfileId()) {
        store(profileId).edit { preferences ->
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(deviceCodeExpiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }

    suspend fun clearAuth() {
        val profileId = profileManager.activeProfileId.value
        tokenMutex.withLock { withContext(Dispatchers.IO) { credentials.write(profileId, null) } }
        store(profileId).edit { preferences -> preferences.clear() }
    }
}
