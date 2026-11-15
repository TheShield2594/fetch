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
                auth.update {
                    YouTubeAuth(clientId = code.clientId, clientSecret = code.clientSecret).withToken(token, now())
                }
                val account = try {
                    fetchAccount(token.accessToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                // Only onto this sign-in: a sign-out meanwhile stays signed out.
                account?.let { a -> auth.update { current -> if (current.refreshToken == token.refreshToken) current.withAccount(a) else current } }
                YouTubeTokenPollResult.Approved(account?.name)
            }
            is YouTubeApiClient.TokenPoll.Status -> result.status
        }
    }

    /** Signs this profile out, and tells Google to drop the sign-in. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        var token: String? = null
        auth.update { current -> token = current.refreshToken; YouTubeAuth() }
        token?.takeIf { it.isNotBlank() }?.let { revoke(it) }
    }

    /** Acts as [account] from now on: feeds, history and search all come from that channel. */
    fun selectAccount(account: YouTubeAccount) {
        auth.update { it.withAccount(account) }
    }

    /** Fetches the account name again, for a sign-in made while it couldn't be read. */
    suspend fun refreshAccount() {
        val refreshToken = auth.load().refreshToken
        currentAccount()?.let { account ->
            auth.update { current -> if (current.refreshToken == refreshToken) current.withAccount(account) else current }
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
