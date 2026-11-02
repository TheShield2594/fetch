package com.fetch.tv.core.sync

import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.fetch.tv.core.auth.AuthManager
import com.fetch.tv.core.parental.ScreenTimeSharing
import com.fetch.tv.core.parental.ScreenTimeStore
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ContinueWatchingEnrichmentCache
import com.fetch.tv.data.website.FetchWebsiteSyncService
import com.fetch.tv.data.local.ExperienceModeDataStore
import com.fetch.tv.data.local.ProfileDataStoreFactory
import com.fetch.tv.data.local.KidsRules
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.local.StreamBadgeSettingsDataStore
import com.fetch.tv.data.local.TmdbSettingsDataStore
import com.fetch.tv.data.remote.supabase.SupabaseProfileSetupCopyResult
import com.fetch.tv.data.remote.supabase.SupabaseProfileSettingsBlob
import com.fetch.tv.domain.model.DiscoverLocation
import com.fetch.tv.domain.repository.MetaRepository
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSettingsSyncService"
private const val SETTINGS_PUSH_DEBOUNCE_MS = 1500L
private const val FOREGROUND_PULL_DELAY_MS = 2500L
private const val FOREGROUND_PULL_MIN_INTERVAL_MS = 60_000L
private const val SCREEN_TIME_PULL_INTERVAL_MS = 60_000L
private const val SCREEN_TIME_PUSH_INTERVAL_MS = 60_000L
private const val SETTINGS_SYNC_PLATFORM = "tv"
private const val PLAYER_SETTINGS_FEATURE = "player_settings"
private const val PRIMARY_PROFILE_ID = 1

private val catalogKeysExcludedFromProfileSettingsBlob = setOf(
    "home_catalog_order_keys",
    "disabled_home_catalog_keys",
    "custom_catalog_titles"
)

private val localOnlyLayoutProfileSettingsKeys = setOf(
    "last_non_off_discover_location"
)

private val localOnlyPlayerProfileSettingsKeys = setOf(
    "player_preference",
    "internal_player_engine",
    "auto_switch_internal_player_on_error",
    "use_libass",
    "libass_render_type",
    "decoder_priority",
    "downmix_enabled",
    "audio_output_channels",
    "maintain_original_audio_on_downmix",
    "downmix_normalization_enabled",
    "tunneling_enabled",
    "force_optical_passthrough",
    "audio_amplification_db",
    "center_mix_level_db",
    "persist_audio_amplification",
    "remember_audio_delay_per_device",
    "experimental_dv5_to_dv81_enabled",
    "experimental_dv7_to_dv81_preserve_mapping_enabled",
    "dv7_handling_mode",
    "map_dv7_to_hevc",
    "dv7_libdovi_mode_override",
    "strip_hdr10plus_sei",
    "mpv_hardware_decode_mode",
    "frame_rate_matching",
    "frame_rate_matching_mode",
    "resolution_matching_enabled",
    "external_player_forward_subtitles",
    "external_player_send_skip_segments",
    "vod_cache_enabled",
    "vod_cache_size_mode",
    "vod_cache_size_mb",
    "use_parallel_connections",
    "buffer_engine_enabled",
    "parallel_network_enabled",
    "allow_large_target_buffer",
    "buffer_budget_managed",
    "parallel_connection_count",
    "parallel_chunk_size_mb",
    "parallel_chunk_size_kb",
    "enable_http2",
    "last_playback_diagnostics_json",
    "enable_buffer_logs",
    "resize_mode",
    "min_buffer_ms",
    "max_buffer_ms",
    "buffer_for_playback_ms",
    "buffer_for_playback_after_rebuffer_ms",
    "target_buffer_size_mb",
    "back_buffer_duration_ms",
    "retain_back_buffer_from_keyframe",
    "migration_load_control_defaults_aligned_done",
    "migration_load_control_defaults_retuned_done",
    "migration_load_control_min_buffer_retuned_done",
    "migration_vod_cache_split_done",
    "migration_back_buffer_duration_bumped_done",
    "migration_max_buffer_bumped_done",
    "migration_target_buffer_size_bumped_done",
    "migration_after_rebuffer_lowered_done",
    "migration_back_buffer_duration_reduced_done",
    "migration_target_buffer_size_reduced_done",
    "migration_allow_large_target_buffer_off_done",
    "migration_buffer_budget_managed_exo_done",
    "migration_vod_cache_back_buffer_zeroed_done",
    "fetch_performance_mode_enabled"
)

// 4K trailers depend on the device's RAM, so a choice made on one device must not reach another.
private val localOnlyTrailerProfileSettingsKeys = setOf(
    "trailer_allow_4k"
)

private val credentialProfileSettingsKeys = mapOf(
    "mdblist_settings" to setOf("mdblist_api_key"),
    "animeskip_settings" to setOf("animeskip_client_id")
)

internal fun shouldExcludePreferenceFromProfileSettingsSync(feature: String, keyName: String): Boolean {
    return when {
        feature == "layout_settings" && keyName in catalogKeysExcludedFromProfileSettingsBlob -> true
        feature == "layout_settings" && keyName in localOnlyLayoutProfileSettingsKeys -> true
        feature == "layout_settings" && keyName == "search_discover_enabled" -> true
        feature == PLAYER_SETTINGS_FEATURE && keyName in localOnlyPlayerProfileSettingsKeys -> true
        feature == "trailer_settings" && keyName in localOnlyTrailerProfileSettingsKeys -> true
        keyName in credentialProfileSettingsKeys[feature].orEmpty() -> true
        else -> false
    }
}

