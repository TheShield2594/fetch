package com.fetch.desktop.youtube

import com.fetch.tv.data.youtube.YouTubeAccount
import com.fetch.tv.data.youtube.YouTubeApiClient
import com.fetch.tv.data.youtube.YouTubeAuthStorage
import com.fetch.tv.data.youtube.YouTubeDeviceCode
import com.fetch.tv.data.youtube.YouTubeLinkKind
import com.fetch.tv.data.youtube.YouTubeStoredAuth
import com.fetch.tv.data.youtube.YouTubeToken
import com.fetch.tv.data.youtube.YouTubeTokenPollResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * YouTube for the desktop app: the shared [YouTubeApiClient] (the same one the TV app uses) with
 * the sign-in kept in the profile's [YouTubeAuthStore].
 *
 * Sign-in is Google's device flow for TVs: Fetch shows a code, the user enters it at
 * youtube.com/activate, and Fetch polls until it's approved. Nothing in it needs a TV, so the
 * desktop signs in the same way. Playback isn't here: mpv plays the watch page through yt-dlp.
 */
class YouTubeClient(
    http: OkHttpClient,
    private val auth: YouTubeAuthStore,
    locale: () -> Locale = { Locale.getDefault() },
    private val now: () -> Long = System::currentTimeMillis
) : YouTubeApiClient(http, AuthStoreStorage(auth), locale, now) {

    val signedIn: Boolean get() = auth.load().isSignedIn

    /**
     * Counts sign-in attempts. A sign-out or a cancelled sign-in moves it on (under the store's
     * lock), so an approval that answers after either is never saved.
     */
    @Volatile
    private var attempt = 0L

    /** The refresh token an attempt saved before it finished (while it reads the account name). */
    @Volatile
    private var unfinished: String? = null

    override fun libraryShortcutTitle(kind: YouTubeLinkKind): String? = when (kind) {
        YouTubeLinkKind.WATCH_LATER -> "Watch later"
        YouTubeLinkKind.LIKED -> "Liked videos"
        else -> null
    }

    // --- Sign-in ---

    /** Asks Google for a code to show; the user enters it at [YouTubeDeviceCode.verificationUrl]. */
    suspend fun startDeviceAuth(): YouTubeDeviceCode = requestDeviceCode()

    /** One poll of the pending sign-in; on approval the tokens and the account name are saved. */
    suspend fun pollDeviceToken(code: YouTubeDeviceCode): YouTubeTokenPollResult = withContext(Dispatchers.IO) {
        val started = attempt
        val result = try {
            exchangeDeviceCode(code.deviceCode, code.clientId, code.clientSecret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext YouTubeTokenPollResult.Failed(e.message ?: "Network error")
        }
        when (result) {
            is YouTubeApiClient.TokenPoll.Token -> {
                val token = result.token
                var saved = false
                auth.update { current ->
                    if (attempt != started) return@update current
                    saved = true
                    unfinished = token.refreshToken
                    YouTubeAuth(clientId = code.clientId, clientSecret = code.clientSecret).withToken(token, now())
                }
                // Signed out or cancelled while Google answered: the approval is dropped.
                if (!saved) return@withContext YouTubeTokenPollResult.Denied
                val account = try {
                    fetchAccount(token.accessToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                // Only onto this sign-in: a sign-out meanwhile stays signed out.
                // Only onto this sign-in and its own channel: a sign-out or channel pick meanwhile stays.
                account?.let { a ->
                    auth.update { current ->
                        if (current.refreshToken == token.refreshToken && current.pageId == null) current.withAccount(a) else current
                    }
                }
                // Finished: a cancel from here on no longer undoes it. A cancel meanwhile already did.
                var kept = false
                auth.update { current ->
                    if (unfinished == token.refreshToken) unfinished = null
                    kept = attempt == started && current.refreshToken == token.refreshToken
                    current
                }
                if (!kept) return@withContext YouTubeTokenPollResult.Denied
                YouTubeTokenPollResult.Approved(account?.name)
            }
            is YouTubeApiClient.TokenPoll.Status -> result.status
        }
    }

    /** Signs this profile out, and tells Google to drop the sign-in. */
    suspend fun signOut() {
        signOutLocally()?.let { revokeSignIn(it) }
    }

    /**
     * Drops the saved sign-in at once, and any sign-in still waiting for approval; returns the
     * refresh token to hand to [revokeSignIn], or null when there was none.
     */
    fun signOutLocally(): String? {
        var token: String? = null
        auth.update { current ->
            attempt++
            unfinished = null
            token = current.refreshToken
            YouTubeAuth()
        }
        return token?.takeIf { it.isNotBlank() }
    }

    /** Stops a sign-in waiting for approval from saving it, should the approval still arrive. */
    fun cancelSignIn() {
        auth.update { current ->
            attempt++
            // An approval saved while the account name was still loading is undone too.
            val saved = unfinished
            unfinished = null
            if (saved != null && current.refreshToken == saved) YouTubeAuth() else current
        }
    }

    /** Tells Google to drop [refreshToken]'s sign-in. */
    suspend fun revokeSignIn(refreshToken: String) = withContext(Dispatchers.IO) { revoke(refreshToken) }

    /** Acts as [account] from now on: feeds, history and search all come from that channel. */
    fun selectAccount(account: YouTubeAccount) {
        auth.update { it.withAccount(account) }
    }

    /** Fetches the account name again, for a sign-in made while it couldn't be read. */
    suspend fun refreshAccount() {
        val (signIn, account) = currentAccount() ?: return
        // Only while the same sign-in still acts as the same channel it was looked up for.
        auth.update { current ->
            if (current.refreshToken == signIn.refreshToken && current.pageId == signIn.pageId) current.withAccount(account) else current
        }
    }
}

/** [token] in place of this sign-in's access token; a token answer may carry a new refresh token. */
internal fun YouTubeAuth.withToken(token: YouTubeToken, nowMs: Long) = copy(
    accessToken = token.accessToken,
    refreshToken = token.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken,
    accessTokenExpiresAt = nowMs + token.expiresInSeconds * 1000L
)

/**
 * The shared client's view of a [YouTubeAuthStore]. Every write goes through [YouTubeAuthStore.update]
 * and only lands while the store still holds the sign-in it was worked out from, so a sign-out or a
 * channel switch made during a token refresh is never undone by it.
 */
private class AuthStoreStorage(private val auth: YouTubeAuthStore) : YouTubeAuthStorage {
    override suspend fun read(): YouTubeStoredAuth = auth.load().let {
        YouTubeStoredAuth(
            location = null,
            accessToken = it.accessToken,
            refreshToken = it.refreshToken,
            accessTokenExpiresAt = it.accessTokenExpiresAt,
            clientId = it.clientId,
            clientSecret = it.clientSecret,
            pageId = it.pageId
        )
    }

    override suspend fun saveRefreshedToken(from: YouTubeStoredAuth, token: YouTubeToken, nowMs: Long) =
        auth.update { current -> if (current.refreshToken == from.refreshToken) current.withToken(token, nowMs) else current }

    override suspend fun expireAccessToken(from: YouTubeStoredAuth) = auth.update { current ->
        if (current.refreshToken == from.refreshToken && current.accessToken == from.accessToken) {
            current.copy(accessTokenExpiresAt = 0L)
        } else {
            current
        }
    }

    override suspend fun clearRevokedSignIn(from: YouTubeStoredAuth) =
        auth.update { current -> if (current.refreshToken == from.refreshToken) YouTubeAuth() else current }
}
