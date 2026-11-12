package com.fetch.desktop.website

import com.fetch.desktop.AppInfo
import com.fetch.desktop.addons.SyncCatalogItem
import com.fetch.desktop.addons.SyncHomeCatalogPayload
import com.fetch.desktop.collections.CollectionsRemote
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.ProfileLimits
import com.fetch.desktop.sync.RemoteAddon
import com.fetch.desktop.sync.RemoteProfile
import com.fetch.desktop.sync.RemoteSharedSettings
import com.fetch.desktop.sync.SyncMerge
import com.fetch.desktop.sync.SyncSettingsBlob
import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.domain.model.PROFILE_AVATAR_COLORS
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A profile's addon list on the website; [everSet] is false when nobody has set one there yet. */
data class WebsiteAddonList(val addons: List<RemoteAddon>, val everSet: Boolean)

/**
 * The Fetch website as the sync transport, the way the TV's `FetchWebsiteSyncService` syncs while
 * paired: profiles (with their parental settings and which are locked), each profile's addons,
 * settings blob, Home layout and collections. Everything comes and goes in the TV's sync shapes ([RemoteProfile],
 * [RemoteAddon], [RemoteSharedSettings], [SyncHomeCatalogPayload]), so the app decides what to
 * apply and what to send with the same [SyncMerge] rules for both.
 *
 * A PIN-protected profile nobody has unlocked here has nothing to read: its calls answer null and
 * its pushes are skipped, as on TV.
 */
class FetchWebsiteSync(private val website: FetchWebsiteState) {
    private val client: FetchWebsiteClient get() = website.client

    /** Whether the website still takes this computer's token; a revoked one ends the session. */
    suspend fun checkSession(): Boolean = website.isPaired && client.checkSession()

    // --- Profiles ---

    /** The website's profiles in this computer's slots, read afresh. */
    suspend fun profiles(): List<WebsiteProfile> = client.profiles().also { website.profiles = it }

    /**
     * Sends [local] to the website, creating the slots it doesn't have yet. Never a PIN.
     *
     * [actingSlot] is the profile in use on this computer. The website judges a new profile, and a
     * lifted limit, by who is asking, as its own pages do, so each request names that profile; it
     * goes first so the ones after it can name it even when it is new itself. A profile the website
     * won't add from here (a kids profile adding one) doesn't stop the rest going, but the push then
     * fails with that refusal, so a caller doesn't take the profile for saved and drop it.
     */
    suspend fun pushProfiles(local: List<DesktopProfile>, actingSlot: Int? = null) {
        val remote = website.profiles.ifEmpty { profiles() }
        val limits = local.associate { it.id to it.limits }
        var known = remote
        val saved = mutableListOf<WebsiteProfile>()
        var refused: FetchWebsiteException? = null
        SyncMerge.profilesToPush(local, remote.map { FetchWebsiteMappings.toRemoteProfile(it, null) })
            .sortedByDescending { it.profileIndex == actingSlot }
            .forEach { profile ->
                val target = known.firstOrNull { it.slot == profile.profileIndex }
                val acting = known.firstOrNull { it.slot == actingSlot }
                // Who is asking, and separately the token that unlocks a PIN-protected profile.
                val actingToken = acting?.let { website.profileToken(it.id) }
                val targetToken = target?.takeIf { it.hasPin }?.let { website.profileToken(it.id) }
                val body = FetchWebsiteMappings.toWebsiteBody(profile, limits[profile.profileIndex] ?: ProfileLimits())
                try {
                    val put = client.putProfile(profile.profileIndex, body, acting?.id, actingToken, targetToken)
                    saved += put
                    known = known.filter { it.slot != put.slot } + put
                } catch (e: FetchWebsiteException) {
                    if (!e.needsParentalAuthority) throw e
                    refused = refused ?: e
                }
            }
        website.profiles = (remote.filter { r -> saved.none { it.slot == r.slot } } + saved).sortedBy { it.slot }
        refused?.let { throw it }
    }

