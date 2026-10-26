package com.fetch.tv.data.website

import android.util.Log
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.CollectionsDataStore
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.local.ProfileLockStateDataStore
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.local.ProfileRestrictions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FetchWebsiteSync"

/** How long a profile token from the website is trusted here; the website issues them for 12 hours. */
private const val PROFILE_TOKEN_TTL_MS = 11L * 60 * 60 * 1000

/**
 * Profiles and collections against the Fetch website, for a TV signed in to one by pairing.
 *
 * While paired the website is where the account lives: profiles (with their colour, picture
 * and parental limit) are pulled from it and written back when edited here, and each
 * profile's collections are read and saved as one document, the way the TV stores them.
 * Parental limits are written into the TV's own restriction store, so they keep applying
 * when the website is out of reach.
 */
@Singleton
class FetchWebsiteSyncService @Inject constructor(
    private val store: FetchWebsiteStore,
    private val client: FetchWebsiteClient,
    private val profileManager: ProfileManager,
    private val profileDataStore: ProfileDataStore,
    private val restrictionsDataStore: ProfileRestrictionsDataStore,
    private val lockStateDataStore: ProfileLockStateDataStore,
    private val collectionsDataStore: CollectionsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var remoteProfiles: List<WebsiteSlottedProfile> = emptyList()
    private val profileTokens = ConcurrentHashMap<String, Pair<String, Long>>()

    private var watchJob: Job? = null
    private var collectionsPushJob: Job? = null
    private var profilesPushJob: Job? = null

    val isPaired: Boolean get() = store.isPaired

    /**
     * Checks the sign-in and pulls profiles and the active profile's collections. Safe to call
     * often (app start, returning to the foreground); does nothing when not paired.
     */
    fun requestSync() {
        if (!store.isPaired) return
        ensureWatching()
        scope.launch { syncNow() }
    }

    suspend fun syncNow(): Result<Unit> {
        // A change made here and not yet sent goes first, or the pull would undo it.
        profilesPushJob?.join()
        return syncMutex.withLock { syncLocked() }
    }

    private suspend fun syncLocked(): Result<Unit> =
        runCatchingNonCancel {
            if (!client.checkSession()) return@runCatchingNonCancel
            pullProfiles()
            pullCollections()
        }.onFailure { Log.w(TAG, "Website sync failed", it) }

    // ─── Profiles ───

    suspend fun pullProfiles() {
        val remote = client.profiles()
        if (remote.isEmpty()) return
        remoteProfiles = remote
        val local = profileManager.profiles.value.associateBy { it.id }
        profileDataStore.replaceAllProfiles(remote.map { FetchWebsiteMappings.toUserProfile(it, local[it.slot]) })

        // Parental limits come from the website while paired: it is where they are set.
        val slots = remote.map { it.slot }.toSet()
        remote.forEach { restrictionsDataStore.setRestrictions(it.slot, FetchWebsiteMappings.toRestrictions(it.parental)) }
        restrictionsDataStore.restrictions.first().keys.filter { it !in slots }.forEach {
            restrictionsDataStore.removeProfile(it)
        }

        // Which profiles are locked. The PIN itself is only ever checked by the website; the
        // copy kept for offline unlocks goes when the website says a profile has no PIN.
        lockStateDataStore.replaceAll(remote.associate { it.slot to it.hasPin })
        remote.filter { !it.hasPin }.forEach { lockStateDataStore.setLocalPinHash(it.slot, null) }
        Log.d(TAG, "Pulled ${remote.size} profiles from the website")
    }

    /** Sends every profile on this TV to the website, creating the ones it doesn't have yet. */
    suspend fun pushProfiles(): Result<Unit> = runCatchingNonCancel {
        val restrictions = restrictionsDataStore.restrictions.first()
        val updated = profileManager.profiles.value.map { profile ->
            client.putProfile(
                profile.id,
                FetchWebsiteMappings.toWebsiteBody(profile, restrictions[profile.id] ?: ProfileRestrictions.None)
            )
        }
        remoteProfiles = (remoteProfiles.filter { r -> updated.none { it.slot == r.slot } } + updated).sortedBy { it.slot }
    }.onFailure { Log.w(TAG, "Failed to push profiles to the website", it) }

    /**
     * After a change made only on this TV (a parental limit, say): sends the profiles shortly
     * after, so several quick changes go as one. Pulls wait for it, so a pull can't put back
     * what the push is about to replace.
     */
    fun triggerProfilesPush() {
        if (!store.isPaired) return
        profilesPushJob?.cancel()
        profilesPushJob = scope.launch {
            delay(500)
            syncMutex.withLock { pushProfiles() }
        }
    }

    /** Deletes the website profile in [slot], with everything it holds there. */
    suspend fun deleteProfile(slot: Int): Result<Unit> = runCatchingNonCancel {
        val remote = remoteFor(slot) ?: return@runCatchingNonCancel
        client.deleteProfile(remote.id, profileToken(remote.id))
        remoteProfiles = remoteProfiles.filter { it.slot != slot }
    }.onFailure { Log.w(TAG, "Failed to delete profile $slot on the website", it) }

    /** The website profile behind the profile active on this TV, looked up when not known yet. */
    suspend fun activeWebsiteProfile(): WebsiteSlottedProfile? = remoteFor(profileManager.activeProfileId.value)

    suspend fun remoteFor(slot: Int): WebsiteSlottedProfile? {
        if (!store.isPaired) return null
        remoteProfiles.firstOrNull { it.slot == slot }?.let { return it }
        remoteProfiles = runCatchingNonCancel { client.profiles() }.getOrDefault(remoteProfiles)
        return remoteProfiles.firstOrNull { it.slot == slot }
    }

    // ─── PINs ───

    fun profileToken(profileId: String): String? {
        val (token, expiresAt) = profileTokens[profileId] ?: return null
        return token.takeIf { System.currentTimeMillis() < expiresAt }
    }

    fun rememberProfileToken(profileId: String, token: String) {
        profileTokens[profileId] = token to System.currentTimeMillis() + PROFILE_TOKEN_TTL_MS
    }

    // ─── Collections ───

    /**
     * Reads the active profile's collections. A website that has never had any for this
     * profile is given this TV's instead, so pairing doesn't empty the Home screen.
     */
    suspend fun pullCollections() {
        val remote = activeWebsiteProfile() ?: return
        val (collections, updatedAt) = try {
            client.collections(remote.id, profileToken(remote.id))
        } catch (error: FetchWebsiteException) {
            // A PIN-protected profile nobody has unlocked on this TV yet: nothing to read.
            if (error.code == "profile_verification_required") return
            throw error
        }
        if (updatedAt == null) {
            if (collectionsDataStore.getCurrentCollections().isNotEmpty()) pushCollections()
            return
        }
        val remoteJson = collections.toString()
        val parsed = collectionsDataStore.importFromJson(remoteJson)
        if (collectionsDataStore.exportCurrentProfileJson() == collectionsDataStore.exportToJson(parsed)) return
        collectionsDataStore.setCollections(parsed)
        Log.d(TAG, "Applied ${parsed.size} collections from the website")
    }

    suspend fun pushCollections(): Result<Unit> = runCatchingNonCancel {
        val remote = activeWebsiteProfile() ?: return@runCatchingNonCancel
        val local = collectionsDataStore.exportCurrentProfileJson()
        val array = if (local.isNullOrBlank()) JsonArray(emptyList()) else json.parseToJsonElement(local) as JsonArray
        client.putCollections(remote.id, profileToken(remote.id), array)
        Log.d(TAG, "Pushed ${array.size} collections to the website")
    }.onFailure { Log.w(TAG, "Failed to push collections to the website", it) }

    /** After a change on this TV: saves the active profile's collections shortly after. */
    fun triggerCollectionsPush() {
        if (!store.isPaired) return
        collectionsPushJob?.cancel()
        collectionsPushJob = scope.launch {
            delay(500)
            pushCollections()
        }
    }

    /** Pulls the new profile's collections whenever the active profile changes. */
    private fun ensureWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            profileManager.activeProfileId.drop(1).collect {
                if (store.isPaired) runCatchingNonCancel { pullCollections() }
            }
        }
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}
