package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.ProfileLimits
import com.fetch.desktop.data.ProfilesFile
import com.fetch.desktop.parental.PinGate
import com.fetch.tv.core.parental.ProfilePinHasher
import com.fetch.desktop.data.profileDir
import com.fetch.desktop.data.profilesFile
import java.io.File

/**
 * The profiles and which one is active. [active] is null while the "Who's watching?" picker is
 * up: at start-up with several profiles and Remember Last Profile off, or after "Switch profile".
 */
class ProfilesState(
    private val store: JsonFile<ProfilesFile> = profilesFile(),
    private val root: File = AppDirs.config
) {
    var file: ProfilesFile by mutableStateOf(store.load())
        private set

    var active: DesktopProfile? by mutableStateOf(initialProfile(file))
        private set

    val profiles: List<DesktopProfile> get() = file.profiles

    /** Titles hidden from or allowed on each profile, set from a poster's menu. */
    val titleLists = com.fetch.desktop.parental.TitleListsState(com.fetch.desktop.parental.titleListsFile(root))

    fun select(profile: DesktopProfile) {
        active = profile
        save(file.copy(lastProfileId = profile.id))
    }

    /** Shows the picker. */
    fun switchProfile() {
        active = null
    }

    fun add(
        name: String,
        colorHex: String = file.nextColor(),
        usesPrimaryAddons: Boolean = true,
        avatarId: String? = null,
        limits: ProfileLimits = ProfileLimits()
    ): DesktopProfile? {
        if (file.isFull) return null
        val profile = DesktopProfile(
            file.nextId(), name.trim().ifEmpty { "Profile ${file.nextId()}" }, colorHex, usesPrimaryAddons,
            limits = limits, avatarId = avatarId
        )
        save(file.copy(profiles = file.profiles + profile))
        listener?.onProfilesChanged(null)
        if (limits.isRestricted) listener?.onLimitsChanged(profile)
        return profile
    }

    fun update(profile: DesktopProfile) {
        val before = file.profiles.firstOrNull { it.id == profile.id }
        save(file.copy(profiles = file.profiles.map { if (it.id == profile.id) profile else it }))
        if (active?.id == profile.id) active = profile
        // Only what the account keeps (name, colour, avatar, background, addon sharing) needs a push.
        if (before != null && (before.name != profile.name || before.colorHex != profile.colorHex ||
                before.usesPrimaryAddons != profile.usesPrimaryAddons || before.avatarId != profile.avatarId ||
                before.avatarCleared != profile.avatarCleared || before.profileBackgroundId != profile.profileBackgroundId ||
                before.profileBackgroundUrl != profile.profileBackgroundUrl || before.backgroundEdited != profile.backgroundEdited)) {
            listener?.onProfilesChanged(null)
        }
    }

    /** Deletes a profile and everything it saved. The main profile can't be deleted. */
    fun delete(profile: DesktopProfile) {
        if (profile.isPrimary) return
        save(file.copy(profiles = file.profiles.filter { it.id != profile.id }, lastProfileId = file.lastProfileId.takeIf { it != profile.id }))
        profileDir(profile, root).deleteRecursively()
        titleLists.deleteProfile(profile.id)
        if (active?.id == profile.id) active = file.profiles.first()
        listener?.onProfilesChanged(profile)
    }

    fun setRememberLastProfile(remember: Boolean) = save(file.copy(rememberLastProfile = remember))

    /** The TV's "lock unrestricted profiles"; see [ProfilesFile.lockUnrestrictedProfiles]. */
    fun setLockUnrestrictedProfiles(enabled: Boolean) = save(file.copy(lockUnrestrictedProfiles = enabled))

    fun setLimitsPushPending(profileId: Int, pending: Boolean) {
        val ids = if (pending) file.pendingLimitsPush + profileId else file.pendingLimitsPush - profileId
        if (ids != file.pendingLimitsPush) save(file.copy(pendingLimitsPush = ids))
    }

    /**
     * Checks a PIN with the sync server when no profile is open to do it (the start-up picker);
     * set by the app while signed in. The open profile's [listener] is asked first.
     */
    var remotePinCheck: (suspend (DesktopProfile, String) -> PinGate.Result?)? = null

    fun needsParentPinToOpen(profile: DesktopProfile): Boolean =
        file.needsParentPinToOpen(file.profiles.firstOrNull { it.id == profile.id } ?: profile)

    /** Sets (or with null, removes) [profile]'s PIN. Callers check the current PIN first. */
    fun setPin(profile: DesktopProfile, pin: String?) {
        val current = file.profiles.firstOrNull { it.id == profile.id } ?: return
        update(current.copy(pinHash = pin?.let { ProfilePinHasher.hash(it) }, serverLocked = false))
        listener?.onPinChanged(current, pin)
    }

    fun setLimits(profile: DesktopProfile, limits: ProfileLimits) {
        val current = file.profiles.firstOrNull { it.id == profile.id } ?: return
        update(current.copy(limits = limits))
        listener?.onLimitsChanged(current.copy(limits = limits))
    }

    // --- Sync server ---

    /** What the sync server needs to hear about; set while signed in. */
    interface SyncListener {
        fun onProfilesChanged(deleted: DesktopProfile?)
        fun onPinChanged(profile: DesktopProfile, pin: String?)
        fun onLimitsChanged(profile: DesktopProfile)
        /** Checks a PIN set on another device; null when the server can't be asked. */
        suspend fun verifyRemotePin(profile: DesktopProfile, pin: String): PinGate.Result?
    }

    var listener: SyncListener? = null

    /**
     * Replaces the profiles with the account's (names, colours, addon sharing, which are locked),
     * as the TV app does on sync. Profiles the account no longer has leave the list, but their
     * files stay on disk.
     */
    fun applyFromServer(profiles: List<DesktopProfile>) {
        if (profiles == file.profiles) return
        save(file.copy(profiles = profiles))
        active?.let { current -> active = profiles.firstOrNull { it.id == current.id } ?: current }
    }

    /** Checks [pin] for [profile]: its PIN here, or the server's when it was only set elsewhere. */
    suspend fun checkPin(profile: DesktopProfile, pin: String): PinGate.Result {
        val current = file.profiles.firstOrNull { it.id == profile.id } ?: profile
        if (current.pinHash == null && current.serverLocked) {
            val remote = (listener?.verifyRemotePin(current, pin) ?: remotePinCheck?.invoke(current, pin))
                ?: return PinGate.Result.Wrong("Connect to your sync server to check this PIN.")
            // Right PIN: keep it here too, so it works offline next time.
            if (remote == PinGate.Result.Ok) update(current.copy(pinHash = ProfilePinHasher.hash(pin), serverLocked = false))
            return remote
        }
        return pinGate.check(current.id, current.pinHash, pin)
    }

    val primary: DesktopProfile get() = file.profiles.first { it.isPrimary }

    /** PIN checks with the TV app's lockout after repeated wrong PINs. */
    val pinGate = PinGate()

    private fun save(updated: ProfilesFile) {
        file = updated
        runCatching { store.save(updated) }
    }

    companion object {
        /** The profile to open at start-up, or null to ask. */
        fun initialProfile(file: ProfilesFile): DesktopProfile? {
            val candidate = when {
                file.profiles.size == 1 -> file.profiles.first()
                !file.rememberLastProfile -> return null
                else -> file.profiles.firstOrNull { it.id == file.lastProfileId } ?: file.profiles.first()
            }
            // A profile with a PIN always asks, as on TV, and so does one behind the main profile's.
            return candidate.takeUnless { it.isLocked || file.needsParentPinToOpen(it) }
        }
    }
}
