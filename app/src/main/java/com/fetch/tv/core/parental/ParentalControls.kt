package com.fetch.tv.core.parental

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tracking.LocalList
import com.fetch.tv.data.local.KidsRules
import com.fetch.tv.data.local.LocalListsDataStore
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.local.ProfileTitleLists
import com.fetch.tv.data.website.FetchWebsiteSyncService
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parental restrictions for the active profile: what it may see and which PIN guards its
 * settings. Restricted profiles are guarded by the primary (parent) profile's PIN.
 */
private const val GATE_UNLOCK_WINDOW_MS = 5 * 60_000L

/** What filters a profile's lists: its limit, its title lists, and how many ratings are known. */
data class ParentalFilterRevision(
    val restriction: ContentRestriction,
    val titleLists: ProfileTitleLists,
    val certifications: Long,
    val approvedTitlesOnly: Boolean = false
)

/** How a parent set one title for a profile. */
enum class ParentalTitleState {
    /** The profile's rating limit decides. */
    BY_RATING,
    HIDDEN,
    /** Shown whatever its rating. */
    ALLOWED
}

@Singleton
class ParentalControls @Inject constructor(
    private val profileManager: ProfileManager,
    private val restrictionsDataStore: ProfileRestrictionsDataStore,
    private val pinManager: ProfilePinManager,
    private val resolver: CertificationResolver,
    private val websiteSync: dagger.Lazy<FetchWebsiteSyncService>,
    private val localLists: LocalListsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val allRestrictions: StateFlow<Map<Int, ProfileRestrictions>> = restrictionsDataStore.restrictions
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val activeRestrictions: StateFlow<ProfileRestrictions> = combine(
        profileManager.activeProfileId,
        allRestrictions
    ) { profileId, all -> all[profileId] ?: ProfileRestrictions.None }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ProfileRestrictions.None)

    val allTitleLists: StateFlow<Map<Int, ProfileTitleLists>> = restrictionsDataStore.titleLists
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val activeTitleLists: StateFlow<ProfileTitleLists> = combine(
        profileManager.activeProfileId,
        allTitleLists
    ) { profileId, all -> all[profileId] ?: ProfileTitleLists.Empty }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ProfileTitleLists.Empty)

    val allKidsRules: StateFlow<Map<Int, KidsRules>> = restrictionsDataStore.kidsRules
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The active profile's kids rules, or null when it isn't a kids profile. */
    val activeKidsRules: StateFlow<KidsRules?> = combine(
        profileManager.activeProfileId,
        activeRestrictions,
        allKidsRules
    ) { profileId, restrictions, all ->
        if (restrictions.isKidsProfile) all[profileId] ?: KidsRules.Default else null
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun kidsRulesFor(profileId: Int): KidsRules = allKidsRules.value[profileId] ?: KidsRules.Default

    /** The parent (primary) profile's own lists, which a kids profile can take approved titles from. */
    val parentLists: StateFlow<List<LocalList>> = localLists.listsFor(PARENT_PROFILE_ID)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The active profile's title lists as filtering sees them: in "approved titles only" mode the
     * allowed list also holds the titles on the parent's chosen lists, so a show the parent adds
     * to one of them appears for the kid. Otherwise the same as [activeTitleLists].
     */
    val activeFilterTitleLists: StateFlow<ProfileTitleLists> = combine(
        activeTitleLists,
        activeKidsRules,
        parentLists
    ) { lists, rules, parent -> lists.withApprovedFromLists(approvedTitlesFromLists(rules, parent)) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ProfileTitleLists.Empty)

    /** Saves a kids profile's rules; callers ask for the parent PIN first. */
    suspend fun setKidsRules(profileId: Int, rules: KidsRules): Boolean {
        if (profileId == 1) return false
        if (rules == kidsRulesFor(profileId)) return true
        restrictionsDataStore.setKidsRules(profileId, rules)
        return true
    }

    val lockUnrestrictedProfiles: StateFlow<Boolean> = restrictionsDataStore.lockUnrestrictedProfiles
        .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun setLockUnrestrictedProfiles(enabled: Boolean) = restrictionsDataStore.setLockUnrestrictedProfiles(enabled)

    /**
     * What decides whether opening a profile from the picker needs the main profile's PIN (the
     * lock is on, some profile has limits, and this one has neither limits nor a PIN of its
     * own; see [ProfileOpenLock.needsParentPinToOpen]), straight from disk. Unlike the StateFlows here it has no
     * not-yet-loaded default, so startup can wait for it before resuming the last profile.
     */
    val profileOpenLock: Flow<ProfileOpenLock> = combine(
        restrictionsDataStore.lockUnrestrictedProfiles,
        restrictionsDataStore.restrictions,
        pinManager.pinEnabled
    ) { lockOn, restrictions, pinEnabled -> ProfileOpenLock(lockOn, restrictions, pinEnabled) }
        .distinctUntilChanged()

    /** Episodes that may autoplay in a row on the active profile; null for no cap. */
    fun autoplayEpisodeCap(): Int? = activeKidsRules.value?.autoplayEpisodeCap

    private val approvedTitlesOnly: Boolean
        get() = activeKidsRules.value?.approvedTitlesOnly == true

    private val pinStates: StateFlow<Map<Int, Boolean>> = pinManager.pinEnabled
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** True when the main (parent) profile has a PIN. */
    val parentPinSet: StateFlow<Boolean> = pinStates.map { it[1] == true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Changes whenever filtering results may differ: the active limit or title lists changed,
     * or new certifications arrived. List pipelines recompute on it.
     */
    val filterRevision: StateFlow<ParentalFilterRevision> = combine(
        activeRestrictions.map { it.contentRestriction }.distinctUntilChanged(),
        activeFilterTitleLists,
        resolver.revision,
        activeKidsRules.map { it?.approvedTitlesOnly == true }.distinctUntilChanged()
    ) { restriction, lists, revision, approvedOnly ->
        ParentalFilterRevision(restriction, lists, if (restriction.isRestricted) revision else 0L, approvedOnly)
    }
        .distinctUntilChanged()
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            ParentalFilterRevision(ContentRestriction.Unrestricted, ProfileTitleLists.Empty, 0L)
        )

    /** True when some titles may be left out for the active profile: it has a limit or hidden titles. */
    fun filtersContent(): Boolean =
        activeRestrictions.value.isRestricted || activeFilterTitleLists.value.hidden.isNotEmpty() || approvedTitlesOnly

    fun restrictionsFor(profileId: Int): ProfileRestrictions =
        allRestrictions.value[profileId] ?: ProfileRestrictions.None

    /**
     * The primary profile is the parent profile and can't be restricted. Callers ask for the
     * parent PIN first.
     */
    suspend fun setRestrictions(profileId: Int, restrictions: ProfileRestrictions): Boolean {
        if (profileId == 1) return false
        // A TV paired with a Fetch website shares it with the website straight away.
        restrictionsDataStore.setRestrictions(profileId, restrictions)
        websiteSync.get().triggerProfilesPush()
        return true
    }

    fun titleState(profileId: Int, contentId: String, imdbId: String?, type: String?): ParentalTitleState {
        val lists = allTitleLists.value[profileId] ?: return ParentalTitleState.BY_RATING
        return when {
            lists.isHidden(contentId, imdbId, type) -> ParentalTitleState.HIDDEN
            lists.isAllowed(contentId, imdbId, type) -> ParentalTitleState.ALLOWED
            else -> ParentalTitleState.BY_RATING
        }
    }

    /**
     * Hides [title] from the profile, allows it whatever its rating, or leaves it to the rating
     * limit again. Kept on this TV only and never changed by a sync pull. The
     * primary profile is the parent's and has no lists.
     */
    suspend fun setTitleState(profileId: Int, title: ParentalTitle, state: ParentalTitleState): Boolean {
        if (profileId == 1) return false
        restrictionsDataStore.updateTitleLists(profileId) { lists ->
            when (state) {
                ParentalTitleState.BY_RATING -> lists.without(title)
                ParentalTitleState.HIDDEN -> lists.withHidden(title)
                ParentalTitleState.ALLOWED -> lists.withAllowed(title)
            }
        }
        return true
    }

    /**
     * The parent profile whose PIN must be entered before hiding a title from the active
     * profile, even one with no limits. Null on the primary profile or when it has no PIN.
     */
    fun parentPinProfileId(): Int? {
        if (profileManager.activeProfileId.value == 1) return null
        return 1.takeIf { pinStates.value[1] == true }
    }

    /**
     * Profile whose PIN must be entered before settings, addon changes, phone management or
     * leaving the active profile. Null when no PIN is needed.
     */
    fun guardPinProfileId(): Int? {
        if (!activeRestrictions.value.isRestricted) return null
        return 1.takeIf { pinStates.value[1] == true }
    }

    @Volatile
    private var gateUnlockedUntilMs = 0L

    @Volatile
    private var gateUnlockedForProfileId: Int? = null

    @Volatile
    private var gateHeldOpen = false

    /**
     * True when the parent PIN was entered recently in this profile, so it isn't asked again.
     * While a guarded screen is open (see [holdGateOpen]) the unlock doesn't time out.
     */
    fun isGateUnlocked(nowMs: Long = System.currentTimeMillis()): Boolean =
        gateUnlockedForProfileId == profileManager.activeProfileId.value &&
            (gateHeldOpen || nowMs < gateUnlockedUntilMs)

    /**
     * Keeps an unlocked gate open past [GATE_UNLOCK_WINDOW_MS] while [held] (a guarded screen
     * is on display). It never unlocks a locked gate: [lockGate] still locks it.
     */
    fun holdGateOpen(held: Boolean) {
        gateHeldOpen = held
    }

    fun markGateUnlocked(nowMs: Long = System.currentTimeMillis()) {
        gateUnlockedForProfileId = profileManager.activeProfileId.value
        gateUnlockedUntilMs = nowMs + GATE_UNLOCK_WINDOW_MS
    }

    private val _gateLocks = MutableStateFlow(0)

    /**
     * Goes up each time an open gate locks again, so guarded screens on display ask for the
     * PIN again instead of staying open.
     */
    val gateLocks: StateFlow<Int> = _gateLocks.asStateFlow()

    fun lockGate() {
        val wasUnlocked = gateUnlockedForProfileId != null
        gateUnlockedForProfileId = null
        gateUnlockedUntilMs = 0L
        if (wasUnlocked) _gateLocks.update { it + 1 }
    }

    /** True when an action needs the parent PIN right now. */
    fun needsGate(): Boolean = guardPinProfileId() != null && !isGateUnlocked()

    /**
     * True when the active profile may see the title. A hidden title never shows and an allowed
     * one always does; otherwise the rating decides, and unknown titles are hidden until resolved.
     */
    fun isAllowed(query: CertificationQuery): Boolean {
        val lists = activeFilterTitleLists.value
        val type = query.type.takeIf { it != ContentType.UNKNOWN }?.toApiString()
        if (lists.isHidden(query.contentId, query.imdbId, type)) return false
        // "Approved titles only": the parent's list decides, whatever the rating.
        if (approvedTitlesOnly) return lists.isAllowed(query.contentId, query.imdbId, type)
        val restriction = activeRestrictions.value.contentRestriction
        if (!restriction.isRestricted || lists.isAllowed(query.contentId, query.imdbId, type)) return true
        val resolved = resolver.levelIfKnown(query) ?: return false
        return restriction.allows(resolved.level)
    }

    fun isAllowed(item: MetaPreview): Boolean = isAllowed(item.certificationQuery())

    fun <T> filterAllowed(items: List<T>, query: (T) -> CertificationQuery): List<T> {
        if (!filtersContent()) return items
        return items.filter { isAllowed(query(it)) }
    }

    fun filterPreviews(items: List<MetaPreview>): List<MetaPreview> {
        if (!filtersContent()) return items
        val filtered = items.filter(::isAllowed)
        return if (filtered.size == items.size) items else filtered
    }

    fun filterRow(row: CatalogRow): CatalogRow {
        if (!filtersContent()) return row
        val filtered = row.items.filter(::isAllowed)
        return if (filtered.size == row.items.size) row else row.copy(items = filtered)
    }

    /** Suspending check that waits for the lookup; used before auto-play. */
    suspend fun isAllowedResolved(query: CertificationQuery): Boolean {
        val lists = activeFilterTitleLists.value
        val type = query.type.takeIf { it != ContentType.UNKNOWN }?.toApiString()
        if (lists.isHidden(query.contentId, query.imdbId, type)) return false
        if (approvedTitlesOnly) return lists.isAllowed(query.contentId, query.imdbId, type)
        val restriction = activeRestrictions.value.contentRestriction
        if (!restriction.isRestricted || lists.isAllowed(query.contentId, query.imdbId, type)) return true
        return restriction.allows(resolver.resolve(query))
    }
}

