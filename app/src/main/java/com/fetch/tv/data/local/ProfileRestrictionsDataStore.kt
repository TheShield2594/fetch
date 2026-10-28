package com.fetch.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.core.parental.ContentRestriction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileRestrictionsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_restrictions",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/** Parental settings for one profile. */
data class ProfileRestrictions(
    val isKidsProfile: Boolean = false,
    val maxLevel: CertificationLevel? = null,
    val allowUnrated: Boolean = true
) {
    val contentRestriction: ContentRestriction
        get() = ContentRestriction(maxLevel = maxLevel, allowUnrated = allowUnrated)

    /** A restricted profile can't manage addons and needs the parent PIN for settings. */
    val isRestricted: Boolean get() = isKidsProfile || maxLevel != null

    /**
     * The stricter of this and [other], setting by setting: kids if either is, the lower rating
     * limit, and unrated titles only when every limit allows them. Never looser than either.
     */
    fun tightenedWith(other: ProfileRestrictions): ProfileRestrictions {
        val level = listOfNotNull(maxLevel, other.maxLevel).minByOrNull { it.rank }
        return ProfileRestrictions(
            isKidsProfile = isKidsProfile || other.isKidsProfile,
            maxLevel = level,
            // Without a limit the flag means nothing; keep the default so "no limit" stays None.
            allowUnrated = level == null || (allowsUnratedUnderLimit && other.allowsUnratedUnderLimit)
        )
    }

    private val allowsUnratedUnderLimit: Boolean get() = maxLevel == null || allowUnrated

    companion object {
        val None = ProfileRestrictions()

        /** Preset applied when a profile is marked as a kids profile. */
        val KidsPreset = ProfileRestrictions(
            isKidsProfile = true,
            maxLevel = CertificationLevel.PARENTAL_GUIDANCE,
            allowUnrated = false
        )
    }
}

/** A title on a profile's hidden or allowed list, with every id it's known by. */
@Serializable
data class ParentalTitle(
    val ids: List<String>,
    val type: String,
    val name: String
) {
    /**
     * True for [contentId] or [imdbId] naming this title, or one of its episodes ("tt123:1:2").
     * A movie and a series can share an id ("tmdb:123"), so a known [type] must agree too.
     */
    fun matches(contentId: String, imdbId: String?, type: String?): Boolean =
        sameKind(type) && ids.any { id -> id == contentId || id == imdbId || contentId.startsWith("$id:") }

    internal fun sameKind(other: String?): Boolean {
        val mine = titleKind(type) ?: return true
        val theirs = titleKind(other) ?: return true
        return mine == theirs
    }
}

/** Movies apart from series ("series", "tv" and "anime" are one kind); null when unknown. */
private fun titleKind(type: String?): String? = when (type?.trim()?.lowercase()) {
    "movie" -> "movie"
    "series", "tv", "anime" -> "series"
    else -> null
}

/**
 * Titles a parent hid from a profile, and titles allowed on it whatever their rating. A title
 * is on one list at most.
 */
@Serializable
data class ProfileTitleLists(
    val hidden: List<ParentalTitle> = emptyList(),
    val allowed: List<ParentalTitle> = emptyList()
) {
    val isEmpty: Boolean get() = hidden.isEmpty() && allowed.isEmpty()

    fun isHidden(contentId: String, imdbId: String?, type: String?): Boolean =
        hidden.any { it.matches(contentId, imdbId, type) }

    fun isAllowed(contentId: String, imdbId: String?, type: String?): Boolean =
        allowed.any { it.matches(contentId, imdbId, type) }

    fun withHidden(title: ParentalTitle): ProfileTitleLists = without(title).let { it.copy(hidden = it.hidden + title) }

    fun withAllowed(title: ParentalTitle): ProfileTitleLists = without(title).let { it.copy(allowed = it.allowed + title) }

    /** Takes the title off both lists. */
    fun without(title: ParentalTitle): ProfileTitleLists {
        fun List<ParentalTitle>.drop() = filterNot { listed ->
            listed.sameKind(title.type) && listed.ids.any { it in title.ids }
        }
        return ProfileTitleLists(hidden = hidden.drop(), allowed = allowed.drop())
    }

    companion object {
        val Empty = ProfileTitleLists()
    }
}