    /** Whether this is the first sync since pairing, which adds this computer's profiles to the account. */
    val needsInitialProfilePush: Boolean get() = website.file.initialProfilePush

    fun markInitialProfilePushDone() = website.markInitialProfilePushDone()

    /** Deletes the website profile in [slot], with everything it holds there. */
    suspend fun deleteProfile(slot: Int) {
        val remote = website.profileFor(slot) ?: return
        client.deleteProfile(remote.id, website.profileToken(remote.id))
        website.profiles = website.profiles.filter { it.slot != slot }
    }

    /**
     * Sets (or with null, removes) the PIN of the website profile in [slot]. False when the website
     * wants the old PIN first, which only the website's own pages ask for.
     */
    suspend fun setPin(slot: Int, pin: String?): Boolean {
        val remote = website.profileFor(slot) ?: return true
        try {
            client.setPin(remote.id, pin, null, website.profileToken(remote.id))
        } catch (e: FetchWebsiteException) {
            if (e.status == 401) return false
            throw e
        }
        website.profiles = website.profiles.map { if (it.slot == slot) it.copy(hasPin = pin != null) else it }
        return true
    }

    // --- Addons ---

    /** The addons of the website profile in [slot]; null when there is none, or it is locked. */
    suspend fun addons(slot: Int): WebsiteAddonList? {
        val remote = website.profileFor(slot) ?: return null
        val body = unlessLocked { client.addons(remote.id, website.profileToken(remote.id)) } ?: return null
        return WebsiteAddonList(FetchWebsiteMappings.toRemoteAddons(body.addons), everSet = body.updatedAt != null || body.addons.isNotEmpty())
    }

    /** Replaces the website profile's addon list with [addons], in their order. */
    suspend fun pushAddons(slot: Int, addons: List<RemoteAddon>) {
        val remote = website.profileFor(slot) ?: return
        client.putAddons(remote.id, website.profileToken(remote.id), FetchWebsiteMappings.toWebsiteAddons(addons))
    }

    // --- Settings ---

    /** The settings blob of the website profile in [slot]; null when there is none, or it is locked. */
    suspend fun settings(slot: Int): WebsiteTvSettings? {
        val remote = website.profileFor(slot) ?: return null
        return unlessLocked { client.tvSettings(remote.id, website.profileToken(remote.id)) }
    }

    /**
     * Writes [settings]' non-null fields into the profile's blob on the website, keeping every
     * other key (the TV's own and the website's). Read just before, so the version sent is
     * current; a save that still races another is tried once more on the newer blob.
     */
    suspend fun pushSharedSettings(slot: Int, settings: RemoteSharedSettings) {
        val remote = website.profileFor(slot) ?: return
        val token = website.profileToken(remote.id)
        repeat(2) { attempt ->
            val current = unlessLocked { client.tvSettings(remote.id, token) } ?: return
            try {
                client.putTvSettings(remote.id, token, SyncSettingsBlob.withSharedSettings(current.settings, settings), current.version)
                return
            } catch (e: FetchWebsiteException) {
                if (e.status != 412 || attempt == 1) throw e
            }
        }
    }

    // --- Home layout ---

    /**
     * The website's Home row order as the TV's payload, built over [local] so rows keep their
     * titles from here. Null when the profile is locked, or the website has never had a layout for
     * it, in which case it is given [local].
     */
    suspend fun pullHomeLayout(slot: Int, local: SyncHomeCatalogPayload, installedAddonIds: Set<String>): SyncHomeCatalogPayload? {
        val remote = website.profileFor(slot) ?: return null
        val layout = unlessLocked { client.homeLayout(remote.id, website.profileToken(remote.id)) } ?: return null
        if (layout.version == 0) {
            if (local.items.isNotEmpty()) pushHomeLayout(slot, local, installedAddonIds)
            return null
        }
        return FetchWebsiteMappings.fromWebsiteRows(layout.rows, local)
    }

