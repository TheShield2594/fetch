package com.fetch.tv.core.parental

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.KidsRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

private val Context.screenTimeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "screen_time",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/** One profile's screen time for one local day. A new day starts from nothing. */
@Serializable
data class ScreenTimeDay(
    /** Local date, as `yyyy-MM-dd`. */
    val date: String,
    val watchedMs: Long = 0L,
    /** Minutes a parent added to today's allowance. */
    val extraMinutes: Int = 0,
    /** Bedtime is lifted until this wall-clock time, after a parent added time during it. */
    val bedtimeLiftedUntilMs: Long = 0L,
    /** A parent lifted every limit for the rest of the day. */
    val unlockedForDay: Boolean = false
)

sealed interface ScreenTimeStatus {
    /** Watching is allowed; [remainingMs] is what's left of today's allowance, if there is one. */
    data class Allowed(val remainingMs: Long?) : ScreenTimeStatus
    data object DailyLimitReached : ScreenTimeStatus
    data object Bedtime : ScreenTimeStatus

    val isBlocked: Boolean get() = this !is Allowed
}

/** Whether a kids profile with [rules] may watch at [now], given [day]'s usage. */
internal fun screenTimeStatus(rules: KidsRules?, day: ScreenTimeDay, now: LocalDateTime, nowMs: Long): ScreenTimeStatus {
    if (rules == null || day.unlockedForDay) return ScreenTimeStatus.Allowed(null)
    if (rules.isBedtime(now.hour * 60 + now.minute) && nowMs >= day.bedtimeLiftedUntilMs) {
        return ScreenTimeStatus.Bedtime
    }
    val daily = rules.dailyMinutes ?: return ScreenTimeStatus.Allowed(null)
    val allowanceMs = (daily + day.extraMinutes) * 60_000L
    val remaining = allowanceMs - day.watchedMs
    return if (remaining > 0) ScreenTimeStatus.Allowed(remaining) else ScreenTimeStatus.DailyLimitReached
}

/** Today's record for a profile, or a fresh one when the stored one is from another day. */
internal fun ScreenTimeDay?.forDate(date: String): ScreenTimeDay =
    this?.takeIf { it.date == date } ?: ScreenTimeDay(date)

/**
 * A kid's day across the account's TVs: [local] plus what each other TV recorded for the same
 * date. Playback adds up, so two TVs playing at once both use the allowance (it's the kid's,
 * not the TV's). Time a parent added on any TV adds up too, and a day unlocked or a bedtime
 * lifted on one TV is on every TV. Records from another date are left out.
 */
internal fun mergeScreenTimeDays(local: ScreenTimeDay, others: Collection<ScreenTimeDay>): ScreenTimeDay {
    val sameDay = others.filter { it.date == local.date }
    if (sameDay.isEmpty()) return local
    return ScreenTimeDay(
        date = local.date,
        watchedMs = local.watchedMs + sameDay.sumOf { it.watchedMs.coerceAtLeast(0L) },
        extraMinutes = local.extraMinutes + sameDay.sumOf { it.extraMinutes.coerceAtLeast(0) },
        bedtimeLiftedUntilMs = maxOf(local.bedtimeLiftedUntilMs, sameDay.maxOf { it.bedtimeLiftedUntilMs }),
        unlockedForDay = local.unlockedForDay || sameDay.any { it.unlockedForDay }
    )
}

