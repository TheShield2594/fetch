package com.fetch.tv.core.parental

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.ProfileSettingsSyncService
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val certifications: Long
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
    private val profileSettingsSync: dagger.Lazy<ProfileSettingsSyncService>
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

    private val pinStates: StateFlow<Map<Int, Boolean>> = pinManager.pinEnabled
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Changes whenever filtering results may differ: the active limit or title lists changed,
     * or new certifications arrived. List pipelines recompute on it.
     */
    val filterRevision: StateFlow<ParentalFilterRevision> = combine(
        activeRestrictions.map { it.contentRestriction }.distinctUntilChanged(),
        activeTitleLists,
        resolver.revision
    ) { restriction, lists, revision ->
        ParentalFilterRevision(restriction, lists, if (restriction.isRestricted) revision else 0L)
    }
        .distinctUntilChanged()
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            ParentalFilterRevision(ContentRestriction.Unrestricted, ProfileTitleLists.Empty, 0L)
        )

    /** True when some titles may be left out for the active profile: it has a limit or hidden titles. */
    fun filtersContent(): Boolean =
        activeRestrictions.value.isRestricted || activeTitleLists.value.hidden.isNotEmpty()

    fun restrictionsFor(profileId: Int): ProfileRestrictions =
        allRestrictions.value[profileId] ?: ProfileRestrictions.None

    /**
     * The primary profile is the parent profile and can't be restricted. Callers ask for the
     * parent PIN first: this is the only way limits get looser on a TV, since a sync pull only
     * ever tightens them.
     */
    suspend fun setRestrictions(profileId: Int, restrictions: ProfileRestrictions): Boolean {
        if (profileId == 1) return false
        restrictionsDataStore.setRestrictions(profileId, restrictions)
        // Shared with the account straight away, so other TVs pick it up (only if it's stricter
        // than theirs) and the next pull doesn't put the old one back. A TV signed in to a Fetch
        // website shares it with the website instead.
        restrictionsDataStore.setAccountRestrictions(profileId, restrictions)
        profileSettingsSync.get().triggerParentalLimitsPush(profileId)
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
     * limit again. Kept on this TV only, like the limits, and never changed by a sync pull. The
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

    /** True when the parent PIN was entered recently in this profile, so it isn't asked again. */
    fun isGateUnlocked(nowMs: Long = System.currentTimeMillis()): Boolean =
        gateUnlockedForProfileId == profileManager.activeProfileId.value && nowMs < gateUnlockedUntilMs

    fun markGateUnlocked(nowMs: Long = System.currentTimeMillis()) {
        gateUnlockedForProfileId = profileManager.activeProfileId.value
        gateUnlockedUntilMs = nowMs + GATE_UNLOCK_WINDOW_MS
    }

    fun lockGate() {
        gateUnlockedForProfileId = null
        gateUnlockedUntilMs = 0L
    }

    /** True when an action needs the parent PIN right now. */
    fun needsGate(): Boolean = guardPinProfileId() != null && !isGateUnlocked()

    /**
     * True when the active profile may see the title. A hidden title never shows and an allowed
     * one always does; otherwise the rating decides, and unknown titles are hidden until resolved.
     */
    fun isAllowed(query: CertificationQuery): Boolean {
        val lists = activeTitleLists.value
        val type = query.type.takeIf { it != ContentType.UNKNOWN }?.toApiString()
        if (lists.isHidden(query.contentId, query.imdbId, type)) return false
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

    fun filterRow(row: CatalogRow): CatalogRow {
        if (!filtersContent()) return row
        val filtered = row.items.filter(::isAllowed)
        return if (filtered.size == row.items.size) row else row.copy(items = filtered)
    }

    /** Suspending check that waits for the lookup; used before auto-play. */
    suspend fun isAllowedResolved(query: CertificationQuery): Boolean {
        val lists = activeTitleLists.value
        val type = query.type.takeIf { it != ContentType.UNKNOWN }?.toApiString()
        if (lists.isHidden(query.contentId, query.imdbId, type)) return false
        val restriction = activeRestrictions.value.contentRestriction
        if (!restriction.isRestricted || lists.isAllowed(query.contentId, query.imdbId, type)) return true
        return restriction.allows(resolver.resolve(query))
    }
}

fun MetaPreview.certificationQuery(): CertificationQuery = CertificationQuery(
    contentId = id,
    type = type,
    imdbId = imdbId,
    knownCertification = ageRating
)
