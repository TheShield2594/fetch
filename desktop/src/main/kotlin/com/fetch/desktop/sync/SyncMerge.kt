package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.ProfileLimits
import com.fetch.desktop.data.ProfilesFile
import com.fetch.desktop.data.ProgressEntry
import com.fetch.tv.core.parental.CertificationLevel
import kotlinx.serialization.Serializable

/** What this profile's library held after its last sync, so deletes can be told from additions. */
@Serializable
data class SyncBase(val libraryIds: List<String> = emptyList())

data class LibraryMerge(
    val local: List<LibraryEntry>,
    val push: List<LibraryEntry>,
    val deleteRemote: List<LibraryEntry>,
    val base: SyncBase
)

/**
 * The result of [SyncMerge.addons]: [local] is the ordered list of manifest base URLs to keep on
 * this computer, and [push] the list to send with [SyncClient.pushAddons], or null when nothing
 * needs sending.
 */
data class AddonMerge(
    val local: List<String>,
    val push: List<RemoteAddon>?,
)

/**
 * The result of [SyncMerge.profiles]: [local] is the profile list to keep (its PINs and limits are
 * this computer's own), [push] the list for [SyncClient.pushProfiles] or null, and [removedLocal]
 * the profiles here the account no longer has, whose folders can be cleared.
 */
data class ProfileMerge(
    val local: List<DesktopProfile>,
    val push: List<RemoteProfile>?,
    val removedLocal: List<DesktopProfile>,
)

/**
 * Two-way merges for the sync server. The library is a three-way merge against [SyncBase]: a
 * title gone from one side since the last sync was deleted there, so it goes from the other side
 * too; anything new on either side is copied across.
 */
object SyncMerge {

    fun library(local: List<LibraryEntry>, remote: List<RemoteLibraryItem>, base: SyncBase): LibraryMerge {
        val baseIds = base.libraryIds.toSet()
        val localIds = local.mapTo(HashSet()) { it.id }
        val remoteById = remote.associateBy { it.id }
        val deletedHere = baseIds - localIds
        val deletedThere = baseIds - remoteById.keys
        val kept = local.filter { it.id !in deletedThere }
        val added = remote.filter { it.id !in localIds && it.id !in deletedHere }.map { it.toEntry() }
        val merged = kept + added
        return LibraryMerge(
            local = merged,
            push = kept.filter { it.id !in remoteById },
            deleteRemote = deletedHere.mapNotNull { id -> remoteById[id]?.toEntry() },
            base = SyncBase(merged.map { it.id })
        )
    }

    /** Watched videos both ways: Cinemeta ids, "tt1" for a movie, "tt1:2:5" for an episode. */
    fun watchedToPush(localIds: Collection<String>, remote: List<RemoteWatchedItem>, now: Long): List<RemoteWatchedItem> {
        val remoteIds = remote.mapTo(HashSet()) { it.videoId() }
        return localIds.filter { it !in remoteIds }.mapNotNull { watchedItem(it, now) }
    }

    fun RemoteWatchedItem.videoId(): String =
        if (season != null && episode != null) "$contentId:$season:$episode" else contentId

    fun watchedItem(videoId: String, now: Long): RemoteWatchedItem? {
        val parts = videoId.split(':')
        val id = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        val isEpisode = season != null && episode != null
        return RemoteWatchedItem(id, if (isEpisode) "series" else "movie", season = season, episode = episode, watchedAtEpochMs = now)
    }

    /** Local positions worth sharing: real positions from this app, not other services' guesses. */
    fun progressToPush(local: List<ProgressEntry>): List<RemoteProgress> = local
        .filter { it.source == null && !it.upNext && it.positionMs > 0 && it.durationMs > 0 }
        .map { RemoteProgress(it.contentId, it.contentType, it.videoId, it.season, it.episode, it.positionMs, it.durationMs, it.lastWatched) }

