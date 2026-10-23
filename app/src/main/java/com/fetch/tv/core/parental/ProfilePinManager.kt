package com.fetch.tv.core.parental

import android.util.Log
import com.fetch.tv.core.auth.AuthManager
import com.fetch.tv.core.sync.ProfileSyncService
import com.fetch.tv.core.sync.SetProfilePinResult
import com.fetch.tv.data.local.PinAttemptState
import com.fetch.tv.data.local.ProfileLockStateDataStore
import com.fetch.tv.domain.model.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfilePinManager"

data class PinVerifyResult(
    val unlocked: Boolean,
    val retryAfterSeconds: Int = 0
)

/** Profile whose PIN can reset every other profile's PIN. */
const val PARENT_PROFILE_ID = 1

sealed interface ResetPinResult {
    data object Success : ResetPinResult
    /** The main profile has no PIN, so nothing proves who is asking. */
    data object ParentPinRequired : ResetPinResult
    data class ParentPinIncorrect(val retryAfterSeconds: Int = 0) : ResetPinResult
    data class Failure(val throwable: Throwable) : ResetPinResult
}

/**
 * The sync server's copy of profile PINs. Only used when sync is configured and signed in;
 * the local PIN never depends on it.
 */
interface RemoteProfilePins {
    val isAvailable: Boolean
    suspend fun pullStates(): Result<Map<Int, Boolean>>
    suspend fun setPin(profileId: Int, pin: String, currentPin: String?): SetProfilePinResult
    suspend fun clearPin(profileId: Int, currentPin: String?): Result<Unit>
    suspend fun verifyPin(profileId: Int, pin: String): Result<PinVerifyResult>
}

class SyncServerProfilePins @Inject constructor(
    private val authManager: AuthManager,
    private val profileSyncService: ProfileSyncService
) : RemoteProfilePins {
    override val isAvailable: Boolean
        get() = authManager.authState.value is AuthState.FullAccount

    override suspend fun pullStates(): Result<Map<Int, Boolean>> = profileSyncService.pullProfileLockStates()

    override suspend fun setPin(profileId: Int, pin: String, currentPin: String?): SetProfilePinResult =
        profileSyncService.setProfilePin(profileId, pin, currentPin)

    override suspend fun clearPin(profileId: Int, currentPin: String?): Result<Unit> =
        profileSyncService.clearProfilePin(profileId, currentPin)

    override suspend fun verifyPin(profileId: Int, pin: String): Result<PinVerifyResult> =
        profileSyncService.verifyProfilePin(profileId, pin).map {
            PinVerifyResult(unlocked = it.unlocked, retryAfterSeconds = it.retryAfterSeconds)
        }
}

/**
 * Profile PINs that work offline. The PIN is checked against a salted hash stored on the
 * device; when sync is signed in, changes are mirrored to the sync server, and a PIN that so
 * far only exists on the server is adopted locally the first time it is entered correctly.
 */