@Singleton
class ScreenTimeStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.screenTimeDataStore)

    private val daysKey = stringPreferencesKey("days")
    // Other TVs' records per profile, keyed by their sync client id, as last read from the account.
    private val otherDevicesKey = stringPreferencesKey("other_devices")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun days(): Map<Int, ScreenTimeDay> = decode(dataStore.data.first()[daysKey])

    val daysFlow: Flow<Map<Int, ScreenTimeDay>> = dataStore.data.map { prefs -> decode(prefs[daysKey]) }

    /** For a deleted profile: its own record and other TVs'. A new profile can reuse the id. */
    suspend fun deleteProfile(profileId: Int) {
        dataStore.edit { prefs ->
            val days = decode(prefs[daysKey])
            if (profileId in days) prefs[daysKey] = json.encodeToString((days - profileId).mapKeys { it.key.toString() })
            val others = decodeOtherDevices(prefs[otherDevicesKey])
            if (profileId in others) {
                prefs[otherDevicesKey] = json.encodeToString((others - profileId).mapKeys { it.key.toString() })
            }
        }
    }

    /** After sign-out: nothing from the old account's profiles may carry over. */
    suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    val otherDevices: Flow<Map<Int, Map<String, ScreenTimeDay>>> = dataStore.data.map { prefs ->
        decodeOtherDevices(prefs[otherDevicesKey])
    }

    suspend fun otherDevices(profileId: Int): Map<String, ScreenTimeDay> = otherDevices.first()[profileId].orEmpty()

    /** Replaces what other TVs recorded for the profile with [records], as just read from the account. */
    suspend fun setOtherDevices(profileId: Int, records: Map<String, ScreenTimeDay>) {
        dataStore.edit { prefs ->
            val all = decodeOtherDevices(prefs[otherDevicesKey]).toMutableMap()
            if (all[profileId].orEmpty() == records) return@edit
            if (records.isEmpty()) all.remove(profileId) else all[profileId] = records
            prefs[otherDevicesKey] = json.encodeToString(all.mapKeys { it.key.toString() })
        }
    }

    private fun decodeOtherDevices(raw: String?): Map<Int, Map<String, ScreenTimeDay>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val stored = runCatching { json.decodeFromString<Map<String, Map<String, ScreenTimeDay>>>(raw) }.getOrNull()
            ?: return emptyMap()
        return stored.mapNotNull { (id, records) -> id.toIntOrNull()?.let { it to records } }.toMap()
    }

    suspend fun update(profileId: Int, date: String, change: (ScreenTimeDay) -> ScreenTimeDay) {
        dataStore.edit { prefs ->
            val days = decode(prefs[daysKey]).toMutableMap()
            days[profileId] = change(days[profileId].forDate(date))
            prefs[daysKey] = json.encodeToString(days.mapKeys { it.key.toString() })
        }
    }

    private fun decode(raw: String?): Map<Int, ScreenTimeDay> {
        if (raw.isNullOrBlank()) return emptyMap()
        val stored = runCatching { json.decodeFromString<Map<String, ScreenTimeDay>>(raw) }.getOrNull()
            ?: return emptyMap()
        return stored.mapNotNull { (id, day) -> id.toIntOrNull()?.let { it to day } }.toMap()
    }
}

/**
 * Shares a kids profile's screen time with the account's other TVs. [requestScreenTimePull] reads what
 * they recorded into [ScreenTimeStore.setOtherDevices]; [requestScreenTimePush] sends this TV's record,
 * right away when [immediate] (a parent added time), otherwise no more than every so often.
 */
interface ScreenTimeSharing {
    fun requestScreenTimePull(profileId: Int)
    fun requestScreenTimePush(profileId: Int, immediate: Boolean)

    /** Reads other TVs' screen time now, ignoring the pull interval; returns when it's stored. */
    suspend fun refreshScreenTime(profileId: Int)

    companion object {
        val None = object : ScreenTimeSharing {
            override suspend fun refreshScreenTime(profileId: Int) = Unit
            override fun requestScreenTimePull(profileId: Int) = Unit
            override fun requestScreenTimePush(profileId: Int, immediate: Boolean) = Unit
        }
    }
}

/**
 * Daily screen time and bedtime for kids profiles. Playback time is counted on each TV, shared
 * with the account's other TVs (see [mergeScreenTimeDays]) so the allowance is the kid's rather
 * than each TV's, and resets at local midnight; a parent can add time or lift the limits for the day.
 */
