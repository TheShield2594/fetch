package com.fetch.tv.data.website

import android.util.Log
import com.fetch.tv.BuildConfig
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.AddonSyncService
import com.fetch.tv.core.sync.CollectionSyncService
import com.fetch.tv.core.sync.HomeCatalogSettingsSyncService
import com.fetch.tv.core.sync.ProfileSettingsSyncService
import com.fetch.tv.core.sync.SyncHomeCatalogPayload
import com.fetch.tv.core.sync.backend.SyncAddon
import com.fetch.tv.core.sync.shouldExcludePreferenceFromProfileSettingsSync
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.local.ProfileLockStateDataStore
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.repository.AddonRepositoryImpl
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
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.fetch.tv.core.util.runSuspendCatching

private const val TAG = "FetchWebsiteSync"

/** How long a profile token from the website is trusted here; the website issues them for 12 hours. */
private const val PROFILE_TOKEN_TTL_MS = 11L * 60 * 60 * 1000

/**
 * The Fetch website's side of sync, for a TV signed in to one by pairing. It is reached
 * through [com.fetch.tv.core.sync.backend.FetchWebsiteSyncBackend], the website's
 * implementation of the TV's sync backend interface.
 *
 * While paired the website is where the account lives: profiles (with their colour, picture
 * and parental limit) are pulled from it and written back when edited here, and each
 * profile's collections are read and saved as one document, the way the TV stores them.
 * Parental limits are written into the TV's own restriction store, so they keep applying
 * when the website is out of reach.
 *
 * Each profile's addons, Home row order and settings live there too. The TV's own sync
 * services ([AddonSyncService], [CollectionSyncService], [HomeCatalogSettingsSyncService],
 * [ProfileSettingsSyncService]) keep deciding when to push and what to apply, the same way for
 * every backend; this class is the transport. It also runs the website's own sync pass (on
 * start, on returning to the foreground, after a profile switch), which goes through those
 * services. After every pass the TV reports its build and the outcome, which is what the
 * website's overview shows for it.
 */
