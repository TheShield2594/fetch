package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopProfile

/**
 * The sync server's side of "Copy settings", as the TV does it: the server copies [source]'s
 * settings blob onto [target] (`sync_copy_profile_setup`), then [target]'s own limits go back,
 * since the copy brings the source's. [target]'s limits are this computer's, tightened by what
 * the account held before the copy, so the copy never loosens them.
 */
suspend fun SyncClient.copyProfileSetupKeepingLimits(source: DesktopProfile, target: DesktopProfile, copyMissingKeys: Boolean) {
    val targetLimitsBefore = if (target.isPrimary) null else pullParentalLimits(target.id)
    copyProfileSetup(source.id, target.id, copyMissingKeys)
    if (!target.isPrimary) {
        val limits = SyncMerge.parentalLimits(target.limits, targetLimitsBefore)
        pushParentalLimits(target.id, SyncMerge.parentalLimitsToPush(limits, null))
    }
}
