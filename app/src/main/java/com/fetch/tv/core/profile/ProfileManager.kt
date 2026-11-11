package com.fetch.tv.core.profile

import android.content.Context
import com.fetch.tv.R
import com.fetch.tv.data.local.ContinueWatchingEnrichmentCache
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.local.ProfileDataStoreFactory
import com.fetch.tv.data.local.ProfileLockStateDataStore
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    private val credentialStores: Set<@JvmSuppressWildcards ProfileScopedCredentialStore>,
    private val profileLockStateDataStore: ProfileLockStateDataStore,
    private val profileRestrictionsDataStore: ProfileRestrictionsDataStore,
    private val screenTimeStore: com.fetch.tv.core.parental.ScreenTimeStore,
    private val profileDatabaseData: com.fetch.tv.data.local.db.ProfileDatabaseData,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_PROFILES = 6
        private const val PROFILE_LIST_UPDATE_TIMEOUT_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    val activeProfileReady: StateFlow<Boolean> = profileDataStore.activeProfileId
        .map { true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val hasEverSelectedProfile: StateFlow<Boolean> = profileDataStore.hasEverSelectedProfile
        .stateIn(scope, SharingStarted.Eagerly, false)

    val rememberLastProfileEnabled: StateFlow<Boolean> = profileDataStore.rememberLastProfileEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val confirmExitEnabled: StateFlow<Boolean> = profileDataStore.confirmExitEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val startupSplashEnabled: StateFlow<Boolean> = profileDataStore.startupSplashEnabled
        .stateIn(scope, SharingStarted.Eagerly, true)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = context.getString(R.string.profile_default_name, 1), avatarColorHex = "#1E88E5")
        ))

    /**
     * The profile list read from disk, or null before it has been read. Unlike [profiles] there is
     * no placeholder, and readiness and value arrive together as one state, so a decision that
     * depends on every profile (such as whether a sibling is restricted) can't see "loaded" paired
     * with the primary-only placeholder.
     */
    val loadedProfiles: StateFlow<List<UserProfile>?> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The active profile id read from disk, or null before it has been read (see [loadedProfiles]). */
    val loadedActiveProfileId: StateFlow<Int?> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val sessionRef = java.util.concurrent.atomic.AtomicReference(ProfileSession(activeProfileId.value))

    /**
     * The active profile's [ProfileSession]. A new session (with empty caches) replaces the old
     * one as soon as [activeProfileId] changes, so per-profile in-memory state is dropped with
     * the profile it belongs to rather than cleared by hand on each switch.
     */
    val activeSession: ProfileSession
        get() {
            val id = activeProfileId.value
            while (true) {
                val current = sessionRef.get()
                if (current.profileId == id) return current
                val next = ProfileSession(id)
                if (sessionRef.compareAndSet(current, next)) return next
            }
        }

    /**
     * The active profile id, read once by the caller at the start of an operation and passed
     * down explicitly. Stores and repositories take the id as a parameter instead of reading
     * the active profile themselves part way through.
     */
    fun currentProfileId(): Int = activeProfileId.value

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    val canCreateProfile: Boolean
        get() = profiles.value.size < MAX_PROFILES

    init {
        // A deleted profile's stores are refused by the factory until the id is reused.
        // createProfile says so for a profile made here; a profile that comes back another way
        // (a sync pull re-adding the id) is noticed here: once the list has been seen without a
        // deleted id, the id showing up again means it is a profile again.
        scope.launch {
            val goneDeletedIds = mutableSetOf<Int>()
            profileDataStore.profilesList.collect { list ->
                val ids = list.mapTo(mutableSetOf()) { it.id }
                for (id in 2..MAX_PROFILES) {
                    when {
                        !factory.isProfileDeleted(id) -> goneDeletedIds.remove(id)
                        id !in ids -> goneDeletedIds.add(id)
                        goneDeletedIds.remove(id) -> factory.markProfileCreated(id)
                    }
                }
            }
        }
    }

    suspend fun setActiveProfile(id: Int) {
        val exists = profiles.value.any { it.id == id }
        if (exists) {
            profileDataStore.setActiveProfile(id)
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        profileDataStore.setRememberLastProfileEnabled(enabled)
    }

    suspend fun setConfirmExitEnabled(enabled: Boolean) {
        profileDataStore.setConfirmExitEnabled(enabled)
    }

    suspend fun setStartupSplashEnabled(enabled: Boolean) {
        profileDataStore.setStartupSplashEnabled(enabled)
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        avatarId: String? = null
    ): UserProfile? {
        val current = profiles.value
        if (current.size >= MAX_PROFILES) return null

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..MAX_PROFILES).firstOrNull { it !in usedIds } ?: return null

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { context.getString(R.string.profile_default_name, nextId) },
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            avatarId = avatarId
        )
        factory.markProfileCreated(nextId)
        profileDataStore.upsertProfile(profile)
        profiles.first { entries -> entries.any { it.id == nextId } }
        return profile
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        if (profiles.value.none { it.id == id }) return false
        credentialStores.forEach { store -> store.removeProfile(id) }
        // A new profile can reuse this id, so its PIN and limits must not survive.
        profileLockStateDataStore.removeProfile(id)
        profileRestrictionsDataStore.deleteProfile(id)
        screenTimeStore.deleteProfile(id)
        deleteProfileDataAsync(id)
        profileDataStore.deleteProfile(id)
        // A Continue Watching save that started before the cleanup above (while this
        // was still the active profile) can recreate its snapshot. Once the profile is
        // gone from the list, saves for it are skipped, so delete the snapshots again.
        withTimeoutOrNull(PROFILE_LIST_UPDATE_TIMEOUT_MS) {
            profiles.first { entries -> entries.none { it.id == id } }
        }
        withContext(Dispatchers.IO) {
            ContinueWatchingEnrichmentCache.deleteProfileFiles(context.filesDir, id)
        }
        return true
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        if (profiles.value.none { it.id == profile.id }) return false
        profileDataStore.upsertProfile(profile)
        return true
    }

    private suspend fun deleteProfileDataAsync(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == 1) return@withContext

        factory.clearProfile(profileId)
        factory.deleteProfileFiles(profileId)
        // Its watched items, kept in the database rather than DataStore. After clearProfile, so a
        // sync still running for the profile is already refused and can't add rows back.
        profileDatabaseData.deleteProfile(profileId)

        // Continue Watching snapshots are keyed by profile id too. A new profile
        // that reuses this id must not show the deleted profile's titles on Home
        // or in the launcher's Play Next row.
        ContinueWatchingEnrichmentCache.deleteProfileFiles(context.filesDir, profileId)

        // Left behind by versions that still ran plugins.
        val pluginCodeDir = File(context.filesDir, "plugin_code_p${profileId}")
        if (pluginCodeDir.exists()) {
            pluginCodeDir.deleteRecursively()
        }
    }
}