@Singleton
class ScreenTimeManager internal constructor(
    private val parentalControls: ParentalControls,
    private val profileManager: ProfileManager,
    private val store: ScreenTimeStore,
    private val clock: () -> LocalDateTime,
    private val wallClockMs: () -> Long,
    private val sharing: () -> ScreenTimeSharing = { ScreenTimeSharing.None }
) {
    @Inject
    constructor(
        parentalControls: ParentalControls,
        profileManager: ProfileManager,
        store: ScreenTimeStore,
        sharing: dagger.Lazy<com.fetch.tv.core.sync.ProfileSettingsSyncService>
    ) : this(parentalControls, profileManager, store, LocalDateTime::now, System::currentTimeMillis, { sharing.get() })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val days = MutableStateFlow<Map<Int, ScreenTimeDay>>(emptyMap())
    private val otherDevices = MutableStateFlow<Map<Int, Map<String, ScreenTimeDay>>>(emptyMap())
    // Playback counted but not saved yet, per profile; saved every SAVE_INTERVAL_MS.
    private val unsavedMs = mutableMapOf<Int, Long>()
    private var lastSaveMs = 0L

    private val _revision = MutableStateFlow(0)
    /** Goes up when a limit may have changed: time was added or the day was unlocked. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        scope.launch { store.daysFlow.collect { days.value = it } }
        scope.launch { store.otherDevices.collect { otherDevices.value = it } }
    }

    private fun today(): String = clock().toLocalDate().toString()

    private fun dayFor(profileId: Int): ScreenTimeDay {
        val stored = days.value[profileId].forDate(today())
        val unsaved = synchronized(unsavedMs) { unsavedMs[profileId] ?: 0L }
        val local = stored.copy(watchedMs = stored.watchedMs + unsaved)
        return mergeScreenTimeDays(local, otherDevices.value[profileId].orEmpty().values)
    }

    /** Whether the active profile may watch right now. */
    fun status(): ScreenTimeStatus {
        val rules = parentalControls.activeKidsRules.value?.takeIf { it.dailyMinutes != null || it.hasBedtime }
        val profileId = profileManager.activeProfileId.value
        // Catch up on other TVs' playback now and then; the next check sees it.
        if (rules != null) sharing().requestScreenTimePull(profileId)
        return screenTimeStatus(rules, dayFor(profileId), clock(), wallClockMs())
    }

    /**
     * [status] after catching up on other TVs' playback, for the decision to start a title:
     * waits up to [timeoutMs] for the account, then decides from what this TV has.
     */
    suspend fun statusBeforeStart(timeoutMs: Long = START_REFRESH_TIMEOUT_MS): ScreenTimeStatus {
        val rules = parentalControls.activeKidsRules.value
        if (rules != null && (rules.dailyMinutes != null || rules.hasBedtime)) {
            val profileId = profileManager.activeProfileId.value
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { sharing().refreshScreenTime(profileId) }
            // Read back what the refresh stored rather than wait for the collector to see it.
            otherDevices.value = store.otherDevices.first()
        }
        return status()
    }

    /** At the limit, stop right away rather than let the current title finish. */
    fun hardStop(): Boolean = parentalControls.activeKidsRules.value?.hardStop == true

    /** Counts [elapsedMs] of playback against the active profile, if it is a kids profile. */
    fun recordPlayback(elapsedMs: Long) {
        if (elapsedMs <= 0L || parentalControls.activeKidsRules.value == null) return
        val profileId = profileManager.activeProfileId.value
        synchronized(unsavedMs) { unsavedMs[profileId] = (unsavedMs[profileId] ?: 0L) + elapsedMs }
        val now = wallClockMs()
        if (now - lastSaveMs >= SAVE_INTERVAL_MS) {
            lastSaveMs = now
            scope.launch { flush() }
        }
    }

    /** Saves counted playback in the background, outliving the caller's scope. */
    fun save() {
        scope.launch { flush() }
    }

    /** Saves playback counted since the last save. */
    suspend fun flush() = mutex.withLock {
        val pending = synchronized(unsavedMs) { unsavedMs.toMap().also { unsavedMs.clear() } }
        val date = today()
        pending.forEach { (profileId, ms) ->
            store.update(profileId, date) { it.copy(watchedMs = it.watchedMs + ms) }
        }
        days.value = store.days()
        pending.keys.forEach { sharing().requestScreenTimePush(it, immediate = false) }
    }

    /** A parent added [minutes] for the active profile: to today's allowance, and past bedtime. */
    suspend fun grantMinutes(minutes: Int) = change { day ->
        day.copy(
            extraMinutes = day.extraMinutes + minutes,
            bedtimeLiftedUntilMs = maxOf(day.bedtimeLiftedUntilMs, wallClockMs()) + minutes * 60_000L
        )
    }

    /** A parent lifted every limit on the active profile until midnight. */
    suspend fun unlockForToday() = change { it.copy(unlockedForDay = true) }

    private suspend fun change(update: (ScreenTimeDay) -> ScreenTimeDay) {
        flush()
        val profileId = profileManager.activeProfileId.value
        mutex.withLock {
            store.update(profileId, today(), update)
            days.value = store.days()
        }
        sharing().requestScreenTimePush(profileId, immediate = true)
        _revision.update { it + 1 }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 30_000L
        const val START_REFRESH_TIMEOUT_MS = 3_000L
    }
}
