package com.fetch.tv.data.website

import android.util.Log
import com.fetch.tv.BuildConfig
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.AddonSyncService
import com.fetch.tv.core.sync.HomeCatalogSettingsSyncService
import com.fetch.tv.core.sync.ProfileSettingsSyncService
import com.fetch.tv.core.sync.SyncHomeCatalogPayload
import com.fetch.tv.data.local.AddonPreferences
import com.fetch.tv.data.local.CollectionsDataStore
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.local.ProfileLockStateDataStore
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.repository.AddonRepositoryImpl
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
import kotlinx.serialization.json.JsonObject
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
 *
 * Each profile's addons, Home row order and settings live there too. The TV's own sync
 * services ([AddonSyncService], [HomeCatalogSettingsSyncService], [ProfileSettingsSyncService])
 * keep deciding when to push and what to apply, and hand the transport to this class while
 * paired. After every sync the TV reports its build and the outcome, which is what the
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
    private val collectionsDataStore: CollectionsDataStore,
    private val addonPreferences: AddonPreferences,
    private val addonSyncService: dagger.Lazy<AddonSyncService>,
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

    private suspend fun syncLocked(): Result<Unit> {
        var signedIn = false
        val result = runCatchingNonCancel {
            if (!client.checkSession()) return@runCatchingNonCancel
            signedIn = true
            pullProfiles()
            pullCollections()
            pullActiveProfileState()
        }.onFailure { Log.w(TAG, "Website sync failed", it) }
        if (signedIn) report(result)
        return result
    }

    /**
     * The active profile's addons, then its Home rows (which name the addons' catalogs), then
     * its settings. Each goes through the TV's own sync service, so what is applied, and what is
     * kept back while applying, is decided the same way as for the Supabase backend.
     */
    private suspend fun pullActiveProfileState() {
        pullAddons()
        homeCatalogSync.get().pullFromRemote().getOrThrow()
        profileSettingsSync.get().pullCurrentProfileFromRemote().getOrThrow()
    }

    /** Best-effort: a website that can't take the report is no reason to fail the sync. */
    private suspend fun report(result: Result<Unit>) {
        val error = result.exceptionOrNull()
        runCatchingNonCancel {
            client.reportSync(
                appVersion = BuildConfig.VERSION_NAME,
                flavor = BuildConfig.FLAVOR,
                ok = error == null,
                error = error?.let { it.message ?: it.javaClass.simpleName }
            )
        }.onFailure { Log.w(TAG, "Couldn't report the sync to the website", it) }
    }

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

    // ─── Addons ───

    /**
     * The website profile whose addons the active profile uses: its own, or the primary's for a
     * profile set to share them.
     */
    private suspend fun addonsProfile(): WebsiteSlottedProfile? {
        val active = profileManager.activeProfile
        val slot = if (active != null && !active.isPrimary && active.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
        return remoteFor(slot)
    }

    /**
     * Applies the website's addon list. A website that has never had one for this profile is
     * given this TV's instead, so pairing doesn't empty the TV's addons.
     */
    suspend fun pullAddons() {
        val urls = websiteAddonUrls() ?: run {
            addonSyncService.get().pushToRemote().getOrThrow()
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
     * The addon base URLs to install, in order, with names and on/off states written into the
     * TV's addon preferences on the way. Null when the website has no list for this profile yet,
     * or the profile is locked and nobody has unlocked it on this TV.
     */
    suspend fun websiteAddonUrls(): List<String>? {
        val remote = addonsProfile() ?: return null
        val body = try {
            client.addons(remote.id, profileToken(remote.id))
        } catch (error: FetchWebsiteException) {
            if (error.code == "profile_verification_required") return emptyList()
            throw error
        }
        if (body.updatedAt == null && body.addons.isEmpty()) return null

        val urls = body.addons.map { FetchWebsiteTvMappings.toTvAddonUrl(it.manifestUrl) }
        if (body.addons.isNotEmpty()) {
            addonPreferences.setAddonEnabledStates(urls.zip(body.addons).associate { (url, addon) -> url to addon.enabled })
            val names = urls.zip(body.addons).mapNotNull { (url, addon) -> addon.name?.takeIf { it.isNotBlank() }?.let { url to it } }
            addonPreferences.setUserSetNames(names.toMap())
        }
        return urls
    }

    /** Replaces the website's list with this TV's: every addon, in order, with its state and name. */
    suspend fun pushAddons(urls: List<String>, enabled: Map<String, Boolean>, names: Map<String, String>) {
        val remote = addonsProfile() ?: return
        val addons = urls.map { url ->
            WebsiteAddon(
                manifestUrl = FetchWebsiteTvMappings.toManifestUrl(url),
                enabled = enabled[url] ?: true,
                name = names[url]?.takeIf { it.isNotBlank() }
            )
        }
        client.putAddons(remote.id, profileToken(remote.id), addons)
        Log.d(TAG, "Pushed ${addons.size} addons to the website")
    }

    // ─── Home layout ───

    /**
     * The website's Home row order as the TV's payload, built over [local] so rows keep their
     * TV-side titles. Null when the website has never been given a layout for this profile.
     */
    suspend fun pullHomeLayout(local: SyncHomeCatalogPayload): SyncHomeCatalogPayload? {
        val remote = activeWebsiteProfile() ?: return null
        val layout = try {
            client.homeLayout(remote.id, profileToken(remote.id))
        } catch (error: FetchWebsiteException) {
            if (error.code == "profile_verification_required") return null
            throw error
        }
        if (layout.version == 0) {
            if (local.items.isNotEmpty()) pushHomeLayout(local)
            return null
        }
        return FetchWebsiteTvMappings.fromWebsiteRows(layout.rows, local)
    }

    /**
     * Saves this TV's order, keeping the rows only the website places. Read just before, so the
     * version sent is the current one; a save that still races another is retried once on the
     * newer layout.
     */
    suspend fun pushHomeLayout(payload: SyncHomeCatalogPayload) {
        val remote = activeWebsiteProfile() ?: return
        val token = profileToken(remote.id)
        repeat(2) { attempt ->
            val current = client.homeLayout(remote.id, token)
            try {
                client.putHomeLayout(remote.id, token, FetchWebsiteTvMappings.toWebsiteRows(payload, current.rows), current.version)
                return
            } catch (error: FetchWebsiteException) {
                if (error.status != 412 || attempt == 1) throw error
            }
        }
    }

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
            val base = store.settingsBase(remote.id)?.let { runCatching { json.parseToJsonElement(it) as JsonObject }.getOrNull() }
            val merged = FetchWebsiteTvMappings.mergeSettings(
                base = if (current.version == 0) null else base,
                local = local,
                remote = current.settings
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
                    runCatchingNonCancel {
                        pullCollections()
                        pullActiveProfileState()
                    }.onFailure { Log.w(TAG, "Website sync after a profile switch failed", it) }
                }
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