    /**
     * Saves this computer's order, keeping the rows only the website places. Read just before, so
     * the version sent is current; a save that still races another is tried once more.
     */
    suspend fun pushHomeLayout(slot: Int, payload: SyncHomeCatalogPayload, installedAddonIds: Set<String>) {
        val remote = website.profileFor(slot) ?: return
        val token = website.profileToken(remote.id)
        repeat(2) { attempt ->
            val current = unlessLocked { client.homeLayout(remote.id, token) } ?: return
            try {
                client.putHomeLayout(remote.id, token, FetchWebsiteMappings.toWebsiteRows(payload, current.rows, installedAddonIds), current.version)
                return
            } catch (e: FetchWebsiteException) {
                if (e.status != 412 || attempt == 1) throw e
            }
        }
    }

    // --- Collections ---

    /** The collections of the website profile in [slot], for [com.fetch.desktop.collections.CollectionsState]. */
    fun collections(slot: Int): CollectionsRemote = object : CollectionsRemote {
        override suspend fun pull(): JsonArray? {
            // No website profile in this slot: nothing there, and nowhere for a push to go.
            val remote = website.profileFor(slot) ?: return null
            val (collections, updatedAt) = try {
                client.collections(remote.id, website.profileToken(remote.id))
            } catch (e: FetchWebsiteException) {
                if (e.isProfileLocked) throw WebsiteProfileLockedException(slot, remote.name)
                throw e
            }
            return collections.takeIf { updatedAt != null }
        }

        override suspend fun push(collections: JsonArray) {
            val remote = website.profileFor(slot) ?: return
            client.putCollections(remote.id, website.profileToken(remote.id), collections)
        }
    }

    // --- Report ---

