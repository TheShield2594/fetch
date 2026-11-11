package com.fetch.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
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

/**
 * Set when the file was unreadable and replaced. Every profile but the main one then reads as a
 * kids profile until a parent sets its limits again; see [ProfileRestrictionsDataStore.restrictions].
 */
private val restrictionsCorruptionResetKey = booleanPreferencesKey("corruption_reset")

private val Context.profileRestrictionsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_restrictions",
    // Fail closed: a corrupt file must not read as "no limits on any profile".
    corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(restrictionsCorruptionResetKey to true) }
)

/** The main profile; it belongs to the parent and is never restricted. */
private const val MAIN_PROFILE_ID = 1

/**
 * Limits read after the file was lost to corruption: a profile with nothing stored gets
 * [ProfileRestrictions.KidsPreset] rather than no limits, except the main profile. Only lookups
 * by id see the fallback; iteration sees what is stored. Equality is by identity, so a reader
 * using distinctUntilChanged notices when the fallback goes away.
 */
private class FailClosedRestrictions(
    private val stored: Map<Int, ProfileRestrictions>
) : Map<Int, ProfileRestrictions> by stored {
    override fun get(key: Int): ProfileRestrictions? =
        stored[key] ?: ProfileRestrictions.KidsPreset.takeIf { key != MAIN_PROFILE_ID }

    override fun containsKey(key: Int): Boolean = get(key) != null
}

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

/**
 * Extra rules for a kids profile, set by a parent next to its limits. They apply only while the
 * profile is a kids profile.
 */
@Serializable
data class KidsRules(
    /** Episodes that autoplay in a row before a button press is needed; null for no cap. */
    val autoplayEpisodeCap: Int? = DEFAULT_AUTOPLAY_EPISODE_CAP,
    /** Only titles on the allowed list show, whatever their rating. */
    val approvedTitlesOnly: Boolean = false,
    /** Playback allowed per day, in minutes; null for no limit. */
    val dailyMinutes: Int? = null,
    /** Start of the no-watching window, in minutes after midnight; null for no bedtime. */
    val bedtimeStartMinute: Int? = null,
    /** End of the no-watching window, in minutes after midnight. */
    val bedtimeEndMinute: Int? = null,
    /** At the limit, stop right away instead of letting the episode or movie finish. */
    val hardStop: Boolean = false,
    /**
     * Ids of the parent profile's own lists whose titles count as approved too, next to the
     * profile's allowed list, in "approved titles only" mode.
     */
    val approvedListIds: List<String> = emptyList()
) {
    val hasBedtime: Boolean get() = bedtimeStartMinute != null && bedtimeEndMinute != null

    /** True when [minuteOfDay] falls in the bedtime window, which may run past midnight. */
    fun isBedtime(minuteOfDay: Int): Boolean {
        val start = bedtimeStartMinute ?: return false
        val end = bedtimeEndMinute ?: return false
        return when {
            start == end -> false
            start < end -> minuteOfDay in start until end
            else -> minuteOfDay >= start || minuteOfDay < end
        }
    }

    companion object {
        const val DEFAULT_AUTOPLAY_EPISODE_CAP = 3
        val Default = KidsRules()
    }
}