/**
 * Per-profile parental restrictions. Kept on the device and outside the synced profile
 * list, so a sync pull can never loosen them: limits from the account only ever tighten the
 * ones here ([tightenFromAccount]), and removing one is done on each TV behind the parent PIN.
 *
 * Next to the limits it keeps the account's copy as this TV last saw or sent it, which is what
 * the TV sends back with the profile's settings; a limit removed on one TV doesn't reach the others.
 * A parent's change waits as a pending push until the account has it; until then a pull leaves
 * that profile alone, so it can't put back a limit the parent just removed.
 */
@Singleton
class ProfileRestrictionsDataStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.profileRestrictionsDataStore)

    private val restrictionsKey = stringPreferencesKey("restrictions_map")
    // The account's limits per profile; a profile is missing until this TV has seen or sent them.
    private val accountRestrictionsKey = stringPreferencesKey("account_restrictions_map")
    // Profiles whose account copy was changed here and hasn't reached the account yet.
    private val pendingAccountPushKey = stringSetPreferencesKey("account_push_pending")
    // Kept apart from the limits, which a website pull replaces; the lists are only set on this TV.
    private val titleListsKey = stringPreferencesKey("title_lists")
    private val json = Json { ignoreUnknownKeys = true }

    val restrictions: Flow<Map<Int, ProfileRestrictions>> = dataStore.data.map { prefs ->
        decode(prefs[restrictionsKey])
    }

    suspend fun setRestrictions(profileId: Int, restrictions: ProfileRestrictions) {
        dataStore.edit { prefs ->
            val current = decode(prefs[restrictionsKey]).toMutableMap()
            if (restrictions == ProfileRestrictions.None) current.remove(profileId) else current[profileId] = restrictions
            prefs[restrictionsKey] = encode(current)
        }
    }

    val accountRestrictions: Flow<Map<Int, ProfileRestrictions>> = dataStore.data.map { prefs ->
        decode(prefs[accountRestrictionsKey])
    }

    val pendingAccountPushes: Flow<Set<Int>> = dataStore.data.map { prefs -> decodePending(prefs) }

    /**
     * A parent set the profile's limits on this TV: they become both this TV's limits and the
     * account's copy, waiting to be pushed.
     */
    suspend fun setByParent(profileId: Int, restrictions: ProfileRestrictions) {
        setRestrictions(profileId, restrictions)
        setPendingAccountRestrictions(profileId, restrictions)
    }

    /** Records [restrictions] as the account's copy for the profile, waiting to be pushed. */
    suspend fun setPendingAccountRestrictions(profileId: Int, restrictions: ProfileRestrictions) {
        dataStore.edit { prefs ->
            val current = decode(prefs[accountRestrictionsKey]).toMutableMap()
            current[profileId] = restrictions
            prefs[accountRestrictionsKey] = encode(current)
            prefs[pendingAccountPushKey] = (decodePending(prefs) + profileId).mapTo(HashSet()) { it.toString() }
        }
    }

    /**
     * The account now holds [pushed] for the profile: it becomes the account's copy, and a
     * pending push of it is done. A copy changed again while this one was being sent is left
     * pending, since that change still needs its own push.
     */
    suspend fun accountPushSucceeded(profileId: Int, pushed: ProfileRestrictions) {
        dataStore.edit { prefs ->
            val accountCopies = decode(prefs[accountRestrictionsKey])
            val pending = decodePending(prefs)
            if (profileId in pending) {
                if (accountCopies[profileId] != pushed) return@edit
                prefs[pendingAccountPushKey] = (pending - profileId).mapTo(HashSet()) { it.toString() }
            } else {
                prefs[accountRestrictionsKey] = encode(accountCopies + (profileId to pushed))
            }
        }
    }

    /**
     * Applies limits pulled from the account: records them as the account's copy and tightens
     * this TV's limits to at least as strict. Returns true when this TV's limits changed. A
     * profile with a pending push is left alone: this TV's change is the newer one.
     */
    suspend fun tightenFromAccount(profileId: Int, account: ProfileRestrictions): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            if (profileId in decodePending(prefs)) return@edit
            val accountCopies = decode(prefs[accountRestrictionsKey]).toMutableMap()
            accountCopies[profileId] = account
            prefs[accountRestrictionsKey] = encode(accountCopies)

            val current = decode(prefs[restrictionsKey]).toMutableMap()
            val local = current[profileId] ?: ProfileRestrictions.None
            val tightened = local.tightenedWith(account)
            if (tightened != local) {
                current[profileId] = tightened
                prefs[restrictionsKey] = encode(current)
                changed = true
            }
        }
        return changed
    }

    val titleLists: Flow<Map<Int, ProfileTitleLists>> = dataStore.data.map { prefs ->
        decodeTitleLists(prefs[titleListsKey])
    }

    suspend fun updateTitleLists(profileId: Int, update: (ProfileTitleLists) -> ProfileTitleLists) {
        dataStore.edit { prefs ->
            val current = decodeTitleLists(prefs[titleListsKey]).toMutableMap()
            val updated = update(current[profileId] ?: ProfileTitleLists.Empty)
            if (updated.isEmpty) current.remove(profileId) else current[profileId] = updated
            prefs[titleListsKey] = json.encodeToString(current.mapKeys { it.key.toString() })
        }
    }

    /** Drops the profile's limits. Its title lists stay: they're kept on this TV whatever the website says. */
    suspend fun removeProfile(profileId: Int) = setRestrictions(profileId, ProfileRestrictions.None)

    /** For a deleted profile: its limits, the account's copy of them and its title lists. */
    suspend fun deleteProfile(profileId: Int) {
        removeProfile(profileId)
        dataStore.edit { prefs ->
            val accountCopies = decode(prefs[accountRestrictionsKey])
            if (profileId in accountCopies) prefs[accountRestrictionsKey] = encode(accountCopies - profileId)
            val pending = decodePending(prefs)
            if (profileId in pending) prefs[pendingAccountPushKey] = (pending - profileId).mapTo(HashSet()) { it.toString() }
        }
        updateTitleLists(profileId) { ProfileTitleLists.Empty }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    private fun decodePending(prefs: Preferences): Set<Int> =
        prefs[pendingAccountPushKey].orEmpty().mapNotNullTo(HashSet()) { it.toIntOrNull() }

    // "<id>:<kids>:<level or ->:<allowUnrated>" entries separated by ','.
    private fun encode(map: Map<Int, ProfileRestrictions>): String =
        map.entries.joinToString(separator = ",") { (id, value) ->
            "$id:${value.isKidsProfile}:${value.maxLevel?.name ?: "-"}:${value.allowUnrated}"
        }

    private fun decodeTitleLists(raw: String?): Map<Int, ProfileTitleLists> {
        if (raw.isNullOrBlank()) return emptyMap()
        val stored = runCatching { json.decodeFromString<Map<String, ProfileTitleLists>>(raw) }.getOrNull()
            ?: return emptyMap()
        return stored.mapNotNull { (id, lists) -> id.toIntOrNull()?.let { it to lists } }.toMap()
    }

    private fun decode(raw: String?): Map<Int, ProfileRestrictions> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 4) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val kids = parts[1].toBooleanStrictOrNull() ?: return@mapNotNull null
            val level = if (parts[2] == "-") null else CertificationLevel.fromName(parts[2]) ?: return@mapNotNull null
            val allowUnrated = parts[3].toBooleanStrictOrNull() ?: return@mapNotNull null
            id to ProfileRestrictions(kids, level, allowUnrated)
        }.toMap()
    }
}
