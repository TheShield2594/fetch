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
    val usesPrimaryAddons: Boolean = true,
    /** The TV app's PIN format (salted PBKDF2, see ProfilePinHasher); null when not locked. */
    val pinHash: String? = null,
    /** Limits, set from the main profile, as on TV. */
    val limits: ProfileLimits = ProfileLimits(),
    /** Locked on the sync server (set on another device) with no PIN here yet; checked online. */
    val serverLocked: Boolean = false,
    /** The TV's avatar id: a bundled one (`fetch:fox`) or the sync server catalog's; null shows the initial. */
    val avatarId: String? = null,
    /** A picture of the profile's own on the sync server (the TV's `avatar_url`), shown when set. */
    val avatarUrl: String? = null,
    /**
     * The avatar was removed here: the next push clears the account's too. Without it a null
     * [avatarId] keeps the account's picture, so profiles saved before avatars existed here don't
     * wipe one set on the TV.
     */
    val avatarCleared: Boolean = false,
    /** The TV's profile background: a catalog id (`get_member_profile_background_catalog`). */
    val profileBackgroundId: String? = null,
    /** Or a picture of its own; wins over [profileBackgroundId], as on TV. */
    val profileBackgroundUrl: String? = null,
    /** The background was picked here; until then a push keeps the account's. */
    val backgroundEdited: Boolean = false
) {
    val isPrimary: Boolean get() = id == PRIMARY_ID
    val isLocked: Boolean get() = pinHash != null || serverLocked

    override fun toString(): String = "DesktopProfile(id=$id, name=$name, locked=$isLocked)"

    companion object {
        const val PRIMARY_ID = 1
    }
}

@Serializable
data class ProfilesFile(
    val profiles: List<DesktopProfile> = listOf(DesktopProfile(DesktopProfile.PRIMARY_ID, "Me", PROFILE_AVATAR_COLORS.first())),
    val lastProfileId: Int? = null,
    /** "Remember Last Profile": skip the picker at start-up. */
    val rememberLastProfile: Boolean = true,
    /**
     * The TV's "lock unrestricted profiles": while some profile has limits and the main profile
     * has a PIN, a profile with neither limits nor a PIN of its own asks for the main profile's
     * PIN to open, so a restricted profile can't skip its limits by switching. Kept on this
     * computer, as on TV.
     */
    val lockUnrestrictedProfiles: Boolean = false,
    /**
     * Profiles whose limits a server-side settings copy replaced with the source's, with the exact
     * limits the account still owes them (theirs, tightened by the account's before the copy).
     * Pushed on the next account sync; until then the account's copy of them isn't merged in.
     */
    val pendingLimitsPush: Map<Int, ProfileLimits> = emptyMap()
) {
    /** The lowest free id: the sync server numbers profiles 1 to [MAX_PROFILES], like the TV app. */
    fun nextId(): Int = (2..MAX_PROFILES).firstOrNull { id -> profiles.none { it.id == id } } ?: ((profiles.maxOfOrNull { it.id } ?: 0) + 1)

    val isFull: Boolean get() = profiles.size >= MAX_PROFILES

    val primary: DesktopProfile? get() = profiles.firstOrNull { it.isPrimary }

    /** Whether [profile] asks for the main profile's PIN to open (see [lockUnrestrictedProfiles]). */
    fun needsParentPinToOpen(profile: DesktopProfile): Boolean {
        if (!lockUnrestrictedProfiles || profile.isPrimary || profile.isLocked || profile.limits.isRestricted) return false
        if (primary?.isLocked != true) return false
        return profiles.any { it.limits.isRestricted }
    }

    /**
     * The profiles a restricted one could open to skip its limits: not the main one, with no PIN
     * and no limits. The TV offers to lock these once a profile gets limits.
     */
    fun unlockedUnrestricted(except: DesktopProfile? = null): List<DesktopProfile> =
        profiles.filter { !it.isPrimary && it.id != except?.id && !it.isLocked && !it.limits.isRestricted }

    /** The next unused avatar color, cycling once all are taken. */
    fun nextColor(): String =
        PROFILE_AVATAR_COLORS.firstOrNull { color -> profiles.none { it.colorHex.equals(color, true) } }
            ?: PROFILE_AVATAR_COLORS[profiles.size % PROFILE_AVATAR_COLORS.size]
}

/** The TV app's profile limit, which the sync server enforces. */
const val MAX_PROFILES = 6

fun profilesFile(dir: File = AppDirs.config) =
    JsonFile(File(dir, "profiles.json"), ProfilesFile.serializer()) { ProfilesFile() }

/** Where [profile] keeps its files: the config folder itself for the main profile. */
fun profileDir(profile: DesktopProfile, root: File = AppDirs.config): File =
    if (profile.isPrimary) root else File(root, "profiles/${profile.id}")

/** Where [profile]'s addon list lives: the main profile's when it shares it. */
fun addonsDir(profile: DesktopProfile, root: File = AppDirs.config): File =
    if (profile.usesPrimaryAddons) root else profileDir(profile, root)

/**
 * The TV app's profile limits: the highest rating allowed, screen time a day, a bedtime and how
 * many episodes Up Next plays in a row.
 */
@Serializable
data class ProfileLimits(
    /** A [com.fetch.tv.core.parental.CertificationLevel] name; null means no limit. */
    val maxRating: String? = null,
    /** Whether titles with no known rating are allowed under a rating limit. */
    val allowUnrated: Boolean = true,
    val dailyMinutes: Int? = null,
    /** Minutes after midnight when bedtime starts; it ends at 7:00. */
    val bedtimeStartMinute: Int? = null,
    /** Episodes Up Next plays in a row before stopping; null means no cap. */
    val autoplayCap: Int? = null,
    /** A kids profile, as on TV: a Home of its own (see [com.fetch.desktop.home.HomeLayout]). Synced. */
    val kidsProfile: Boolean = false
) {
    val isRestricted: Boolean
        get() = maxRating != null || dailyMinutes != null || bedtimeStartMinute != null || autoplayCap != null || kidsProfile
}
