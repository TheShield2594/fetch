package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.ProfileLimits
import com.fetch.desktop.data.ProfilesFile

/**
 * The result of [SyncMerge.addons]: [local] is the ordered list of manifest base URLs to keep on
 * this computer, [disabled] the ones of them switched off, and [push] the list to send to the
 * Fetch website, or null when nothing needs sending.
 */
data class AddonMerge(
    val local: List<String>,
    val push: List<RemoteAddon>?,
    val disabled: Set<String> = emptySet(),
)

/**
 * The result of [SyncMerge.profiles]: [local] is the profile list to keep (its PINs and limits are
 * this computer's own), [push] the list to send to the Fetch website or null, and [removedLocal]
 * the profiles here the account no longer has, whose folders can be cleared.
 */
data class ProfileMerge(
    val local: List<DesktopProfile>,
    val push: List<RemoteProfile>?,
    val removedLocal: List<DesktopProfile>,
)

/** Two-way merges of addons and profiles with the Fetch website, by the TV app's rules. */
object SyncMerge {

    // --- Addons ---

    /**
     * An addon URL as the TV app keeps it (`AddonRepositoryImpl.canonicalizeUrl`): trimmed, without
     * trailing slashes or a `/manifest.json` suffix, with any query string kept.
     */
    fun canonicalAddonUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
            path.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }

    /** What two addon URLs are compared by: the canonical URL, lowercased, as on TV. */
    fun addonKey(url: String): String = canonicalAddonUrl(url).lowercase()

    /**
     * Merges this computer's addon list with the account's, the way the TV app does on every pull
     * (`reconcileWithRemoteAddonUrls(removeMissingLocal = true)`): the account's list, in its order
     * and with its on/off states, replaces the local one; an addon in both keeps this computer's
     * spelling of its URL. An empty account list never wipes the local one: it is kept and nothing
     * is pushed, as on TV.
     *
     * With [preferLocal] (the TV app's sign-up: this device's data goes up first) the local list
     * and [localDisabled] are kept and pushed instead.
     */
    fun addons(
        local: List<String>,
        remote: List<RemoteAddon>,
        preferLocal: Boolean = false,
        localDisabled: Set<String> = emptySet(),
    ): AddonMerge {
        val localCanonical = local.map(::canonicalAddonUrl).filter { it.isNotBlank() }.distinctBy(::addonKey)
        val localDisabledKeys = localDisabled.map(::addonKey).toSet()
        val keptDisabled = localCanonical.filter { addonKey(it) in localDisabledKeys }.toSet()
        if (preferLocal) return AddonMerge(localCanonical, addonsToPush(localCanonical, remote, keptDisabled), keptDisabled)
        val remoteOrdered = remote.sortedBy { it.order }
            .filter { canonicalAddonUrl(it.url).isNotBlank() }
            .distinctBy { addonKey(it.url) }
        if (remoteOrdered.isEmpty()) return AddonMerge(localCanonical, null, keptDisabled)
        val localByKey = localCanonical.associateBy(::addonKey)
        val merged = remoteOrdered.map { (localByKey[addonKey(it.url)] ?: canonicalAddonUrl(it.url)) to it.enabled }
        return AddonMerge(merged.map { it.first }, null, merged.filter { !it.second }.map { it.first }.toSet())
    }

    /**
     * This computer's addon list as the Fetch website takes it, in local order, with the
     * ones in [disabled] switched off. Names the user set on TV are kept, and the account's
     * disabled addons that aren't installed here stay in the list, disabled, after the addon they
     * followed there, unless they are in [removed] (taken off here).
     */
    fun addonsToPush(
        local: List<String>,
        remote: List<RemoteAddon>,
        disabled: Set<String> = emptySet(),
        removed: Set<String> = emptySet(),
    ): List<RemoteAddon> {
        val remoteOrdered = remote.sortedBy { it.order }.distinctBy { addonKey(it.url) }
        val remoteByKey = remoteOrdered.associateBy { addonKey(it.url) }
        val disabledKeys = disabled.map(::addonKey).toSet()
        val removedKeys = removed.map(::addonKey).toSet()
        val result = local.map(::canonicalAddonUrl).filter { it.isNotBlank() }.distinctBy(::addonKey)
            .map { url -> RemoteAddon(url, 0, enabled = addonKey(url) !in disabledKeys, name = remoteByKey[addonKey(url)]?.name) }
            .toMutableList()
        var previousKey: String? = null
        remoteOrdered.forEach { addon ->
            val key = addonKey(addon.url)
            if (!addon.enabled && key !in removedKeys && result.none { addonKey(it.url) == key }) {
                val after = previousKey?.let { prev -> result.indexOfFirst { addonKey(it.url) == prev } } ?: -1
                result.add(after + 1, addon)
            }
            if (result.any { addonKey(it.url) == key }) previousKey = key
        }
        return result.mapIndexed { index, addon -> addon.copy(order = index) }
    }

    // --- Profiles ---

    /**
     * Merges this computer's profiles with the account's, the way the TV app pulls them
     * (`ProfileSyncService.pullFromRemote`): when the account has profiles, its list replaces the
     * local one (names, colours, "uses primary addons"); profile 1 is always kept. PIN hashes and
     * limits never travel with the profile list, so each profile keeps this computer's.
     *
     * An account with no profiles yet gets this computer's, as does [preferLocal] (the TV app
     * pushes its profiles whenever one is created, edited or deleted there).
     */
    fun profiles(local: List<DesktopProfile>, remote: List<RemoteProfile>, preferLocal: Boolean = false): ProfileMerge {
        if (preferLocal || remote.isEmpty()) return ProfileMerge(local, profilesToPush(local, remote), emptyList())
        val localById = local.associateBy { it.id }
        val merged = remote.sortedBy { it.profileIndex }.distinctBy { it.profileIndex }.map { r ->
            val mine = localById[r.profileIndex]
            DesktopProfile(
                id = r.profileIndex,
                name = r.name.ifBlank { mine?.name ?: r.name },
                colorHex = r.avatarColorHex,
                // Profile 1 is the primary: its own addons either way, so its flag stays as it was here.
                usesPrimaryAddons = if (r.profileIndex == DesktopProfile.PRIMARY_ID) mine?.usesPrimaryAddons ?: true else r.usesPrimaryAddons,
                pinHash = mine?.pinHash,
                limits = mine?.limits ?: ProfileLimits(),
                avatarId = r.avatarId,
                avatarUrl = r.avatarUrl?.takeIf { it.isNotBlank() },
                // Still cleared while the account has no avatar either; one set elsewhere since shows.
                avatarCleared = mine?.avatarCleared == true && r.avatarId == null && r.avatarUrl.isNullOrBlank(),
                profileBackgroundId = r.profileBackgroundId,
                profileBackgroundUrl = r.profileBackgroundUrl?.takeIf { it.isNotBlank() },
            )
        }.toMutableList()
        if (merged.none { it.isPrimary }) {
            val primary = localById[DesktopProfile.PRIMARY_ID] ?: defaultPrimary()
            merged.add(0, primary)
        }
        val keptIds = merged.mapTo(HashSet()) { it.id }
        return ProfileMerge(merged, null, local.filter { it.id !in keptIds })
    }

    /**
     * This computer's profiles as the Fetch website takes them. An avatar or background
     * left alone here is carried over from the account's copy of each profile, so a push doesn't
     * clear one set on another device; one picked or removed here replaces it. Profile 1 is sent
     * with `uses_primary_addons` false, as on TV.
     */
    fun profilesToPush(local: List<DesktopProfile>, remote: List<RemoteProfile>): List<RemoteProfile> {
        val remoteById = remote.associateBy { it.profileIndex }
        return local.sortedBy { it.id }.distinctBy { it.id }.map { p ->
            val theirs = remoteById[p.id]
            RemoteProfile(
                profileIndex = p.id,
                name = p.name,
                avatarColorHex = p.colorHex,
                usesPrimaryAddons = !p.isPrimary && p.usesPrimaryAddons,
                // An avatar picked here replaces the account's picture; one removed here clears it;
                // with none picked here, theirs is kept.
                avatarId = if (p.avatarCleared) null else p.avatarId ?: theirs?.avatarId,
                avatarUrl = when {
                    p.avatarCleared -> null
                    p.avatarId != null && p.avatarId != theirs?.avatarId -> null
                    else -> theirs?.avatarUrl
                },
                profileBackgroundId = if (p.backgroundEdited) p.profileBackgroundId else theirs?.profileBackgroundId,
                profileBackgroundUrl = if (p.backgroundEdited) p.profileBackgroundUrl else theirs?.profileBackgroundUrl,
            )
        }
    }

    private const val MANIFEST_SUFFIX = "/manifest.json"

    private fun defaultPrimary(): DesktopProfile = ProfilesFile().profiles.first()
}