    /** Remote positions as Continue Watching entries; names come from [known] or are filled in later. */
    fun progressEntries(remote: List<RemoteProgress>, known: Map<String, ProgressEntry>): List<ProgressEntry> =
        remote.filter { it.positionMs > 0 && it.durationMs > 0 }
            .groupBy { it.contentId }
            .map { (_, items) -> items.maxBy { it.lastWatchedEpochMs } }
            .map { p ->
                val seen = known[p.contentId]
                val imdb = p.contentId.takeIf { it.startsWith("tt") }
                ProgressEntry(
                    contentId = p.contentId,
                    contentType = p.contentType,
                    name = seen?.name ?: p.contentId,
                    poster = seen?.poster ?: imdb?.let { "https://images.metahub.space/poster/small/$it/img" },
                    backdrop = seen?.backdrop ?: imdb?.let { "https://images.metahub.space/background/medium/$it/img" },
                    logo = seen?.logo ?: imdb?.let { "https://images.metahub.space/logo/medium/$it/img" },
                    videoId = p.videoId,
                    season = p.season,
                    episode = p.episode,
                    episodeTitle = seen?.takeIf { it.videoId == p.videoId }?.episodeTitle,
                    positionMs = p.positionMs,
                    durationMs = p.durationMs,
                    lastWatched = p.lastWatchedEpochMs
                )
            }

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
     * (`reconcileWithRemoteAddonUrls(removeMissingLocal = true)`): the account's list, in its order,
     * replaces the local one; an addon in both keeps this computer's spelling of its URL. An empty
     * account list never wipes the local one: it is kept and nothing is pushed, as on TV.
     *
     * With [preferLocal] (the TV app's sign-up: this device's data goes up first) the local list is
     * kept and pushed instead.
     *
     * Addons disabled on TV aren't installed here, since this app has no disabled state; pushes
     * keep them, disabled, in their place (see [addonsToPush]).
     */
    fun addons(local: List<String>, remote: List<RemoteAddon>, preferLocal: Boolean = false): AddonMerge {
        val localCanonical = local.map(::canonicalAddonUrl).filter { it.isNotBlank() }.distinctBy(::addonKey)
        if (preferLocal) return AddonMerge(localCanonical, addonsToPush(localCanonical, remote))
        val remoteOrdered = remote.sortedBy { it.order }
            .filter { canonicalAddonUrl(it.url).isNotBlank() }
            .distinctBy { addonKey(it.url) }
        if (remoteOrdered.isEmpty()) return AddonMerge(localCanonical, null)
        val localByKey = localCanonical.associateBy(::addonKey)
        val merged = remoteOrdered.filter { it.enabled }.map { localByKey[addonKey(it.url)] ?: canonicalAddonUrl(it.url) }
        return AddonMerge(merged, null)
    }

    /**
     * This computer's addon list as [SyncClient.pushAddons] takes it, in local order. Names the
     * user set on TV are kept, and the account's disabled addons that aren't installed here stay
     * in the list, disabled, after the addon they followed there.
     */
    fun addonsToPush(local: List<String>, remote: List<RemoteAddon>): List<RemoteAddon> {
        val remoteOrdered = remote.sortedBy { it.order }.distinctBy { addonKey(it.url) }
        val remoteByKey = remoteOrdered.associateBy { addonKey(it.url) }
        val result = local.map(::canonicalAddonUrl).filter { it.isNotBlank() }.distinctBy(::addonKey)
            .map { url -> RemoteAddon(url, 0, enabled = true, name = remoteByKey[addonKey(url)]?.name) }
            .toMutableList()
        var previousKey: String? = null
        remoteOrdered.forEach { addon ->
            val key = addonKey(addon.url)
            if (!addon.enabled && result.none { addonKey(it.url) == key }) {
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
     * This computer's profiles as [SyncClient.pushProfiles] takes them. An avatar or background
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

    /**
     * Profiles locked on the account that have no PIN here yet. As on TV, such a PIN is checked
     * with [SyncClient.verifyProfilePin]; once it is right, hash it with ProfilePinHasher and keep it.
     */
    fun lockedOnlyRemotely(local: List<DesktopProfile>, locks: List<RemoteProfileLock>): List<Int> {
        val locked = locks.filter { it.pinEnabled }.mapTo(HashSet()) { it.profileIndex }
        return local.filter { it.id in locked && it.pinHash == null }.map { it.id }
    }

    // --- Parental limits ---

    /**
     * This computer's limits for a profile, tightened by the account's, as the TV app applies them
     * (`ProfileRestrictions.tightenedWith`): the stricter rating limit, and unrated titles only when
     * every limit allows them. Never looser than either side. Daily minutes, bedtime and the
     * autoplay cap aren't synced, so they stay as they are. Profile 1's are never synced.
     */
    fun parentalLimits(local: ProfileLimits, remote: RemoteParentalLimits?): ProfileLimits {
        if (remote == null) return local
        val localLevel = local.maxRating?.let { CertificationLevel.fromName(it) ?: CertificationLevel.ALL_AGES }
        val remoteLevel = remote.maxRating?.let { CertificationLevel.fromName(it) ?: CertificationLevel.ALL_AGES }
        val level = listOfNotNull(localLevel, remoteLevel).minByOrNull { it.rank }
        val localAllows = localLevel == null || local.allowUnrated
        val remoteAllows = remoteLevel == null || remote.allowUnrated
        return local.copy(maxRating = level?.name, allowUnrated = level == null || (localAllows && remoteAllows), kidsProfile = remote.isKidsProfile)
    }

    /**
     * A profile's limits as [SyncClient.pushParentalLimits] takes them, with its kids-profile switch.
     */
    fun parentalLimitsToPush(local: ProfileLimits, remote: RemoteParentalLimits?): RemoteParentalLimits {
        val level = local.maxRating?.let { CertificationLevel.fromName(it) ?: CertificationLevel.ALL_AGES }
        return RemoteParentalLimits(
            isKidsProfile = local.kidsProfile,
            maxRating = level?.name,
            allowUnrated = level == null || local.allowUnrated,
        )
    }

    private const val MANIFEST_SUFFIX = "/manifest.json"

    private fun defaultPrimary(): DesktopProfile = ProfilesFile().profiles.first()

    private fun RemoteLibraryItem.toEntry() = LibraryEntry(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterShape = posterShape.lowercase(),
        background = background,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        addedAt = addedAtEpochMs
    )

    fun LibraryEntry.toRemote() = RemoteLibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterShape = posterShape.uppercase(),
        background = background,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        addedAtEpochMs = addedAt
    )
}