/**
 * Titles on the parent's lists that [rules] approve: those on each list in
 * [KidsRules.approvedListIds], oldest addition first. None outside "approved titles only" mode.
 */
internal fun approvedTitlesFromLists(rules: KidsRules?, parentLists: List<LocalList>): List<ParentalTitle> {
    if (rules == null || !rules.approvedTitlesOnly || rules.approvedListIds.isEmpty()) return emptyList()
    val chosen = rules.approvedListIds.toSet()
    return parentLists
        .filter { it.id in chosen }
        .flatMap { it.items }
        .filter { it.id.isNotBlank() }
        .sortedBy { it.addedAt }
        .distinctBy { it.id to it.type.trim().lowercase() }
        .map { item ->
            ParentalTitle(
                ids = listOf(item.id),
                type = item.type,
                name = item.name,
                poster = item.poster,
                background = item.background
            )
        }
}

/** These lists with [titles] added to the allowed list, skipping any already on either list. */
internal fun ProfileTitleLists.withApprovedFromLists(titles: List<ParentalTitle>): ProfileTitleLists {
    if (titles.isEmpty()) return this
    val listed = hidden + allowed
    val added = titles.filterNot { title ->
        listed.any { it.sameKind(title.type) && it.ids.any { id -> id in title.ids } }
    }
    return if (added.isEmpty()) this else copy(allowed = allowed + added)
}

