package com.fetch.tv.core.parental

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
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
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local date (`yyyy-MM-dd`) on which the file was found corrupt and replaced. That day's usage
 * is unknown, so for the rest of it a daily allowance counts as used up; see [ScreenTimeStore.resetDate].
 */
private val screenTimeCorruptionResetDateKey = stringPreferencesKey("corruption_reset_date")

private val Context.screenTimeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "screen_time",
    // Fail closed: a corrupt file must not hand out a fresh allowance.
    corruptionHandler = ReplaceFileCorruptionHandler {
        preferencesOf(screenTimeCorruptionResetDateKey to LocalDate.now().toString())
    }
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

/**
 * Today's record for a profile, or a fresh one when the stored one is from an earlier day.
 *
 * A record from a *later* day than [date] means the wall clock went backwards (dates are
 * `yyyy-MM-dd`, so they compare as strings). Starting a fresh day then would let setting the
 * clock back hand out a new allowance, so the later record is kept and counted against instead.
 */
internal fun ScreenTimeDay?.forDate(date: String): ScreenTimeDay =
    this?.takeIf { it.date >= date } ?: ScreenTimeDay(date)

/** Watched time standing in for a day whose usage was lost: past any allowance, without overflowing a sum. */
internal const val LOST_DAY_WATCHED_MS = Long.MAX_VALUE / 4

@Singleton
class ScreenTimeStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.screenTimeDataStore)

    private val daysKey = stringPreferencesKey("days")
    // Other TVs' records, kept by installs from when screen time was shared through the account.
    // Never read; dropped on the next write.
    private val legacyOtherDevicesKey = stringPreferencesKey("other_devices")
    private val resetDateKey = screenTimeCorruptionResetDateKey
    private val json = Json { ignoreUnknownKeys = true }

    /** The local date the file was lost to corruption, if it was; see [ScreenTimeManager]. */
    val resetDate: Flow<String?> = dataStore.data.map { prefs -> prefs[resetDateKey] }

    suspend fun days(): Map<Int, ScreenTimeDay> = decode(dataStore.data.first()[daysKey])

    val daysFlow: Flow<Map<Int, ScreenTimeDay>> = dataStore.data.map { prefs -> decode(prefs[daysKey]) }

    /** For a deleted profile: its record. A new profile can reuse the id. */
    suspend fun deleteProfile(profileId: Int) {
        dataStore.edit { prefs ->
            prefs.remove(legacyOtherDevicesKey)
            val days = decode(prefs[daysKey])
            if (profileId in days) prefs[daysKey] = json.encodeToString((days - profileId).mapKeys { it.key.toString() })
        }
    }

    /** After sign-out: nothing from the old account's profiles may carry over. */
    suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    suspend fun update(profileId: Int, date: String, change: (ScreenTimeDay) -> ScreenTimeDay) {
        dataStore.edit { prefs ->
            prefs.remove(legacyOtherDevicesKey)
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
 * Daily screen time and bedtime for kids profiles. Playback time is counted on this TV and resets
 * at local midnight; a parent can add time or lift the limits for the day.
 */
@Singleton
class ScreenTimeManager internal constructor(
    private val parentalControls: ParentalControls,
    private val profileManager: ProfileManager,
    private val store: ScreenTimeStore,
    private val clock: () -> LocalDateTime,
    private val wallClockMs: () -> Long
) {
    @Inject
    constructor(
        parentalControls: ParentalControls,
        profileManager: ProfileManager,
        store: ScreenTimeStore
    ) : this(parentalControls, profileManager, store, LocalDateTime::now, System::currentTimeMillis)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val days = MutableStateFlow<Map<Int, ScreenTimeDay>>(emptyMap())
    private val corruptionResetDate = MutableStateFlow<String?>(null)
    // Playback counted but not saved yet, per profile; saved every SAVE_INTERVAL_MS.
    private val unsavedMs = mutableMapOf<Int, Long>()
    private var lastSaveMs = 0L

    private val _revision = MutableStateFlow(0)
    /** Goes up when a limit may have changed: time was added or the day was unlocked. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        scope.launch { store.daysFlow.collect { days.value = it } }
        scope.launch { store.resetDate.collect { corruptionResetDate.value = it } }
    }

    private fun today(): String = clock().toLocalDate().toString()

    private fun dayFor(profileId: Int): ScreenTimeDay {
        val today = today()
        val record = days.value[profileId].forDate(today)
        // The file was lost to corruption today, so today's usage before that is unknown: the
        // allowance counts as used until a parent adds time or unlocks the day. Only in memory;
        // nothing of it is saved.
        val lostToday = corruptionResetDate.value == today && record.extraMinutes == 0 && !record.unlockedForDay
        val stored = if (lostToday) record.copy(watchedMs = record.watchedMs + LOST_DAY_WATCHED_MS) else record
        val unsaved = synchronized(unsavedMs) { unsavedMs[profileId] ?: 0L }
        return stored.copy(watchedMs = stored.watchedMs + unsaved)
    }

    /** Whether the active profile may watch right now. */
    fun status(): ScreenTimeStatus {
        val rules = parentalControls.activeKidsRules.value?.takeIf { it.dailyMinutes != null || it.hasBedtime }
        val profileId = profileManager.activeProfileId.value
        return screenTimeStatus(rules, dayFor(profileId), clock(), wallClockMs())
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
        _revision.update { it + 1 }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 30_000L
    }
}