/** A title on a profile's hidden or allowed list, with every id it's known by. */
@Serializable
data class ParentalTitle(
    val ids: List<String>,
    val type: String,
    val name: String,
    /** Artwork kept so an approved title can be shown on a kids Home without a lookup. */
    val poster: String? = null,
    val background: String? = null
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
 * Per-profile parental restrictions, title lists and kids rules, kept on the device. A paired
 * Fetch website carries the limits with the profiles; see FetchWebsiteSyncService.
 */
@Singleton
class ProfileRestrictionsDataStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.profileRestrictionsDataStore)

    private val restrictionsKey = stringPreferencesKey("restrictions_map")
    // Kept apart from the limits, which a website pull replaces; the lists are only set on this TV.
    private val titleListsKey = stringPreferencesKey("title_lists")
    private val kidsRulesKey = stringPreferencesKey("kids_rules")
    private val lockUnrestrictedKey = booleanPreferencesKey("lock_unrestricted_profiles")
    private val corruptionResetKey = restrictionsCorruptionResetKey
    // The account's copy of each profile's limits and kids rules, and the profiles waiting to send
    // them, kept by installs from before the website carried limits. Never read; dropped on the next write.
    private val legacyAccountKeys = listOf("account_restrictions_map", "account_push_pending", "account_kids_rules")
        .map { stringPreferencesKey(it) }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Limits per profile. After the file was lost to corruption, a profile other than the main
     * one with nothing stored reads as [ProfileRestrictions.KidsPreset] (fail closed) until a
     * parent or the account sets its limits; see [FailClosedRestrictions].
     */
    val restrictions: Flow<Map<Int, ProfileRestrictions>> = dataStore.data.map { prefs ->
        val stored = decode(prefs[restrictionsKey])
        if (prefs[corruptionResetKey] == true) FailClosedRestrictions(stored) else stored
    }

    /**
     * True when parental controls are in use on this TV: some profile has limits, the
     * unrestricted-profile lock is on, or the limits were lost to corruption.
     */
    val parentalControlsConfigured: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[corruptionResetKey] == true ||
            prefs[lockUnrestrictedKey] == true ||
            decode(prefs[restrictionsKey]).values.any { it.isRestricted }
    }

    suspend fun setRestrictions(profileId: Int, restrictions: ProfileRestrictions) {
        dataStore.edit { prefs ->
            val current = decode(prefs[restrictionsKey]).toMutableMap()
            // After corruption a missing entry reads as the kids preset, so "no limits" has to be
            // stored explicitly for it to stick.
            val keepNone = prefs[corruptionResetKey] == true
            if (restrictions == ProfileRestrictions.None && !keepNone) current.remove(profileId) else current[profileId] = restrictions
            prefs[restrictionsKey] = encode(current)
            dropLegacyKeys(prefs)
        }
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

    /**
     * While a profile has limits, opening a profile with no limits and no PIN of its own needs
     * the main profile's PIN. One setting for the TV.
     */
    val lockUnrestrictedProfiles: Flow<Boolean> = dataStore.data.map { prefs -> prefs[lockUnrestrictedKey] == true }

    suspend fun setLockUnrestrictedProfiles(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[lockUnrestrictedKey] = enabled }
    }

    /** Rules per kids profile; a profile missing here uses [KidsRules.Default]. */
    val kidsRules: Flow<Map<Int, KidsRules>> = dataStore.data.map { prefs ->
        decodeKidsRules(prefs[kidsRulesKey])
    }

    suspend fun setKidsRules(profileId: Int, rules: KidsRules) {
        dataStore.edit { prefs ->
            val current = decodeKidsRules(prefs[kidsRulesKey]).toMutableMap()
            if (rules == KidsRules.Default) current.remove(profileId) else current[profileId] = rules
            prefs[kidsRulesKey] = encodeKidsRules(current)
            dropLegacyKeys(prefs)
        }
    }

    private fun encodeKidsRules(map: Map<Int, KidsRules>): String =
        json.encodeToString(map.mapKeys { it.key.toString() })

    /** Drops the profile's limits. Its title lists stay: they're kept on this TV whatever the website says. */
    suspend fun removeProfile(profileId: Int) {
        dataStore.edit { prefs ->
            val current = decode(prefs[restrictionsKey])
            if (profileId in current) prefs[restrictionsKey] = encode(current - profileId)
        }
    }

    /** For a deleted profile: its limits, title lists and kids rules. */
    suspend fun deleteProfile(profileId: Int) {
        removeProfile(profileId)
        updateTitleLists(profileId) { ProfileTitleLists.Empty }
        setKidsRules(profileId, KidsRules.Default)
    }

    suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    // Keys match by name whatever their type, so this also drops the string set.
    private fun dropLegacyKeys(prefs: MutablePreferences) {
        legacyAccountKeys.forEach { prefs.remove(it) }
    }

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

    private fun decodeKidsRules(raw: String?): Map<Int, KidsRules> {
        if (raw.isNullOrBlank()) return emptyMap()
        val stored = runCatching { json.decodeFromString<Map<String, KidsRules>>(raw) }.getOrNull()
            ?: return emptyMap()
        return stored.mapNotNull { (id, rules) -> id.toIntOrNull()?.let { it to rules } }.toMap()
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