@Singleton
class FetchWebsiteSyncService @Inject constructor(
    private val store: FetchWebsiteStore,
    private val client: FetchWebsiteClient,
    private val profileManager: ProfileManager,
    private val profileDataStore: ProfileDataStore,
    private val restrictionsDataStore: ProfileRestrictionsDataStore,
    private val lockStateDataStore: ProfileLockStateDataStore,
    private val addonSyncService: dagger.Lazy<AddonSyncService>,
    private val collectionSync: dagger.Lazy<CollectionSyncService>,
    private val addonRepository: dagger.Lazy<AddonRepositoryImpl>,
    private val homeCatalogSync: dagger.Lazy<HomeCatalogSettingsSyncService>,
    private val profileSettingsSync: dagger.Lazy<ProfileSettingsSyncService>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var remoteProfiles: List<WebsiteSlottedProfile> = emptyList()
    private val profileTokens = ConcurrentHashMap<String, Pair<String, Long>>()

    private var watchJob: Job? = null
    private var profilesPushJob: Job? = null

    val isPaired: Boolean get() = store.isPaired

    /**
     * Checks the sign-in and pulls profiles and the active profile's collections. Safe to call
     * often (app start, returning to the foreground); does nothing when not paired.
     */
    fun requestSync() {
        if (!store.isPaired) return
        // Settings changes are pushed by the settings service's own observer, which only runs
        // once something has built it. Building it here, rather than late in a pass that may
        // fail first, is what makes a setting changed on the TV reach the website at all.
        profileSettingsSync.get()
        ensureWatching()
        scope.launch { syncNow() }
    }

    suspend fun syncNow(): Result<Unit> {
        // A change made here and not yet sent goes first, or the pull would undo it.
        profilesPushJob?.join()
        return syncMutex.withLock { syncLocked() }
    }

    private suspend fun syncLocked(): Result<Unit> {
        var signedIn = false
        val result = runSuspendCatching {
            if (!client.checkSession()) return@runSuspendCatching
            signedIn = true
            // Until this TV's profiles are on the website, the website's list isn't written over
            // them: that would drop the ones not pushed yet. The rest of the pass still runs, and
            // the push is tried again next time.
            val initialPush = if (store.needsInitialProfilePush) {
                runSuspendCatching { pushProfilesMissingFromWebsite() }
                    .onSuccess { store.markInitialProfilePushDone() }
                    .onFailure { Log.w(TAG, "Couldn't add this TV's profiles to the website", it) }
            } else {
                Result.success(Unit)
            }
            if (initialPush.isSuccess) pullProfiles()
            val pulled = runSuspendCatching { pullActiveProfileState() }
            // Both can fail: the push is the one the website's report names, since it is what
            // keeps the TV's profiles off the account; the pull's error is in the log.
            initialPush.exceptionOrNull()?.let { pushError ->
                pulled.exceptionOrNull()?.let { Log.w(TAG, "Website sync step failed", it) }
                throw pushError
            }
            pulled.getOrThrow()
        }.onFailure { Log.w(TAG, "Website sync failed", it) }
        if (signedIn) report(result)
        return result
    }

    /**
     * The active profile's collections, addons, then its Home rows (which name the addons'
     * catalogs and the collections), then its settings. Each goes through the TV's own sync
     * service, which decides what is applied and what is kept back while applying.
     */
    private suspend fun pullActiveProfileState() {
        // Each domain on its own, so one the website refuses (an addon link it can't read,
        // say) doesn't keep the rest from syncing. The first failure is what the pass reports.
        val failures = listOf(
            runSuspendCatching { pullCollections() },
            runSuspendCatching { pullAddons() },
            runSuspendCatching { homeCatalogSync.get().pullFromRemote().getOrThrow() },
            runSuspendCatching { profileSettingsSync.get().pullCurrentProfileFromRemote().getOrThrow() }
        ).mapNotNull { it.exceptionOrNull() }
        failures.drop(1).forEach { Log.w(TAG, "Website sync step failed", it) }
        failures.firstOrNull()?.let { throw it }
    }

    /** Best-effort: a website that can't take the report is no reason to fail the sync. */
    private suspend fun report(result: Result<Unit>) {
        val error = result.exceptionOrNull()
        runSuspendCatching {
            client.reportSync(
                appVersion = BuildConfig.VERSION_NAME,
                // One build since the playstore flavour went (#431); the website still shows a label.
                flavor = "full",
                ok = error == null,
                error = error?.let { it.message ?: it.javaClass.simpleName }
            )
        }.onFailure { Log.w(TAG, "Couldn't report the sync to the website", it) }
    }

    // ─── Profiles ───

    /** Writes the website's profiles to this TV. False when the website has none yet. */
    suspend fun pullProfiles(): Boolean {
        val remote = client.profiles()
        if (remote.isEmpty()) return false
        remoteProfiles = remote
        val local = profileManager.profiles.value.associateBy { it.id }
        profileDataStore.replaceAllProfiles(remote.map { FetchWebsiteMappings.toUserProfile(it, local[it.slot]) })

        // Parental limits come from the website while paired: it is where they are set.
        val slots = remote.map { it.slot }.toSet()
        remote.forEach { restrictionsDataStore.setRestrictions(it.slot, FetchWebsiteMappings.toRestrictions(it.parental)) }
        val stored = restrictionsDataStore.restrictions.first().keys + restrictionsDataStore.titleLists.first().keys
        stored.filter { it !in slots }.forEach {
            restrictionsDataStore.deleteProfile(it)
        }

        // Which profiles are locked. The PIN itself is only ever checked by the website; the
        // copy kept for offline unlocks goes when the website says a profile has no PIN.
        lockStateDataStore.replaceAll(remote.associate { it.slot to it.hasPin })
        remote.filter { !it.hasPin }.forEach { lockStateDataStore.setLocalPinHash(it.slot, null) }
        Log.d(TAG, "Pulled ${remote.size} profiles from the website")
        return true
    }

    /**
     * The first sync after pairing: creates on the website every profile this TV has that the
     * website has no profile for in the same slot, so pairing adds the TV's profiles to the
     * account instead of the pull that follows replacing them. Profiles the website already
     * has are left as they are there. Only done once per pairing, so a profile deleted on the
     * website later isn't brought back.
     */
    private suspend fun pushProfilesMissingFromWebsite() {
        val remoteSlots = client.profiles().mapTo(HashSet()) { it.slot }
        val restrictions = restrictionsDataStore.restrictions.first()
        val missing = profileManager.profiles.value.filter { it.id !in remoteSlots }
        missing.forEach { profile ->
            client.putProfile(
                profile.id,
                FetchWebsiteMappings.toWebsiteBody(profile, restrictions[profile.id] ?: ProfileRestrictions.None)
            )
        }
        if (missing.isNotEmpty()) Log.d(TAG, "Added ${missing.size} profiles from this TV to the website")
    }

    /**
     * [pushProfiles] for a change made on this TV (a profile added, renamed or deleted): waits
     * for a sync pass in progress, so a pull that read the website before the change can't
     * write the old profiles back over it.
     */
    suspend fun pushProfilesNow(): Result<Unit> {
        profilesPushJob?.cancel()
        return syncMutex.withLock { pushProfiles() }
    }

    /** Sends every profile on this TV to the website, creating the ones it doesn't have yet. */
    suspend fun pushProfiles(): Result<Unit> = runSuspendCatching {
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
    suspend fun deleteProfile(slot: Int): Result<Unit> = runSuspendCatching {
        val remote = remoteFor(slot) ?: return@runSuspendCatching
        client.deleteProfile(remote.id, profileToken(remote.id))
        remoteProfiles = remoteProfiles.filter { it.slot != slot }
    }.onFailure { Log.w(TAG, "Failed to delete profile $slot on the website", it) }

    /** The website profile behind the profile active on this TV, looked up when not known yet. */
    suspend fun activeWebsiteProfile(): WebsiteSlottedProfile? = remoteFor(profileManager.activeProfileId.value)

    suspend fun remoteFor(slot: Int): WebsiteSlottedProfile? {
        if (!store.isPaired) return null
        remoteProfiles.firstOrNull { it.slot == slot }?.let { return it }
        remoteProfiles = runSuspendCatching { client.profiles() }.getOrDefault(remoteProfiles)
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

    /** Applies the active profile's collections from the website, through [CollectionSyncService]. */
    suspend fun pullCollections() {
        collectionSync.get().pullFromRemote().getOrThrow()
    }

    /**
     * The collections of the website profile in [slot]. Null when there is nothing to apply: the
     * profile is locked and nobody has unlocked it on this TV, or the website has never had any
     * for it, in which case it is given [local] instead, so pairing doesn't empty the Home screen.
     */
    suspend fun collections(slot: Int, local: JsonArray): JsonArray? {
        val remote = remoteFor(slot) ?: return null
        val (collections, updatedAt) = try {
            client.collections(remote.id, profileToken(remote.id))
        } catch (error: FetchWebsiteException) {
            if (error.code == "profile_verification_required") return null
            throw error
        }
        if (updatedAt == null) {
            if (local.isNotEmpty()) putCollections(slot, local)
            return null
        }
        return collections
    }

    suspend fun putCollections(slot: Int, collections: JsonArray) {
        val remote = remoteFor(slot) ?: return
        client.putCollections(remote.id, profileToken(remote.id), collections)
        Log.d(TAG, "Pushed ${collections.size} collections to the website")
    }

    // ─── Addons ───

    /**
     * Applies the active profile's addon list from the account. One that has never had a list
     * is given this TV's instead, so pairing doesn't empty the TV's addons.
     */
    suspend fun pullAddons() {
        val addonSync = addonSyncService.get()
        val urls = addonSync.pullRemoteAddonUrls().getOrThrow() ?: run {
            addonSync.pushToRemote().getOrThrow()
            return
        }
        val repository = addonRepository.get()
        repository.isSyncingFromRemote = true
        try {
            repository.reconcileWithRemoteAddonUrls(remoteUrls = urls, removeMissingLocal = true)
        } finally {
            repository.isSyncingFromRemote = false
        }
    }

    /**
     * The addons of the website profile in [slot], in order, as TV base URLs. Null when the
     * website has no list for it yet; empty when the profile is locked and nobody has unlocked
     * it on this TV.
     */
    suspend fun addons(slot: Int): List<SyncAddon>? {
        val remote = remoteFor(slot) ?: return null
        val body = try {
            client.addons(remote.id, profileToken(remote.id))
        } catch (error: FetchWebsiteException) {
            if (error.code == "profile_verification_required") return emptyList()
            throw error
        }
        if (body.updatedAt == null && body.addons.isEmpty()) return null
        // The website's `name` is the addon's manifest name, not one somebody chose, so it isn't
        // passed on: written into the TV's custom names it would pin every addon to it.
        return body.addons.map { addon ->
            SyncAddon(
                url = FetchWebsiteTvMappings.toTvAddonUrl(addon.manifestUrl),
                enabled = addon.enabled
            )
        }
    }

    /** Replaces the website's list for [slot] with [addons]: every addon, in order, with its state and name. */
    suspend fun putAddons(slot: Int, addons: List<SyncAddon>) {
        val remote = remoteFor(slot) ?: return
        val body = addons.map { addon ->
            WebsiteAddon(
                manifestUrl = FetchWebsiteTvMappings.toManifestUrl(addon.url),
                enabled = addon.enabled,
                name = addon.name?.takeIf { it.isNotBlank() }
            )
        }
        client.putAddons(remote.id, profileToken(remote.id), body)
        Log.d(TAG, "Pushed ${body.size} addons to the website")
    }

    // ─── Home layout ───

    /**
     * The website's Home row order as the TV's payload, built over [local] so rows keep their
     * TV-side titles. Null when the website has never been given a layout for this profile.
     */
    suspend fun pullHomeLayout(slot: Int, local: SyncHomeCatalogPayload): SyncHomeCatalogPayload? {
        val remote = remoteFor(slot) ?: return null
        val layout = try {
            client.homeLayout(remote.id, profileToken(remote.id))
        } catch (error: FetchWebsiteException) {
            if (error.code == "profile_verification_required") return null
            throw error
        }
        if (layout.version == 0) {
            if (local.items.isNotEmpty()) pushHomeLayout(slot, local)
            return null
        }
        return FetchWebsiteTvMappings.fromWebsiteRows(layout.rows, local)
    }

    /**
     * Saves this TV's order, keeping the rows only the website places. Read just before, so the
     * version sent is the current one; a save that still races another is retried once on the
     * newer layout.
     */
    suspend fun pushHomeLayout(slot: Int, payload: SyncHomeCatalogPayload) {
        val remote = remoteFor(slot) ?: return
        val token = profileToken(remote.id)
        repeat(2) { attempt ->
            val current = client.homeLayout(remote.id, token)
            try {
                val rows = FetchWebsiteTvMappings.toWebsiteRows(payload, current.rows, installedAddonIds())
                client.putHomeLayout(remote.id, token, rows, current.version)
                return
            } catch (error: FetchWebsiteException) {
                if (error.status != 412 || attempt == 1) throw error
            }
        }
    }

    /** Every installed addon's manifest id, enabled or not. */
    private suspend fun installedAddonIds(): Set<String> =
        addonRepository.get().getInstalledAddons().first().mapTo(HashSet()) { it.id }

    // ─── Settings ───

    /**
     * Brings this TV's settings blob for [slot] and the website's together and returns the
     * result, which is what both sides hold afterwards. The merge is three-way against the copy
     * last agreed on (see [FetchWebsiteTvMappings.mergeSettings]), so a change on either side
     * survives the other's. Null when the profile is locked and not unlocked on this TV.
     */
    suspend fun reconcileSettings(slot: Int, local: JsonObject): JsonObject? {
        val remote = remoteFor(slot) ?: return null
        val token = profileToken(remote.id)
        repeat(2) { attempt ->
            val current = try {
                client.tvSettings(remote.id, token)
            } catch (error: FetchWebsiteException) {
                if (error.code == "profile_verification_required") return null
                throw error
            }
            val base = store.settingsBase(remote.id)?.let { runSuspendCatching { json.parseToJsonElement(it) as JsonObject }.getOrNull() }
            val merged = FetchWebsiteTvMappings.mergeSettings(
                base = if (current.version == 0) null else base,
                local = local,
                remote = current.settings,
                isNotSynced = ::shouldExcludePreferenceFromProfileSettingsSync
            )
            if (merged == current.settings) {
                store.saveSettingsBase(remote.id, merged.toString())
                return merged
            }
            try {
                val saved = client.putTvSettings(remote.id, token, merged, current.version)
                store.saveSettingsBase(remote.id, saved.settings.toString())
                return saved.settings
            } catch (error: FetchWebsiteException) {
                if (error.status != 412 || attempt == 1) throw error
            }
        }
        return null
    }

    /** Pulls the new profile's collections, addons, Home rows and settings whenever the active profile changes. */
    private fun ensureWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            profileManager.activeProfileId.drop(1).collect {
                if (!store.isPaired) return@collect
                syncMutex.withLock {
                    runSuspendCatching {
                        pullActiveProfileState()
                    }.onFailure { Log.w(TAG, "Website sync after a profile switch failed", it) }
                }
            }
        }
    }
}