fun MetaPreview.certificationQuery(): CertificationQuery = CertificationQuery(
    contentId = id,
    type = type,
    imdbId = imdbId,
    knownCertification = ageRating
)

/**
 * Whether the profile remembered at startup needs the parent PIN, or null while any input is
 * still unread. Each input is a value that is null until loaded, so readiness and value can't
 * arrive separately: a lock decision is never made against a placeholder profile list or id.
 */
fun startupNeedsParentPin(lock: ProfileOpenLock?, activeProfileId: Int?, profileIds: List<Int>?): Boolean? {
    if (lock == null || activeProfileId == null || profileIds == null) return null
    return lock.needsParentPinToOpen(activeProfileId, profileIds)
}

/**
 * Whether startup shows the profile picker. Uses the loaded profile count only (null until read),
 * never the primary-only placeholder, and always routes a profile that needs its own PIN or the
 * parent PIN through the PIN-gated picker.
 */
fun shouldShowStartupPicker(
    selectedThisSession: Boolean,
    loadedProfileCount: Int?,
    activeProfileHasPin: Boolean,
    needsParentPin: Boolean?
): Boolean = !selectedThisSession &&
    ((loadedProfileCount ?: 0) > 1 || activeProfileHasPin || needsParentPin == true)

/** What the profile picker does when a profile is chosen. */
enum class PickerOpenGate { OWN_PIN, PARENT_PIN, OPEN }

