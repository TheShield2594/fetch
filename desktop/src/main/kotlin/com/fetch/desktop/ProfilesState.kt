package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.ProfilesFile
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

    fun select(profile: DesktopProfile) {
        active = profile
        save(file.copy(lastProfileId = profile.id))
    }

    /** Shows the picker. */
    fun switchProfile() {
        active = null
    }

    fun add(name: String, colorHex: String = file.nextColor(), usesPrimaryAddons: Boolean = true): DesktopProfile {
        val profile = DesktopProfile(file.nextId(), name.trim().ifEmpty { "Profile ${file.nextId()}" }, colorHex, usesPrimaryAddons)
        save(file.copy(profiles = file.profiles + profile))
        return profile
    }

    fun update(profile: DesktopProfile) {
        save(file.copy(profiles = file.profiles.map { if (it.id == profile.id) profile else it }))
        if (active?.id == profile.id) active = profile
    }

    /** Deletes a profile and everything it saved. The main profile can't be deleted. */
    fun delete(profile: DesktopProfile) {
        if (profile.isPrimary) return
        save(file.copy(profiles = file.profiles.filter { it.id != profile.id }, lastProfileId = file.lastProfileId.takeIf { it != profile.id }))
        profileDir(profile, root).deleteRecursively()
        if (active?.id == profile.id) active = file.profiles.first()
    }

    fun setRememberLastProfile(remember: Boolean) = save(file.copy(rememberLastProfile = remember))

    private fun save(updated: ProfilesFile) {
        file = updated
        runCatching { store.save(updated) }
    }

    companion object {
        /** The profile to open at start-up, or null to ask. */
        fun initialProfile(file: ProfilesFile): DesktopProfile? {
            if (file.profiles.size == 1) return file.profiles.first()
            if (!file.rememberLastProfile) return null
            return file.profiles.firstOrNull { it.id == file.lastProfileId } ?: file.profiles.first()
        }
    }
}
