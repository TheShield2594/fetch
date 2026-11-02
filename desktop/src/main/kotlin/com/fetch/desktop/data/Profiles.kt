package com.fetch.desktop.data

import com.fetch.tv.domain.model.PROFILE_AVATAR_COLORS
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A profile, as on the TV app: its own Library, Continue Watching, settings and accounts. The
 * main profile (id 1) can't be deleted, and keeps its files directly in the config folder.
 */
@Serializable
data class DesktopProfile(
    val id: Int,
    val name: String,
    val colorHex: String,
    /** Share the main profile's addons instead of keeping its own list. */
    val usesPrimaryAddons: Boolean = true
) {
    val isPrimary: Boolean get() = id == PRIMARY_ID

    companion object {
        const val PRIMARY_ID = 1
    }
}

@Serializable
data class ProfilesFile(
    val profiles: List<DesktopProfile> = listOf(DesktopProfile(DesktopProfile.PRIMARY_ID, "Me", PROFILE_AVATAR_COLORS.first())),
    val lastProfileId: Int? = null,
    /** "Remember Last Profile": skip the picker at start-up. */
    val rememberLastProfile: Boolean = true
) {
    fun nextId(): Int = (profiles.maxOfOrNull { it.id } ?: 0) + 1

    /** The next unused avatar color, cycling once all are taken. */
    fun nextColor(): String =
        PROFILE_AVATAR_COLORS.firstOrNull { color -> profiles.none { it.colorHex.equals(color, true) } }
            ?: PROFILE_AVATAR_COLORS[profiles.size % PROFILE_AVATAR_COLORS.size]
}

fun profilesFile(dir: File = AppDirs.config) =
    JsonFile(File(dir, "profiles.json"), ProfilesFile.serializer()) { ProfilesFile() }

/** Where [profile] keeps its files: the config folder itself for the main profile. */
fun profileDir(profile: DesktopProfile, root: File = AppDirs.config): File =
    if (profile.isPrimary) root else File(root, "profiles/${profile.id}")

/** Where [profile]'s addon list lives: the main profile's when it shares it. */
fun addonsDir(profile: DesktopProfile, root: File = AppDirs.config): File =
    if (profile.usesPrimaryAddons) root else profileDir(profile, root)
