package com.fetch.tv.data.website

import com.fetch.tv.core.parental.PinVerifyResult
import com.fetch.tv.core.parental.RemoteProfilePins
import com.fetch.tv.core.sync.SetProfilePinResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile PINs checked by the Fetch website. The website never hands out a PIN or its hash:
 * the TV sends the PIN it was given and gets back yes or no, plus a profile token that lets
 * it read that profile's data (its collections, for instance) for the next few hours.
 */
@Singleton
class FetchWebsiteProfilePins @Inject constructor(
    private val store: FetchWebsiteStore,
    private val client: FetchWebsiteClient,
    private val sync: FetchWebsiteSyncService
) : RemoteProfilePins {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val isAvailable: Boolean get() = store.isPaired

    override suspend fun pullStates(): Result<Map<Int, Boolean>> = runCatching {
        client.profiles().associate { it.slot to it.hasPin }
    }

    override suspend fun setPin(profileId: Int, pin: String, currentPin: String?): SetProfilePinResult {
        val remote = sync.remoteFor(profileId) ?: return SetProfilePinResult.Failure(IllegalStateException("No such profile on the website"))
        return try {
            client.setPin(remote.id, pin, currentPin, sync.profileToken(remote.id))
            SetProfilePinResult.Success
        } catch (error: FetchWebsiteException) {
            if (error.status == 401) SetProfilePinResult.CurrentPinRequired else SetProfilePinResult.Failure(error)
        } catch (error: Exception) {
            SetProfilePinResult.Failure(error)
        }
    }

    override suspend fun clearPin(profileId: Int, currentPin: String?): Result<Unit> = runCatching {
        val remote = sync.remoteFor(profileId) ?: error("No such profile on the website")
        client.setPin(remote.id, null, currentPin, sync.profileToken(remote.id))
    }

    override suspend fun verifyPin(profileId: Int, pin: String): Result<PinVerifyResult> = runCatching {
        val remote = sync.remoteFor(profileId) ?: error("No such profile on the website")
        val token = client.verifyPin(remote.id, pin)
        if (token != null) sync.rememberProfileToken(remote.id, token)
        PinVerifyResult(unlocked = token != null)
    }

    // An unlock that the TV's own copy of the PIN answered still needs the website's profile
    // token. Fetched in the background so an unreachable website never slows an unlock down.
    override suspend fun onVerifiedLocally(profileId: Int, pin: String) {
        scope.launch {
            runCatching {
                val remote = sync.remoteFor(profileId) ?: return@runCatching
                if (!remote.hasPin) return@runCatching
                client.verifyPin(remote.id, pin)?.let { sync.rememberProfileToken(remote.id, it) }
                sync.pullCollections()
            }
        }
    }
}