@Singleton
class ProfileSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrestLazy: dagger.Lazy<Postgrest>,
    private val profileManager: ProfileManager,
    private val profileDataStoreFactory: ProfileDataStoreFactory,
    private val syncClientIdentity: SyncClientIdentity,
    private val providerCredentialSyncService: ProviderCredentialSyncService,
    private val mediaServerSyncService: MediaServerSyncService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val metaRepository: MetaRepository,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val restrictionsDataStore: ProfileRestrictionsDataStore,
    private val websiteSync: dagger.Lazy<FetchWebsiteSyncService>,
    private val screenTimeStore: ScreenTimeStore
) : ScreenTimeSharing {
    /** Somewhere to sync to: the Supabase account, or a Fetch website this TV is paired with. */
    private val canSync: Boolean get() = websiteSync.get().isPaired || authManager.isAuthenticated

    private val postgrest: Postgrest get() = postgrestLazy.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    @Volatile
    private var applyingRemoteBlob: Boolean = false

    @Volatile
    private var skipNextPushSignature: String? = null
    private var foregroundPullJob: Job? = null
    private var lastForegroundPullAtMs: Long = 0L

    private val syncedFeatures = listOf(
        "theme_settings",
        "layout_settings",
        ExperienceModeDataStore.FEATURE,
        PLAYER_SETTINGS_FEATURE,
        StreamBadgeSettingsDataStore.FEATURE,
        "trailer_settings",
        "tmdb_settings",
        "mdblist_settings",
        "trakt_settings",
        "animeskip_settings",
        "track_preference",
        com.fetch.tv.data.local.EpisodeQueueStore.FEATURE
    )

    init {
        observeLocalSettingsChangesAndSync()
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun pushCurrentProfileToRemote(): Result<Unit> =
        pushProfileSettingsToRemote(profileManager.activeProfileId.value)

    private suspend fun pushProfileSettingsToRemote(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                pushProfileToRemote(profileId)
                Log.d(TAG, "Pushed profile settings blob for profile $profileId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push profile settings blob", e)
                Result.failure(e)
            }
        }
    }

    suspend fun pullCurrentProfileFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val profileId = profileManager.activeProfileId.value
                val blob = pullProfileFromRemote(profileId)
                lastForegroundPullAtMs = SystemClock.elapsedRealtime()
                if (blob == null) {
                    Log.d(TAG, "No remote profile settings blob for profile $profileId; keeping local settings")
                    return@withLock Result.success(false)
                }

                if (!websiteSync.get().isPaired) applyParentalLimits(profileId, blob)
                val featuresJson = blob["features"]?.jsonObject
                    ?: return@withLock Result.success(false)
                val remoteSignature = buildSettingsSignature(featuresJson)
                val localSignature = buildSettingsSignature(profileId)
                if (remoteSignature == localSignature) {
                    Log.d(TAG, "Remote profile settings already match local for profile $profileId")
                    return@withLock Result.success(false)
                }

                applySettingsBlob(profileId, featuresJson, remoteSignature)
                Log.d(TAG, "Applied remote profile settings blob for profile $profileId")
                Result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull profile settings blob", e)
                Result.failure(e)
            }
        }
    }

    suspend fun copyProfileSetup(
        sourceProfileId: Int,
        targetProfileId: Int,
        copyProviderCredentials: Boolean = false
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                try {
                    require(sourceProfileId != targetProfileId)
                    require(profileManager.profiles.value.any { it.id == sourceProfileId })
                    require(profileManager.profiles.value.any { it.id == targetProfileId })

                    val targetFeatures = if (authManager.isAuthenticated) {
                        if (
                            copyProviderCredentials &&
                            profileManager.activeProfileId.value in setOf(sourceProfileId, targetProfileId)
                        ) {
                            providerCredentialSyncService.syncFromRemote().getOrThrow()
                        }
                        val sourceBlob = if (sourceProfileId == profileManager.activeProfileId.value) {
                            exportSettingsBlob(sourceProfileId).also { blob ->
                                pushProfileToRemote(sourceProfileId, blob)
                            }
                        } else {
                            pullProfileFromRemote(sourceProfileId)
                                ?: exportSettingsBlob(sourceProfileId).also { blob ->
                                    pushProfileToRemote(sourceProfileId, blob)
                                }
                        }
                        require(sourceBlob["features"] is JsonObject)
                        // The copy takes the whole blob, parental limits too; the target keeps its own.
                        val targetParental = if (websiteSync.get().isPaired) null else parentalLimitsForPush(targetProfileId)
                        // The target's kids rules too, read now: after the copy the account holds the source's.
                        val targetKidsRules = targetParental?.let { kidsRulesForPush(targetProfileId) }
                        // Pending until the restore below lands, so a failed one is retried on the next pull.
                        targetParental?.let { restrictionsDataStore.setPendingAccountRestrictions(targetProfileId, it) }

                        val params = buildJsonObject {
                            put("p_source_profile_id", sourceProfileId)
                            put("p_target_profile_id", targetProfileId)
                            put("p_copy_tv", true)
                            put("p_copy_mobile", false)
                            put("p_copy_desktop", false)
                            put("p_copy_provider_credentials", copyProviderCredentials)
                            put("p_replace_provider_credentials", false)
                            putSyncOriginClientId(syncClientIdentity)
                        }
                        val response = withJwtRefreshRetry {
                            postgrest.rpc("sync_copy_profile_setup", params)
                        }
                        val copyResult = response.decodeList<SupabaseProfileSetupCopyResult>().firstOrNull()
                            ?: error("Profile settings copy returned no result")
                        check(copyResult.sourceProfileId == sourceProfileId)
                        check(copyResult.targetProfileId == targetProfileId)
                        check(copyResult.tvStatus == "copied" || copyResult.tvStatus == "unchanged")
                        if (copyProviderCredentials) {
                            check(
                                copyResult.providerCredentialsStatus in setOf(
                                    "copied",
                                    "copied_partial",
                                    "kept_existing",
                                    "unchanged",
                                    "source_missing"
                                )
                            )
                        }
                        val copiedFeatures = pullProfileFromRemote(targetProfileId)
                            ?.get("features")
                            ?.jsonObject
                            ?: error("Copied TV settings are unavailable")
                        if (targetParental != null) pushProfileToRemote(
                            targetProfileId,
                            buildJsonObject {
                                put("version", 1)
                                put("features", copiedFeatures)
                            },
                            ParentalSnapshot(targetParental, targetKidsRules)
                        )
                        copiedFeatures
                    } else {
                        exportSettingsBlob(sourceProfileId)["features"]?.jsonObject
                            ?: error("Source TV settings are unavailable")
                    }

                    applySettingsBlob(
                        profileId = targetProfileId,
                        featuresJson = targetFeatures,
                        signature = buildSettingsSignature(targetFeatures)
                    )
                    if (copyProviderCredentials) {
                        if (authManager.isAuthenticated) {
                            if (profileManager.activeProfileId.value == targetProfileId) {
                                providerCredentialSyncService.syncFromRemote(targetProfileId).getOrThrow()
                            }
                        } else {
                            copyProviderCredentialsLocally(sourceProfileId, targetProfileId)
                        }
                    }
                    Log.d(TAG, "Copied profile setup from $sourceProfileId to $targetProfileId")
                    Result.success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy profile setup from $sourceProfileId to $targetProfileId", e)
                    Result.failure(e)
                }
            }
        }

    /**
     * [profileId]'s synced settings as the device has them, without credentials or values that
     * only fit this device. Used to move a profile to another TV.
     */
    suspend fun exportLocalSettings(profileId: Int): JsonObject = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            exportSettingsBlob(profileId)["features"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    /**
     * Replaces [profileId]'s synced settings with [features], as [exportLocalSettings] gave them on
     * another TV, keeping this device's own values. When signed in they are pushed too, so an older
     * copy on the sync server doesn't replace them on the next pull.
     */
    suspend fun importLocalSettings(profileId: Int, features: JsonObject) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            importSettingsBlob(profileId, features)
            if (authManager.isAuthenticated) {
                try {
                    pushProfileToRemote(profileId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Imported settings for profile $profileId weren't pushed", e)
                }
            }
        }
    }

    /**
     * Reads each profile's parental limits from the account and tightens this TV's to match, so
     * a kids profile set up on another TV is one here too, before anyone opens it. Limits only
     * ever get stricter this way. A parent's change that hasn't reached the account yet is sent
     * first. A TV paired with a Fetch website gets them with its profiles.
     *
     * Returns false when a read or a send failed, so the caller tries again sooner.
     */
    suspend fun pullParentalLimits(profileIds: Collection<Int>): Boolean = withContext(Dispatchers.IO) {
        if (websiteSync.get().isPaired || !authManager.isAuthenticated) return@withContext true
        var allDone = true
        val ids = profileIds.filter { it != PRIMARY_PROFILE_ID }
        val pending = restrictionsDataStore.pendingAccountPushes.first() + failedScreenTimePushes
        ids.filter { it in pending }.forEach { profileId ->
            if (pushProfileSettingsToRemote(profileId).isFailure) allDone = false else failedScreenTimePushes.remove(profileId)
        }
        ids.forEach { profileId ->
            try {
                applyParentalLimits(profileId, pullSupabaseBlob(profileId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                allDone = false
                Log.w(TAG, "Couldn't read parental limits for profile $profileId", e)
            }
        }
        allDone
    }

    /** After a parent changed a profile's limits on this TV: sends them to the account. */
    fun triggerParentalLimitsPush(profileId: Int) {
        if (websiteSync.get().isPaired || !authManager.isAuthenticated) return
        scope.launch { pushProfileSettingsToRemote(profileId) }
    }

    private suspend fun applyParentalLimits(profileId: Int, blob: JsonObject?) {
        if (profileId == PRIMARY_PROFILE_ID) return
        applyOtherDevicesScreenTime(profileId, blob)
        val account = ParentalLimitsBlob.decode(blob) ?: return
        val accountKidsRules = ParentalLimitsBlob.decodeKidsRules(blob)
        if (restrictionsDataStore.tightenFromAccount(profileId, account, accountKidsRules)) {
            Log.d(TAG, "Tightened parental limits for profile $profileId from the account")
        }
    }

    /**
     * The limits to send with [profileId]'s settings: the account's, as this TV last saw or set
     * them, so a limit removed only here isn't removed everywhere. A profile whose account limits
     * this TV has never seen reads them first, so the push can't wipe them; when the account has
     * none yet, this TV's own limits are the first copy.
     */
    private suspend fun parentalLimitsForPush(profileId: Int): ProfileRestrictions? {
        if (profileId == PRIMARY_PROFILE_ID) return null
        restrictionsDataStore.accountRestrictions.first()[profileId]?.let { return it }
        val remoteBlob = pullSupabaseBlob(profileId)
        ParentalLimitsBlob.decode(remoteBlob)?.let { remote ->
            restrictionsDataStore.tightenFromAccount(profileId, remote, ParentalLimitsBlob.decodeKidsRules(remoteBlob))
            return remote
        }
        return restrictionsDataStore.restrictions.first()[profileId] ?: ProfileRestrictions.None
    }

    /**
     * The kids rules to send with [profileId]'s settings, worked out like the limits: the
     * account's copy as this TV last saw or set it, else this TV's own rules once a parent set
     * some. Read after [parentalLimitsForPush], which fills the account copy when it reads one.
     */
    private suspend fun kidsRulesForPush(profileId: Int): KidsRules? {
        if (profileId == PRIMARY_PROFILE_ID) return null
        restrictionsDataStore.accountKidsRules.first()[profileId]?.let { return it }
        // Never seen the account's rules: read them first, so this push can't wipe another TV's.
        val remoteBlob = pullSupabaseBlob(profileId)
        val remoteRules = ParentalLimitsBlob.decodeKidsRules(remoteBlob)
        val local = restrictionsDataStore.kidsRules.first()[profileId]?.forAccount()
        if (remoteRules != null) {
            ParentalLimitsBlob.decode(remoteBlob)?.let { remoteLimits ->
                restrictionsDataStore.tightenFromAccount(profileId, remoteLimits, remoteRules)
            }
            // Both TVs' rules, so neither loosens the other's when this copy reaches the account.
            return (local ?: KidsRules.Default.forAccount()).tightenedWith(remoteRules).forAccount()
        }
        return local
    }

    private suspend fun refreshAccountCopiesBeforePush(profileId: Int) {
        val remote = try {
            pullSupabaseBlob(profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read profile $profileId before pushing; sending what this TV has", e)
            return
        } ?: return
        // A pending profile is left alone by tightenFromAccount, so the parent's change goes out as set.
        applyParentalLimits(profileId, remote)
    }

    private suspend fun applyOtherDevicesScreenTime(profileId: Int, blob: JsonObject?) {
        val others = ScreenTimeBlob.decode(blob) - syncClientIdentity.currentClientId()
        screenTimeStore.setOtherDevices(profileId, others)
    }

    /**
     * This TV's screen time for [profileId] and the other TVs' as last read, for its settings
     * blob. Null when there's nothing to send, so profiles that never watched as kids carry none.
     */
    private suspend fun screenTimeForPush(profileId: Int): JsonObject? {
        if (profileId == PRIMARY_PROFILE_ID) return null
        val today = java.time.LocalDate.now()
        val records = ScreenTimeBlob.recordsForPush(
            others = screenTimeStore.otherDevices(profileId),
            ownClientId = syncClientIdentity.currentClientId(),
            own = screenTimeStore.days()[profileId],
            oldestDate = today.minusDays(1).toString()
        )
        return records.takeIf { it.isNotEmpty() }?.let(ScreenTimeBlob::encode)
    }

    private val screenTimePullAtMs = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val screenTimePushAtMs = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** Reads other TVs' screen time for a kids profile, at most every [SCREEN_TIME_PULL_INTERVAL_MS]. */
    override fun requestScreenTimePull(profileId: Int) {
        if (profileId == PRIMARY_PROFILE_ID || websiteSync.get().isPaired || !authManager.isAuthenticated) return
        if (!claimSlot(screenTimePullAtMs, profileId, SCREEN_TIME_PULL_INTERVAL_MS)) return
        scope.launch {
            try {
                applyOtherDevicesScreenTime(profileId, pullSupabaseBlob(profileId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                screenTimePullAtMs.remove(profileId)
                Log.w(TAG, "Couldn't read shared screen time for profile $profileId", e)
            }
        }
    }

    /**
     * Sends this TV's screen time for a kids profile with its settings: right away when
     * [immediate] (a parent added time), otherwise at most every [SCREEN_TIME_PUSH_INTERVAL_MS].
     */
    override fun requestScreenTimePush(profileId: Int, immediate: Boolean) {
        if (profileId == PRIMARY_PROFILE_ID || websiteSync.get().isPaired || !authManager.isAuthenticated) return
        if (immediate) {
            screenTimePushAtMs[profileId] = SystemClock.elapsedRealtime()
            launchScreenTimePush(profileId)
            return
        }
        if (claimSlot(screenTimePushAtMs, profileId, SCREEN_TIME_PUSH_INTERVAL_MS)) {
            launchScreenTimePush(profileId)
            return
        }
        // Inside the interval: send once it ends, so the last minute of playback isn't held back.
        if (screenTimeTrailingPushes[profileId]?.isActive == true) return
        val since = SystemClock.elapsedRealtime() - (screenTimePushAtMs[profileId] ?: 0L)
        screenTimeTrailingPushes[profileId] = scope.launch {
            delay((SCREEN_TIME_PUSH_INTERVAL_MS - since).coerceAtLeast(0L))
            requestScreenTimePush(profileId, immediate = false)
        }
    }

    override suspend fun refreshScreenTime(profileId: Int) {
        if (profileId == PRIMARY_PROFILE_ID || websiteSync.get().isPaired || !authManager.isAuthenticated) return
        try {
            applyOtherDevicesScreenTime(profileId, pullSupabaseBlob(profileId))
            screenTimePullAtMs[profileId] = SystemClock.elapsedRealtime()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read shared screen time for profile $profileId before playback", e)
        }
    }

    private val screenTimeTrailingPushes = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    // Profiles whose last screen-time push failed; the next account pull sends them again.
    private val failedScreenTimePushes: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun launchScreenTimePush(profileId: Int) {
        scope.launch {
            if (pushProfileSettingsToRemote(profileId).isFailure) {
                failedScreenTimePushes.add(profileId)
            } else {
                failedScreenTimePushes.remove(profileId)
            }
        }
    }

    private fun claimSlot(lastAt: java.util.concurrent.ConcurrentHashMap<Int, Long>, profileId: Int, intervalMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        var claimed = false
        lastAt.compute(profileId) { _, previous ->
            if (previous == null || now - previous >= intervalMs) {
                claimed = true
                now
            } else {
                previous
            }
        }
        return claimed
    }

    fun requestForegroundPull(force: Boolean = false) {
        providerCredentialSyncService.requestForegroundPull(force)
        mediaServerSyncService.requestForegroundPull(force)
        if (!authManager.isAuthenticated) return

        val now = SystemClock.elapsedRealtime()
        if (!force && foregroundPullJob?.isActive == true) return
        if (!force && now - lastForegroundPullAtMs < FOREGROUND_PULL_MIN_INTERVAL_MS) return

        foregroundPullJob = scope.launch {
            if (!force) {
                delay(FOREGROUND_PULL_DELAY_MS)
            }
            if (!canSync) return@launch

            lastForegroundPullAtMs = SystemClock.elapsedRealtime()
            pullCurrentProfileFromRemote()
        }
    }

    /** The parental part of a push worked out before the copy RPC overwrote the account's blob. */
    private data class ParentalSnapshot(val limits: ProfileRestrictions, val kidsRules: KidsRules?)

    private suspend fun pushProfileToRemote(
        profileId: Int,
        settingsJson: JsonObject? = null,
        parentalSnapshot: ParentalSnapshot? = null
    ) {
        val resolvedSettingsJson = settingsJson ?: exportSettingsBlob(profileId)
        if (websiteSync.get().isPaired) {
            pushProfileToWebsite(profileId, resolvedSettingsJson)
            return
        }
        // The push replaces the whole blob, so first read what other TVs put there since this TV
        // last looked: their screen time, and (unless a parent's change here is waiting to go)
        // their limits and kids rules. Otherwise a stale copy here would overwrite theirs.
        if (profileId != PRIMARY_PROFILE_ID && parentalSnapshot == null) refreshAccountCopiesBeforePush(profileId)
        val parental = parentalSnapshot?.limits ?: parentalLimitsForPush(profileId)
        val kidsRules = when {
            parental == null -> null
            parentalSnapshot != null -> parentalSnapshot.kidsRules
            else -> kidsRulesForPush(profileId)
        }
        val screenTime = screenTimeForPush(profileId)
        var blob = resolvedSettingsJson
        if (parental != null) {
            blob = JsonObject(blob + (ParentalLimitsBlob.KEY to ParentalLimitsBlob.encode(parental, kidsRules)))
        }
        if (screenTime != null) blob = JsonObject(blob + (ScreenTimeBlob.KEY to screenTime))
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_settings_json", blob)
            put("p_platform", SETTINGS_SYNC_PLATFORM)
            putSyncOriginClientId(syncClientIdentity)
        }
        withJwtRefreshRetry {
            postgrest.rpc("sync_push_profile_settings_blob", params)
        }
        if (parental != null) restrictionsDataStore.accountPushSucceeded(profileId, parental, kidsRules)
    }

    /**
     * A TV signed in to a Fetch website keeps its settings there. The website merges rather
     * than replaces (see [FetchWebsiteSyncService.reconcileSettings]), so what comes back can
     * hold changes made on the website since the last sync; those are applied here, the same way
     * a pull applies them.
     */
    private suspend fun pushProfileToWebsite(profileId: Int, local: JsonObject) {
        val merged = websiteSync.get().reconcileSettings(profileId, local) ?: return
        val mergedFeatures = merged["features"]?.jsonObject ?: return
        val localFeatures = local["features"]?.jsonObject ?: JsonObject(emptyMap())
        val signature = buildSettingsSignature(mergedFeatures)
        if (signature != buildSettingsSignature(localFeatures)) {
            applySettingsBlob(profileId, mergedFeatures, signature)
        }
    }

    private suspend fun pullProfileFromRemote(profileId: Int): JsonObject? {
        if (websiteSync.get().isPaired) {
            return websiteSync.get().reconcileSettings(profileId, exportSettingsBlob(profileId))
        }
        return pullSupabaseBlob(profileId)
    }

    private suspend fun pullSupabaseBlob(profileId: Int): JsonObject? {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", SETTINGS_SYNC_PLATFORM)
        }
        val response = withJwtRefreshRetry {
            postgrest.rpc("sync_pull_profile_settings_blob", params)
        }
        return response.decodeList<SupabaseProfileSettingsBlob>().firstOrNull()?.settingsJson
    }

    private suspend fun applySettingsBlob(
        profileId: Int,
        featuresJson: JsonObject,
        signature: String
    ) {
        val isActiveProfile = profileManager.activeProfileId.value == profileId
        importSettingsBlob(profileId, featuresJson)
        if (isActiveProfile) {
            skipNextPushSignature = signature
        }
    }

    private suspend fun copyProviderCredentialsLocally(sourceProfileId: Int, targetProfileId: Int) {
        credentialProfileSettingsKeys.forEach { (feature, keyNames) ->
            val sourcePreferences = profileDataStoreFactory.get(sourceProfileId, feature).data.first()
            profileDataStoreFactory.get(targetProfileId, feature).edit { targetPreferences ->
                keyNames.forEach { keyName ->
                    val key = stringPreferencesKey(keyName)
                    val sourceValue = sourcePreferences[key]?.trim().orEmpty()
                    val targetValue = targetPreferences[key]?.trim().orEmpty()
                    if (sourceValue.isNotEmpty() && targetValue.isEmpty()) {
                        targetPreferences[key] = sourceValue
                    }
                }
            }
        }
    }

    private suspend fun exportSettingsBlob(profileId: Int): JsonObject {
        val features = buildJsonObject {
            syncedFeatures.forEach { feature ->
                val prefs = profileDataStoreFactory.get(profileId, feature).data.first()
                val serialized = buildJsonObject {
                    prefs.asMap().forEach { (key, rawValue) ->
                        if (shouldExcludePreferenceFromProfileSettingsSync(feature, key.name)) return@forEach
                        val encoded = encodePreferenceValue(rawValue) ?: return@forEach
                        put(key.name, encoded)
                    }
                }
                put(feature, serialized)
            }
        }

        return buildJsonObject {
            put("version", 1)
            put("features", features)
        }
    }

    private suspend fun importSettingsBlob(profileId: Int, featuresJson: JsonObject) {
        applyingRemoteBlob = true
        try {
            syncedFeatures.forEach { feature ->
                val featureJson = featuresJson[feature]?.jsonObject ?: return@forEach
                profileDataStoreFactory.get(profileId, feature).edit { mutablePrefs ->
                    val preservedEntries = captureLocalOnlyPreferenceEntries(feature, mutablePrefs)
                    val priorDiscoverLocation = if (feature == "layout_settings") {
                        mutablePrefs[stringPreferencesKey("discover_location")]
                    } else null
                    val priorLastNonOff = if (feature == "layout_settings") {
                        mutablePrefs[stringPreferencesKey("last_non_off_discover_location")]?.let {
                            runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
                        }?.takeIf { it != DiscoverLocation.OFF }
                    } else null

                    mutablePrefs.clear()
                    val hasWellFormedNewDiscoverKey = feature == "layout_settings" &&
                        extractDiscoverLocationString(featureJson) != null
                    featureJson.forEach { (keyName, encodedValue) ->
                        if (shouldExcludePreferenceFromProfileSettingsSync(feature, keyName)) {
                            if (feature != "layout_settings" || keyName != "search_discover_enabled") return@forEach
                            if (!hasWellFormedNewDiscoverKey) {
                                val legacy = (encodedValue as? JsonObject)
                                    ?.get("value")?.jsonPrimitive?.contentOrNull
                                    ?.toBooleanStrictOrNull()
                                if (legacy != null) {
                                    val translated = DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy)
                                    val priorLocation = priorDiscoverLocation?.let {
                                        runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
                                    }
                                    val priorIsValidNonOff = priorLocation != null && priorLocation != DiscoverLocation.OFF
                                    when {
                                        translated == DiscoverLocation.OFF ->
                                            mutablePrefs[stringPreferencesKey("discover_location")] = translated.name
                                        priorIsValidNonOff -> {}
                                        priorLastNonOff != null ->
                                            mutablePrefs[stringPreferencesKey("discover_location")] = priorLastNonOff.name
                                        else ->
                                            mutablePrefs[stringPreferencesKey("discover_location")] = translated.name
                                    }
                                }
                            }
                            return@forEach
                        }
                        if (feature == "layout_settings" && keyName == "discover_location" && !hasWellFormedNewDiscoverKey) return@forEach
                        applyEncodedPreference(mutablePrefs, keyName, encodedValue)
                    }

                    restorePreferenceEntries(mutablePrefs, preservedEntries)
                    if (feature == "layout_settings" && priorDiscoverLocation != null) {
                        val discoverKey = stringPreferencesKey("discover_location")
                        if (mutablePrefs[discoverKey] == null) {
                            mutablePrefs[discoverKey] = priorDiscoverLocation
                        }
                    }
                    if (feature == "layout_settings") {
                        val finalDiscover = mutablePrefs[stringPreferencesKey("discover_location")]?.let {
                            runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
                        }
                        if (finalDiscover != null && finalDiscover != DiscoverLocation.OFF) {
                            mutablePrefs[stringPreferencesKey("last_non_off_discover_location")] =
                                finalDiscover.name
                        }
                    }
                }
            }
        } finally {
            applyingRemoteBlob = false
        }
    }

    private fun observeLocalSettingsChangesAndSync() {
        scope.launch {
            profileManager.activeProfileId
                .flatMapLatest { profileId ->
                    val featureFlows = syncedFeatures.map { feature ->
                        profileDataStoreFactory.get(profileId, feature).data
                            .map { prefs ->
                                "$feature={${buildFeatureSignature(prefs, feature)}}"
                            }
                    }
                    combine(featureFlows) { signatures ->
                        profileId to signatures.joinToString(separator = "||")
                    }
                        .distinctUntilChanged()
                        // The first value is the profile's stored state, not a local edit. On a
                        // profile switch it can be older than the cloud copy that is about to be
                        // pulled, so pushing it would overwrite newer settings.
                        .drop(1)
                }
                .debounce(SETTINGS_PUSH_DEBOUNCE_MS)
                .collect { (profileId, signature) ->
                    if (!canSync) return@collect
                    if (applyingRemoteBlob) return@collect
                    if (profileDataStoreFactory.corruptedFileNames.isNotEmpty()) {
                        Log.w(TAG, "DataStore corruption detected (${profileDataStoreFactory.corruptedFileNames}) — pulling from remote instead of pushing")
                        profileDataStoreFactory.corruptedFileNames.clear()
                        pullCurrentProfileFromRemote()
                        return@collect
                    }
                    if (signature == skipNextPushSignature) {
                        skipNextPushSignature = null
                        return@collect
                    }
                    // A change debounced across a profile switch still belongs to the profile it was made on.
                    pushProfileSettingsToRemote(profileId)
                }
        }
    }

    private suspend fun buildSettingsSignature(profileId: Int): String {
        val signatures = ArrayList<String>(syncedFeatures.size)
        syncedFeatures.forEach { feature ->
            val prefs = profileDataStoreFactory.get(profileId, feature).data.first()
            signatures += "$feature={${buildFeatureSignature(prefs, feature)}}"
        }
        return signatures.joinToString(separator = "||")
    }

    private fun extractDiscoverLocationString(featureJson: JsonObject): String? {
        val encoded = featureJson["discover_location"] as? JsonObject ?: return null
        val type = encoded["type"]?.jsonPrimitive?.contentOrNull
        if (type != "string") return null
        return encoded["value"]?.jsonPrimitive?.contentOrNull
    }

    private fun normalizeLayoutSettingsForSignature(featureJson: JsonObject): JsonObject {
        val hasLegacy = "search_discover_enabled" in featureJson
        val hasNewKey = "discover_location" in featureJson
        val hasLocalOnly = featureJson.keys.any { it in localOnlyLayoutProfileSettingsKeys }
        if (!hasLegacy && !hasNewKey && !hasLocalOnly) return featureJson
        val newDiscoverString = extractDiscoverLocationString(featureJson)
        if (!hasLegacy && newDiscoverString != null && !hasLocalOnly) return featureJson
        return buildJsonObject {
            featureJson.forEach { (keyName, encodedValue) ->
                when {
                    keyName == "search_discover_enabled" -> return@forEach
                    keyName == "discover_location" && newDiscoverString == null -> return@forEach
                    keyName in localOnlyLayoutProfileSettingsKeys -> return@forEach
                    else -> put(keyName, encodedValue)
                }
            }
            if (newDiscoverString == null && hasLegacy) {
                val legacy = (featureJson["search_discover_enabled"] as? JsonObject)
                    ?.get("value")?.jsonPrimitive?.contentOrNull
                    ?.toBooleanStrictOrNull()
                if (legacy != null) {
                    put(
                        "discover_location",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "value",
                                DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy).name
                            )
                        }
                    )
                }
            }
        }
    }

    private fun buildSettingsSignature(featuresJson: JsonObject): String {
        return syncedFeatures.joinToString(separator = "||") { feature ->
            val featureJson = featuresJson[feature]?.jsonObject ?: JsonObject(emptyMap())
            val normalized = if (feature == "layout_settings") {
                normalizeLayoutSettingsForSignature(featureJson)
            } else {
                featureJson
            }
            "$feature={${buildFeatureSignature(normalized, feature)}}"
        }
    }

    private fun buildFeatureSignature(prefs: Preferences, feature: String = ""): String {
        return prefs.asMap()
            .entries
            .mapNotNull { (key, rawValue) ->
                if (shouldExcludePreferenceFromProfileSettingsSync(feature, key.name)) return@mapNotNull null
                encodePreferenceValue(rawValue)?.let { encoded ->
                    key.name to encoded.toString()
                }
            }
            .sortedBy { it.first }
            .joinToString(separator = "|") { (key, value) -> "$key=$value" }
    }

    private fun buildFeatureSignature(featureJson: JsonObject, feature: String = ""): String {
        return featureJson.entries
            .filterNot { (key, _) -> shouldExcludePreferenceFromProfileSettingsSync(feature, key) }
            .sortedBy { it.key }
            .joinToString(separator = "|") { (key, value) -> "$key=$value" }
    }

    private fun captureLocalOnlyPreferenceEntries(
        feature: String,
        mutablePrefs: MutablePreferences
    ): Map<Preferences.Key<*>, Any> {
        val keyNames = when (feature) {
            "layout_settings" -> catalogKeysExcludedFromProfileSettingsBlob + localOnlyLayoutProfileSettingsKeys
            PLAYER_SETTINGS_FEATURE -> localOnlyPlayerProfileSettingsKeys
            "trailer_settings" -> localOnlyTrailerProfileSettingsKeys
            else -> credentialProfileSettingsKeys[feature].orEmpty()
        }
        if (keyNames.isEmpty()) return emptyMap()
        val entries = mutableMapOf<Preferences.Key<*>, Any>()
        keyNames.forEach { keyName ->
            val stringKey = stringPreferencesKey(keyName)
            runCatching { mutablePrefs[stringKey] }.getOrNull()?.let { entries[stringKey] = it }
            val booleanKey = booleanPreferencesKey(keyName)
            runCatching { mutablePrefs[booleanKey] }.getOrNull()?.let { entries[booleanKey] = it }
            val intKey = intPreferencesKey(keyName)
            runCatching { mutablePrefs[intKey] }.getOrNull()?.let { entries[intKey] = it }
            val longKey = longPreferencesKey(keyName)
            runCatching { mutablePrefs[longKey] }.getOrNull()?.let { entries[longKey] = it }
            val floatKey = floatPreferencesKey(keyName)
            runCatching { mutablePrefs[floatKey] }.getOrNull()?.let { entries[floatKey] = it }
            val doubleKey = doublePreferencesKey(keyName)
            runCatching { mutablePrefs[doubleKey] }.getOrNull()?.let { entries[doubleKey] = it }
            val stringSetKey = stringSetPreferencesKey(keyName)
            runCatching { mutablePrefs[stringSetKey] }.getOrNull()?.let { entries[stringSetKey] = it }
        }
        return entries
    }

    @Suppress("UNCHECKED_CAST")
    private fun restorePreferenceEntries(
        mutablePrefs: MutablePreferences,
        entries: Map<Preferences.Key<*>, Any>
    ) {
        val gson = com.google.gson.Gson()
        entries.forEach { (key, value) ->
            when (value) {
                is String -> mutablePrefs[key as Preferences.Key<String>] = value
                is Boolean -> mutablePrefs[key as Preferences.Key<Boolean>] = value
                is Int -> mutablePrefs[key as Preferences.Key<Int>] = value
                is Long -> mutablePrefs[key as Preferences.Key<Long>] = value
                is Float -> mutablePrefs[key as Preferences.Key<Float>] = value
                is Double -> mutablePrefs[key as Preferences.Key<Double>] = value
                is Set<*> -> {
                    if (value.all { it is String }) {
                        // Catalog keys should be stored as JSON strings, not Sets.
                        // Convert to prevent ClassCastException on read.
                        if (key.name in catalogKeysExcludedFromProfileSettingsBlob) {
                            val jsonValue = gson.toJson((value as Set<String>).toList())
                            mutablePrefs[stringPreferencesKey(key.name)] = jsonValue
                        } else {
                            mutablePrefs[key as Preferences.Key<Set<String>>] = value as Set<String>
                        }
                    }
                }
            }
        }
    }

    private fun encodePreferenceValue(rawValue: Any?): JsonObject? {
        return when (rawValue) {
            is String -> buildJsonObject {
                put("type", "string")
                put("value", rawValue)
            }
            is Boolean -> buildJsonObject {
                put("type", "boolean")
                put("value", rawValue)
            }
            is Int -> buildJsonObject {
                put("type", "int")
                put("value", rawValue)
            }
            is Long -> buildJsonObject {
                put("type", "long")
                put("value", rawValue)
            }
            is Float -> buildJsonObject {
                put("type", "float")
                put("value", rawValue)
            }
            is Double -> buildJsonObject {
                put("type", "double")
                put("value", rawValue)
            }
            is Set<*> -> {
                val allStrings = rawValue.all { it is String }
                if (!allStrings) return null
                buildJsonObject {
                    put("type", "string_set")
                    val values = rawValue.map { it as String }.sorted()
                    put("value", JsonArray(values.map { JsonPrimitive(it) }))
                }
            }
            else -> null
        }
    }

    private fun applyEncodedPreference(
        mutablePrefs: androidx.datastore.preferences.core.MutablePreferences,
        keyName: String,
        encodedValue: JsonElement
    ) {
        val obj = encodedValue as? JsonObject ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
        val value = obj["value"] ?: JsonNull

        when (type) {
            "string" -> {
                val parsed = value.jsonPrimitive.contentOrNull ?: return
                mutablePrefs[stringPreferencesKey(keyName)] = parsed
            }
            "boolean" -> {
                val parsed = value.jsonPrimitive.contentOrNull?.toBooleanStrictOrNull() ?: return
                mutablePrefs[booleanPreferencesKey(keyName)] = parsed
            }
            "int" -> {
                val parsed = value.jsonPrimitive.intOrNull ?: return
                mutablePrefs[intPreferencesKey(keyName)] = parsed
            }
            "long" -> {
                val parsed = value.jsonPrimitive.longOrNull ?: return
                mutablePrefs[longPreferencesKey(keyName)] = parsed
            }
            "float" -> {
                val parsed = value.jsonPrimitive.floatOrNull ?: return
                mutablePrefs[floatPreferencesKey(keyName)] = parsed
            }
            "double" -> {
                val parsed = value.jsonPrimitive.doubleOrNull ?: return
                mutablePrefs[doublePreferencesKey(keyName)] = parsed
            }
            "string_set" -> {
                val parsed = value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                mutablePrefs[stringSetPreferencesKey(keyName)] = parsed
            }
        }
    }
}