/**
 * How the profile picker opens [profileId], or null while the PIN states, lock setting, limits
 * or profile list are still unread. The picker ignores a selection until this is non-null, so a
 * PIN-locked profile can't be opened against the empty not-yet-loaded defaults.
 */
fun pickerOpenGate(lock: ProfileOpenLock?, profileId: Int, profileIds: List<Int>?): PickerOpenGate? {
    if (lock == null || profileIds == null) return null
    return when {
        lock.pinEnabled[profileId] == true -> PickerOpenGate.OWN_PIN
        lock.needsParentPinToOpen(profileId, profileIds) -> PickerOpenGate.PARENT_PIN
        else -> PickerOpenGate.OPEN
    }
}

/** The lock setting, limits and PIN states that decide [needsParentPinToOpen]. */
data class ProfileOpenLock(
    val lockOn: Boolean,
    val restrictions: Map<Int, ProfileRestrictions>,
    val pinEnabled: Map<Int, Boolean>
) {
    fun needsParentPinToOpen(profileId: Int, profileIds: List<Int>): Boolean = needsParentPinToOpen(
        profileId = profileId,
        profileIds = profileIds,
        lockOn = lockOn,
        restrictions = restrictions,
        pinEnabled = pinEnabled
    )
}

/**
 * True when deleting a profile needs the main profile's PIN: the main profile has one and some
 * profile has limits. Otherwise a kid could delete their own limited profile (and its limits),
 * or a sibling's.
 */
internal fun needsParentPinToDelete(
    profileIds: List<Int>,
    restrictions: Map<Int, ProfileRestrictions>,
    parentHasPin: Boolean
): Boolean = parentHasPin && profileIds.any { restrictions[it]?.isRestricted == true }

/** See [ProfileOpenLock.needsParentPinToOpen]. */
internal fun needsParentPinToOpen(
    profileId: Int,
    profileIds: List<Int>,
    lockOn: Boolean,
    restrictions: Map<Int, ProfileRestrictions>,
    pinEnabled: Map<Int, Boolean>
): Boolean {
    if (!lockOn || profileId == 1 || pinEnabled[1] != true || pinEnabled[profileId] == true) return false
    if (restrictions[profileId]?.isRestricted == true) return false
    return profileIds.any { restrictions[it]?.isRestricted == true }
}
