package com.fetch.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

/**
 * Extra rules for a kids profile, set by a parent next to its limits. They apply only while the
 * profile is a kids profile. Shared with the account like the limits: rules from another TV only
 * ever tighten the ones here ([tightenedWith]), and loosening is done on each TV behind the parent PIN.
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
     * profile's allowed list, in "approved titles only" mode. Kept on this TV: list ids are local.
     */
    val approvedListIds: List<String> = emptyList()
) {
    val hasBedtime: Boolean get() = bedtimeStartMinute != null && bedtimeEndMinute != null

    /**
     * The stricter of this and [other], rule by rule: the lower autoplay cap, the shorter daily
     * allowance, a bedtime covering both, and "approved titles only" or "stop right away" when
     * either has it. Never looser than either. [approvedListIds] stay this TV's own.
     */
    fun tightenedWith(other: KidsRules): KidsRules {
        val (bedStart, bedEnd) = coveringBedtime(this, other)
        return KidsRules(
            autoplayEpisodeCap = listOfNotNull(autoplayEpisodeCap, other.autoplayEpisodeCap).minOrNull(),
            approvedTitlesOnly = approvedTitlesOnly || other.approvedTitlesOnly,
            dailyMinutes = listOfNotNull(dailyMinutes, other.dailyMinutes).minOrNull(),
            bedtimeStartMinute = bedStart,
            bedtimeEndMinute = bedEnd,
            hardStop = hardStop || other.hardStop,
            approvedListIds = approvedListIds
        )
    }

    /** Only the rules another TV can tighten: [approvedListIds] name lists on this TV. */
    fun forAccount(): KidsRules = copy(approvedListIds = emptyList())

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

private const val MINUTES_PER_DAY = 24 * 60

/**
 * The shortest bedtime window that covers both [a]'s and [b]'s, or the one that exists. Two
 * windows that overlap or touch merge into one; otherwise the gap between them is filled in,
 * on whichever side keeps the window shortest, so neither TV's bedtime is cut short.
 */
internal fun coveringBedtime(a: KidsRules, b: KidsRules): Pair<Int?, Int?> {
    val windows = listOf(a, b).filter { it.hasBedtime && it.bedtimeStartMinute != it.bedtimeEndMinute }
    when (windows.size) {
        0 -> return null to null
        1 -> return windows[0].bedtimeStartMinute to windows[0].bedtimeEndMinute
    }
    if (windows[0].bedtimeStartMinute == windows[1].bedtimeStartMinute &&
        windows[0].bedtimeEndMinute == windows[1].bedtimeEndMinute
    ) {
        return windows[0].bedtimeStartMinute to windows[0].bedtimeEndMinute
    }
    val covered = BooleanArray(MINUTES_PER_DAY)
    windows.forEach { rules ->
        for (minute in 0 until MINUTES_PER_DAY) if (rules.isBedtime(minute)) covered[minute] = true
    }
    // The bedtime is everything outside the longest stretch both TVs allow watching.
    var bestGapStart = -1
    var bestGapLength = 0
    for (start in 0 until MINUTES_PER_DAY) {
        if (covered[start] || !covered[(start + MINUTES_PER_DAY - 1) % MINUTES_PER_DAY]) continue
        var length = 0
        while (length < MINUTES_PER_DAY && !covered[(start + length) % MINUTES_PER_DAY]) length++
        if (length > bestGapLength) {
            bestGapLength = length
            bestGapStart = start
        }
    }
    if (bestGapStart < 0) {
        // Bedtime all day: the closest a start/end window gets is one minute short of it.
        return 0 to MINUTES_PER_DAY - 1
    }
    val bedtimeStart = (bestGapStart + bestGapLength) % MINUTES_PER_DAY
    return bedtimeStart to bestGapStart
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
    private val kidsRulesKey = stringPreferencesKey("kids_rules")
    // The account's kids rules per profile, like the limits' account copy; pushes use the same pending set.
    private val accountKidsRulesKey = stringPreferencesKey("account_kids_rules")
    private val lockUnrestrictedKey = booleanPreferencesKey("lock_unrestricted_profiles")
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

    /**
     * A parent set the profile's kids rules on this TV: they become this TV's rules and the
     * account's copy (without the TV's own list choices), waiting to be pushed.
     */
    suspend fun setKidsRulesByParent(profileId: Int, rules: KidsRules) {
        setKidsRules(profileId, rules)
        dataStore.edit { prefs ->
            val current = decodeKidsRules(prefs[accountKidsRulesKey]).toMutableMap()
            current[profileId] = rules.forAccount()
            prefs[accountKidsRulesKey] = encodeKidsRules(current)
            prefs[pendingAccountPushKey] = (decodePending(prefs) + profileId).mapTo(HashSet()) { it.toString() }
        }
    }

    val accountKidsRules: Flow<Map<Int, KidsRules>> = dataStore.data.map { prefs ->
        decodeKidsRules(prefs[accountKidsRulesKey])
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
    suspend fun accountPushSucceeded(profileId: Int, pushed: ProfileRestrictions, pushedKidsRules: KidsRules? = null) {
        dataStore.edit { prefs ->
            val accountCopies = decode(prefs[accountRestrictionsKey])
            val accountKidsCopies = decodeKidsRules(prefs[accountKidsRulesKey])
            val pending = decodePending(prefs)
            val kidsCopy = accountKidsCopies[profileId]
            val limitsCopy = accountCopies[profileId]
            if (profileId in pending) {
                // A copy changed again while this one was being sent still needs its own push.
                // A missing limits copy (only the kids rules changed here) matches what was sent.
                if (limitsCopy != null && limitsCopy != pushed) return@edit
                if (kidsCopy != null && kidsCopy != pushedKidsRules) return@edit
                prefs[pendingAccountPushKey] = (pending - profileId).mapTo(HashSet()) { it.toString() }
            }
            if (limitsCopy != pushed) prefs[accountRestrictionsKey] = encode(accountCopies + (profileId to pushed))
            if (pushedKidsRules != null && kidsCopy != pushedKidsRules) {
                prefs[accountKidsRulesKey] = encodeKidsRules(accountKidsCopies + (profileId to pushedKidsRules))
            }
        }
    }

    /**
     * Applies limits pulled from the account: records them as the account's copy and tightens
     * this TV's limits to at least as strict. Returns true when this TV's limits changed. A
     * profile with a pending push is left alone: this TV's change is the newer one.
     */
    suspend fun tightenFromAccount(
        profileId: Int,
        account: ProfileRestrictions,
        accountKidsRules: KidsRules? = null
    ): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            if (profileId in decodePending(prefs)) return@edit
            val accountCopies = decode(prefs[accountRestrictionsKey]).toMutableMap()
            accountCopies[profileId] = account
            prefs[accountRestrictionsKey] = encode(accountCopies)

            if (accountKidsRules != null) {
                val accountKidsCopies = decodeKidsRules(prefs[accountKidsRulesKey]).toMutableMap()
                accountKidsCopies[profileId] = accountKidsRules.forAccount()
                prefs[accountKidsRulesKey] = encodeKidsRules(accountKidsCopies)

                val localRules = decodeKidsRules(prefs[kidsRulesKey]).toMutableMap()
                val local = localRules[profileId] ?: KidsRules.Default
                val tightenedRules = local.tightenedWith(accountKidsRules)
                if (tightenedRules != local) {
                    if (tightenedRules == KidsRules.Default) localRules.remove(profileId) else localRules[profileId] = tightenedRules
                    prefs[kidsRulesKey] = encodeKidsRules(localRules)
                    changed = true
                }
            }

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
        }
    }

    private fun encodeKidsRules(map: Map<Int, KidsRules>): String =
        json.encodeToString(map.mapKeys { it.key.toString() })

    /** Drops the profile's limits. Its title lists stay: they're kept on this TV whatever the website says. */
    suspend fun removeProfile(profileId: Int) = setRestrictions(profileId, ProfileRestrictions.None)

    /** For a deleted profile: its limits, the account's copy of them and its title lists. */
    suspend fun deleteProfile(profileId: Int) {
        removeProfile(profileId)
        dataStore.edit { prefs ->
            val accountCopies = decode(prefs[accountRestrictionsKey])
            if (profileId in accountCopies) prefs[accountRestrictionsKey] = encode(accountCopies - profileId)
            val accountKidsCopies = decodeKidsRules(prefs[accountKidsRulesKey])
            if (profileId in accountKidsCopies) prefs[accountKidsRulesKey] = encodeKidsRules(accountKidsCopies - profileId)
            val pending = decodePending(prefs)
            if (profileId in pending) prefs[pendingAccountPushKey] = (pending - profileId).mapTo(HashSet()) { it.toString() }
        }
        updateTitleLists(profileId) { ProfileTitleLists.Empty }
        setKidsRules(profileId, KidsRules.Default)
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
