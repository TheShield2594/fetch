package com.fetch.tv.core.parental

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.website.FetchWebsiteSyncService
import com.fetch.tv.domain.model.CatalogRow
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

@Singleton
class ParentalControls @Inject constructor(
    private val profileManager: ProfileManager,
    private val restrictionsDataStore: ProfileRestrictionsDataStore,
    private val pinManager: ProfilePinManager,
    private val resolver: CertificationResolver,
    private val websiteSync: dagger.Lazy<FetchWebsiteSyncService>
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

    private val pinStates: StateFlow<Map<Int, Boolean>> = pinManager.pinEnabled
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Changes whenever filtering results may differ: the active limit changed or new
     * certifications arrived. List pipelines recompute on it.
     */
    val filterRevision: StateFlow<Pair<ContentRestriction, Long>> = combine(
        activeRestrictions.map { it.contentRestriction }.distinctUntilChanged(),
        resolver.revision
    ) { restriction, revision -> restriction to if (restriction.isRestricted) revision else 0L }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ContentRestriction.Unrestricted to 0L)

    fun restrictionsFor(profileId: Int): ProfileRestrictions =
        allRestrictions.value[profileId] ?: ProfileRestrictions.None

    /** The primary profile is the parent profile and can't be restricted. */
    suspend fun setRestrictions(profileId: Int, restrictions: ProfileRestrictions): Boolean {
        if (profileId == 1) return false
        restrictionsDataStore.setRestrictions(profileId, restrictions)
        // A TV signed in to a Fetch website shares the limit with it straight away, so the
        // website shows it and the next pull doesn't put the old one back.
        websiteSync.get().triggerProfilesPush()
        return true
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

    /** True when the active profile may see the title. Unknown titles are hidden until resolved. */
    fun isAllowed(query: CertificationQuery): Boolean {
        val restriction = activeRestrictions.value.contentRestriction
        if (!restriction.isRestricted) return true
        val resolved = resolver.levelIfKnown(query) ?: return false
        return restriction.allows(resolved.level)
    }

    fun isAllowed(item: MetaPreview): Boolean = isAllowed(item.certificationQuery())

    fun <T> filterAllowed(items: List<T>, query: (T) -> CertificationQuery): List<T> {
        if (!activeRestrictions.value.isRestricted) return items
        return items.filter { isAllowed(query(it)) }
    }

    fun filterRow(row: CatalogRow): CatalogRow {
        if (!activeRestrictions.value.isRestricted) return row
        val filtered = row.items.filter(::isAllowed)
        return if (filtered.size == row.items.size) row else row.copy(items = filtered)
    }

    /** Suspending check that waits for the lookup; used before auto-play. */
    suspend fun isAllowedResolved(query: CertificationQuery): Boolean {
        val restriction = activeRestrictions.value.contentRestriction
        if (!restriction.isRestricted) return true
        return restriction.allows(resolver.resolve(query))
    }
}

fun MetaPreview.certificationQuery(): CertificationQuery = CertificationQuery(
    contentId = id,
    type = type,
    imdbId = imdbId,
    knownCertification = ageRating
)