    /** Tells the website how the sync went, for its overview. Best effort. */
    suspend fun report(error: Throwable?) {
        try {
            client.reportSync(AppInfo.version, FLAVOR, ok = error == null, error = error?.let { it.message ?: it.javaClass.simpleName })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private inline fun <T> unlessLocked(block: () -> T): T? = try {
        block()
    } catch (e: FetchWebsiteException) {
        if (e.isProfileLocked) null else throw e
    }

    companion object {
        /** What the website's overview lists this build as, next to the TV's flavours. */
        const val FLAVOR = "desktop"
    }
}

/**
 * Translations between the website's profiles, addons and Home rows and the TV's sync shapes
 * the desktop already merges, ported from the TV's `FetchWebsiteMappings` and
 * `FetchWebsiteTvMappings`. Pure, so the rules that decide what survives a sync are testable.
 */
object FetchWebsiteMappings {

    // --- Profiles ---

    /** A website profile in the TV's sync shape, in the slot the website gave this computer. */
    fun toRemoteProfile(remote: WebsiteProfile, local: DesktopProfile?): RemoteProfile = RemoteProfile(
        profileIndex = remote.slot,
        name = remote.name,
        avatarColorHex = remote.avatarColorHex
            ?: local?.colorHex
            ?: PROFILE_AVATAR_COLORS[(remote.slot - 1).mod(PROFILE_AVATAR_COLORS.size)],
        usesPrimaryAddons = remote.usesPrimaryAddons,
        avatarId = remote.avatarId,
        // The website only knows avatar ids.
        avatarUrl = null,
        profileBackgroundId = remote.profileBackgroundId,
        profileBackgroundUrl = local?.profileBackgroundUrl?.takeIf { remote.profileBackgroundId == local.profileBackgroundId }
    )

    /**
     * [local] with the website's profiles merged in by [SyncMerge.profiles]. The website is where
     * PINs and parental limits are set while paired: a profile it locks asks for its PIN online
     * until it has been right once here, the copy kept for offline unlocks goes when the website
     * says the profile has no PIN, and the website's limits replace the ones here, as on TV.
     */
    fun mergeProfiles(local: List<DesktopProfile>, remote: List<WebsiteProfile>): List<DesktopProfile> {
        if (remote.isEmpty()) return local
        val localById = local.associateBy { it.id }
        val merged = SyncMerge.profiles(local, remote.map { toRemoteProfile(it, localById[it.slot]) }).local
        val bySlot = remote.associateBy { it.slot }
        return merged.map { profile ->
            val theirs = bySlot[profile.id] ?: return@map profile
            val pinHash = if (theirs.hasPin) profile.pinHash else null
            profile.copy(pinHash = pinHash, serverLocked = theirs.hasPin && pinHash == null, limits = limits(profile.limits, theirs.parental))
        }
    }

    /**
     * The website's parental settings over [local], which keeps what the website doesn't hold
     * (daily minutes, bedtime, the autoplay cap). A rating this build doesn't know reads as the
     * strictest, so an unreadable limit can't loosen anything.
     */
    fun limits(local: ProfileLimits, parental: WebsiteParental): ProfileLimits {
        val level = parental.maxRating?.let { CertificationLevel.fromName(it) ?: CertificationLevel.ALL_AGES }
        return local.copy(maxRating = level?.name, allowUnrated = level == null || parental.allowUnrated, kidsProfile = parental.isKidsProfile)
    }

    /** What is sent for a profile edited here (the TV's `toWebsiteBody`). Never a PIN: the website checks those. */
    fun toWebsiteBody(profile: RemoteProfile, limits: ProfileLimits): JsonObject = buildJsonObject {
        put("name", profile.name)
        put("avatarColorHex", profile.avatarColorHex.takeIf { COLOR.matches(it) })
        // Only ids the website can draw; another catalog's avatar means nothing there.
        put("avatarId", profile.avatarId?.takeIf { it.startsWith(com.fetch.desktop.data.BundledAvatars.ID_PREFIX) })
        put("profileBackgroundId", profile.profileBackgroundId?.takeIf { ASSET_ID.matches(it) })
        if (profile.profileIndex != DesktopProfile.PRIMARY_ID) put("usesPrimaryAddons", profile.usesPrimaryAddons)
        val level = limits.maxRating?.let { CertificationLevel.fromName(it) ?: CertificationLevel.ALL_AGES }
        put(
            "parental",
            buildJsonObject {
                put("isKidsProfile", limits.kidsProfile)
                put("maxRating", level?.name)
                put("allowUnrated", level == null || limits.allowUnrated)
            }
        )
    }

    private val COLOR = Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")
    private val ASSET_ID = Regex("^[A-Za-z0-9:_.-]{1,100}$")

    // --- Addons ---

    private const val MANIFEST_SUFFIX = "/manifest.json"

    /** The website's list in the TV's sync shape, with base URLs as this computer keeps them. */
    fun toRemoteAddons(addons: List<WebsiteAddon>): List<RemoteAddon> = addons.mapIndexed { index, addon ->
        RemoteAddon(SyncMerge.canonicalAddonUrl(addon.manifestUrl), index, addon.enabled, addon.name?.takeIf { it.isNotBlank() })
    }

    fun toWebsiteAddons(addons: List<RemoteAddon>): List<WebsiteAddon> = addons.sortedBy { it.order }.map { addon ->
        WebsiteAddon(toManifestUrl(addon.url), addon.enabled, addon.name?.takeIf { it.isNotBlank() })
    }

    /**
     * The website wants the manifest address; this computer keeps the addon's base URL. The query,
     * which some addons use for their settings, stays after the manifest path.
     */
    fun toManifestUrl(url: String): String {
        val trimmed = url.trim()
        val queryStart = trimmed.indexOf('?')
        val path = (if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed).trimEnd('/')
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val withManifest = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) path else path + MANIFEST_SUFFIX
        return withManifest + query
    }

    // --- Home layout ---

    private const val ADDON_PREFIX = "addon:"
    private const val COLLECTION_PREFIX = "collection:"

    /** `encodeURIComponent`, so a key built here is the same string the website builds. */
    fun encodeUriComponent(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) {
                out.append(ch)
            } else {
                out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
            }
        }
        return out.toString()
    }

    fun rowKey(item: SyncCatalogItem): String = if (item.isCollection) {
        COLLECTION_PREFIX + encodeUriComponent(item.collectionId)
    } else {
        ADDON_PREFIX + listOf(item.addonId, item.type, item.catalogId).joinToString(":") { encodeUriComponent(it) }
    }

    /** Rows the apps have their own entry for. Everything else on the website is the website's to place. */
    private fun isAppRow(key: String): Boolean = key.startsWith(ADDON_PREFIX) || key.startsWith(COLLECTION_PREFIX)

    /**
     * The layout to send: this computer's rows in its order, with the rows only the website knows
     * (its lists, Continue Watching) kept where they were. An addon row the payload leaves out is
     * kept too while that addon is still in [installedAddonIds], so switching an addon off doesn't
     * throw away where its rows sat, as on TV.
     */
    fun toWebsiteRows(payload: SyncHomeCatalogPayload, remote: List<WebsiteHomeRow>, installedAddonIds: Set<String>): List<WebsiteHomeRow> {
        val ours = payload.items.sortedBy { it.order }.map { item ->
            WebsiteHomeRow(key = rowKey(item), hidden = !item.enabled, title = item.customTitle.takeIf { it.isNotBlank() })
        }
        val sentKeys = ours.mapTo(HashSet()) { it.key }
        val result = ours.toMutableList()
        remote.forEachIndexed { index, row ->
            val keep = !isAppRow(row.key) ||
                (row.key !in sentKeys && addonIdOf(row.key)?.let { it in installedAddonIds } == true)
            if (keep) result.add(index.coerceAtMost(result.size), row.copy(source = null))
        }
        return result
    }

    /** The addon id an `addon:` row key names, decoded; null for any other row. */
    private fun addonIdOf(key: String): String? {
        if (!key.startsWith(ADDON_PREFIX)) return null
        val parts = key.removePrefix(ADDON_PREFIX).split(":")
        return if (parts.size == 3) decode(parts[0]) else null
    }

    /**
     * The website's layout as the TV's payload. Only addon catalogs and collections become items;
     * a renamed row keeps the name it was given in the apps, which the website doesn't edit.
     */
    fun fromWebsiteRows(rows: List<WebsiteHomeRow>, local: SyncHomeCatalogPayload): SyncHomeCatalogPayload {
        val localByKey = local.items.associateBy { rowKey(it) }
        val items = rows.filter { isAppRow(it.key) }.mapNotNull { row -> itemFor(row, localByKey[row.key]) }
            .mapIndexed { index, item -> item.copy(order = index) }
        return local.copy(items = items)
    }

    private fun itemFor(row: WebsiteHomeRow, local: SyncCatalogItem?): SyncCatalogItem? {
        val enabled = !row.hidden
        if (local != null) return local.copy(enabled = enabled)
        if (row.key.startsWith(COLLECTION_PREFIX)) {
            val id = decode(row.key.removePrefix(COLLECTION_PREFIX)) ?: return null
            return SyncCatalogItem(addonId = "", type = "", catalogId = "", enabled = enabled, isCollection = true, collectionId = id)
        }
        val parts = row.key.removePrefix(ADDON_PREFIX).split(":")
        if (parts.size != 3) return null
        val (addonId, type, catalogId) = parts.map { decode(it) ?: return null }
        return SyncCatalogItem(addonId = addonId, type = type, catalogId = catalogId, enabled = enabled)
    }

    private fun decode(part: String): String? = runCatching {
        java.net.URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
