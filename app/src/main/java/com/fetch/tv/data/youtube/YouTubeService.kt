package com.fetch.tv.data.youtube

import android.util.Log
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.core.network.SharedHttpClient
import com.fetch.tv.data.local.YouTubeAuthDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YouTubeService"

/**
 * YouTube for the TV app: the shared [YouTubeApiClient] with the sign-in kept per profile in
 * [YouTubeAuthDataStore], plus the device sign-in flow as Settings drives it.
 *
 * Sign-in is Google's device flow for TVs: the TV shows a code, the user enters it at
 * youtube.com/activate on a phone or computer, and the TV polls until it's approved. Playback goes
 * through the trailer extractor, which needs no sign-in.
 */
@Singleton
class YouTubeService @Inject constructor(
    private val authDataStore: YouTubeAuthDataStore
) : YouTubeApiClient(youTubeHttpClient(), authDataStore.storage) {

    override fun log(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }

    // --- Sign-in ---

    suspend fun startDeviceAuth(): Result<YouTubeDeviceCode> = withContext(Dispatchers.IO) {
        try {
            val code = requestDeviceCode()
            authDataStore.saveDeviceFlow(code, code.clientId, code.clientSecret)
            Result.success(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not start YouTube sign-in: ${e.message}")
            Result.failure(e)
        }
    }

    /** One poll of the profile's pending sign-in; on approval its tokens and account are saved to that profile. */
    suspend fun pollDeviceToken(): YouTubeTokenPollResult = withContext(Dispatchers.IO) {
        // The profile first, then its flow: every write below goes back to the profile that asked.
        val profileId = authDataStore.storage.read().location as Int
        val state = authDataStore.stateOf(profileId)
        val deviceCode = state.deviceCode ?: return@withContext YouTubeTokenPollResult.Expired
        val result = try {
            exchangeDeviceCode(
                deviceCode,
                state.clientId ?: YouTubeInnerTube.CLIENT_ID,
                state.clientSecret ?: YouTubeInnerTube.CLIENT_SECRET
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext YouTubeTokenPollResult.Failed(e.message ?: "Network error")
        }
        when (result) {
            is YouTubeApiClient.TokenPoll.Token -> {
                authDataStore.saveToken(result.token, profileId = profileId)
                authDataStore.clearDeviceFlow(profileId)
                val account = try {
                    fetchAccount(result.token.accessToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                // Only onto this sign-in: a sign-out or channel pick meanwhile stays as it is.
                account?.let {
                    authDataStore.saveAccountIf(profileId, it, YouTubeSignInId(profileId, result.token.refreshToken, state.pageId))
                }
                YouTubeTokenPollResult.Approved(account?.name)
            }
            is YouTubeApiClient.TokenPoll.Status -> {
                when (val status = result.status) {
                    is YouTubeTokenPollResult.SlowDown -> authDataStore.updatePollInterval(status.intervalSeconds, profileId)
                    YouTubeTokenPollResult.Expired, YouTubeTokenPollResult.Denied -> authDataStore.clearDeviceFlow(profileId)
                    else -> Unit
                }
                result.status
            }
        }
    }

    suspend fun cancelDeviceAuth() = authDataStore.clearDeviceFlow()

    /** Signs out of this profile, and tells Google to drop the sign-in. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val token = authDataStore.getCurrentState().refreshToken
        authDataStore.clearAuth()
        if (!token.isNullOrBlank()) revoke(token)
    }

    /** Acts as [account] from now on: feeds, history and search all come from that channel. */
    suspend fun selectAccount(account: YouTubeAccount) {
        authDataStore.saveAccount(account.copy(isSelected = true))
    }

    /** Fetches the account name again, for profiles that signed in while it couldn't be read. */
    suspend fun refreshAccount() {
        val (signIn, account) = currentAccount() ?: return
        val profileId = signIn.location as? Int ?: return
        authDataStore.saveAccountIf(profileId, account, signIn.identity)
    }
}

private fun youTubeHttpClient(): OkHttpClient =
    SharedHttpClient.root.newBuilder()
        // Its own dispatcher: requests wait on it without holding a thread, and a slow YouTube
        // never queues behind (or in front of) the rest of the app's calls to other hosts.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .dns(IPv4FirstDns())
        .socketFactory(NoDelaySocketFactory)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
