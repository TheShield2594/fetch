package com.fetch.desktop.sync

import com.fetch.desktop.ProfilesState
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.parental.PinGate

/**
 * The sync server's side of "Copy settings", as the TV does it. The profile list goes first, so a
 * profile created a moment ago exists on the account; then the server copies [source]'s settings
 * blob onto [target] (`sync_copy_profile_setup`), and [target]'s own limits go back, since the
 * copy brings the source's. Those limits are this computer's, tightened by what the account held
 * before the copy, so the copy never loosens them.
 *
 * [target] is marked in [profiles] as owing a limits push before the copy and cleared only once
 * the push lands, so a failure or a closed app is put right on the next account sync.
 */
suspend fun SyncClient.copyProfileSetupKeepingLimits(
    profiles: ProfilesState,
    source: DesktopProfile,
    target: DesktopProfile,
    copyMissingKeys: Boolean
) {
    pushProfiles(SyncMerge.profilesToPush(profiles.profiles, pullProfiles()))
    if (target.isPrimary) {
        copyProfileSetup(source.id, target.id, copyMissingKeys)
        return
    }
    // The limits to restore, saved before the copy so a retry pushes exactly these.
    val limits = SyncMerge.parentalLimits(target.limits, pullParentalLimits(target.id))
    profiles.setPendingLimits(target.id, limits)
    copyProfileSetup(source.id, target.id, copyMissingKeys)
    pushParentalLimits(target.id, SyncMerge.parentalLimitsToPush(limits, null))
    profiles.setPendingLimits(target.id, null)
}

/**
 * Checks a PIN set on another device with the sync server, as the TV does; null when the server
 * can't be asked. Used by the open profile and by the start-up picker, where none is open.
 */
suspend fun SyncClient.verifyPin(profile: DesktopProfile, pin: String): PinGate.Result? {
    val result = runCatching { verifyProfilePin(profile.id, pin) }.getOrNull() ?: return null
    return when {
        result.unlocked -> PinGate.Result.Ok
        result.retryAfterSeconds > 0 -> PinGate.Result.Locked(result.retryAfterSeconds)
        else -> PinGate.Result.Wrong("Incorrect PIN")
    }
}