@Singleton
class ProfilePinManager internal constructor(
    private val store: ProfileLockStateDataStore,
    private val remote: RemoteProfilePins,
    private val clock: () -> Long
) {
    @Inject
    constructor(
        store: ProfileLockStateDataStore,
        remote: SyncServerProfilePins
    ) : this(store, remote, System::currentTimeMillis)

    private val mutex = Mutex()

    /** Profiles that require a PIN. */
    val pinEnabled: Flow<Map<Int, Boolean>> = store.pinEnabled

    suspend fun hasPin(profileId: Int): Boolean = store.pinEnabled.first()[profileId] == true

    suspend fun setPin(profileId: Int, pin: String, currentPin: String? = null): SetProfilePinResult {
        if (!ProfilePinHasher.isValidPin(pin)) {
            return SetProfilePinResult.Failure(IllegalArgumentException("PIN must be 4 to 8 digits"))
        }
        if (hasPin(profileId)) {
            if (currentPin.isNullOrEmpty()) return SetProfilePinResult.CurrentPinRequired
            val verified = verifyPin(profileId, currentPin).getOrNull()
            if (verified?.unlocked != true) return SetProfilePinResult.CurrentPinRequired
        }
        val hash = withContext(Dispatchers.Default) { ProfilePinHasher.hash(pin) }
        mutex.withLock {
            store.setLocalPinHash(profileId, hash)
            store.setAttemptState(profileId, PinAttemptState())
        }
        mirror("set") {
            if (remote.setPin(profileId, pin, currentPin) is SetProfilePinResult.Success) {
                store.setPinEnabled(profileId, true)
            }
        }
        return SetProfilePinResult.Success
    }

    /**
     * Replaces a forgotten PIN on [profileId] with [newPin], proven by the main profile's PIN.
     * Also ends any lockout. The main profile's own PIN can't be reset this way.
     */
    suspend fun resetPin(profileId: Int, newPin: String, parentPin: String): ResetPinResult {
        if (profileId == PARENT_PROFILE_ID) {
            return ResetPinResult.Failure(IllegalArgumentException("The main profile's PIN can't be reset"))
        }
        if (!ProfilePinHasher.isValidPin(newPin)) {
            return ResetPinResult.Failure(IllegalArgumentException("PIN must be 4 to 8 digits"))
        }
        // Without a main PIN verifyPin would accept anything.
        if (!hasPin(PARENT_PROFILE_ID)) return ResetPinResult.ParentPinRequired
        val verified = verifyPin(PARENT_PROFILE_ID, parentPin).getOrElse { return ResetPinResult.Failure(it) }
        if (!verified.unlocked) return ResetPinResult.ParentPinIncorrect(verified.retryAfterSeconds)
        val hash = withContext(Dispatchers.Default) { ProfilePinHasher.hash(newPin) }
        mutex.withLock {
            store.setLocalPinHash(profileId, hash)
            store.setAttemptState(profileId, PinAttemptState())
        }
        // The local hash is checked first, so the new PIN works here even if the server keeps the old one.
        mirror("reset") {
            if (remote.setPin(profileId, newPin, null) is SetProfilePinResult.Success) {
                store.setPinEnabled(profileId, true)
            }
        }
        return ResetPinResult.Success
    }

    suspend fun clearPin(profileId: Int, currentPin: String?): Result<PinVerifyResult> {
        if (!hasPin(profileId)) return Result.success(PinVerifyResult(unlocked = true))
        val verified = verifyPin(profileId, currentPin.orEmpty())
        val result = verified.getOrNull() ?: return verified
        if (!result.unlocked) return verified
        mutex.withLock {
            store.setLocalPinHash(profileId, null)
            store.setAttemptState(profileId, PinAttemptState())
        }
        store.setPinEnabled(profileId, false)
        mirror("clear") { remote.clearPin(profileId, currentPin) }
        return verified
    }

    /**
     * Checks [pin] for [profileId]. A profile without a PIN is always unlocked. A failure
     * result means the PIN only exists on the sync server and it could not be reached.
     */
    suspend fun verifyPin(profileId: Int, pin: String): Result<PinVerifyResult> {
        val localHash = store.localPinHash(profileId)
        if (localHash == null) {
            val remoteEnabled = store.remotePinEnabled.first()[profileId] == true
            if (!remoteEnabled) return Result.success(PinVerifyResult(unlocked = true))
            if (!remote.isAvailable) return Result.failure(IllegalStateException("Sync server not available"))
            val remoteResult = runCatchingNonCancel { remote.verifyPin(profileId, pin).getOrThrow() }
            remoteResult.getOrNull()?.takeIf { it.unlocked }?.let {
                val hash = withContext(Dispatchers.Default) { ProfilePinHasher.hash(pin) }
                store.setLocalPinHash(profileId, hash)
            }
            return remoteResult
        }

        return mutex.withLock {
            val now = clock()
            val attempts = store.attemptState(profileId)
            val retryAfter = PinAttemptPolicy.retryAfterSeconds(attempts.lockedUntilMs, now)
            if (retryAfter > 0) {
                return@withLock Result.success(PinVerifyResult(unlocked = false, retryAfterSeconds = retryAfter))
            }
            val matches = withContext(Dispatchers.Default) { ProfilePinHasher.verify(pin, localHash) }
            if (matches) {
                store.setAttemptState(profileId, PinAttemptState())
                Result.success(PinVerifyResult(unlocked = true))
            } else {
                val failures = attempts.failures + 1
                val lockout = PinAttemptPolicy.lockoutAfterFailures(failures)
                val lockedUntil = if (lockout > 0) now + lockout else 0L
                store.setAttemptState(profileId, PinAttemptState(failures, lockedUntil))
                Result.success(
                    PinVerifyResult(
                        unlocked = false,
                        retryAfterSeconds = PinAttemptPolicy.retryAfterSeconds(lockedUntil, now)
                    )
                )
            }
        }
    }

    /** Refreshes which profiles the sync server reports as locked. No-op without sync. */
    suspend fun refreshRemoteStates(): Result<Unit> {
        if (!remote.isAvailable) return Result.success(Unit)
        return remote.pullStates().map { states -> store.replaceAll(states) }
    }

    suspend fun removeProfile(profileId: Int) {
        store.removeProfile(profileId)
    }

    private suspend fun mirror(action: String, block: suspend () -> Unit) {
        if (!remote.isAvailable) return
        runCatchingNonCancel { block() }
            .onFailure { Log.w(TAG, "Failed to mirror PIN $action to sync server", it) }
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}
