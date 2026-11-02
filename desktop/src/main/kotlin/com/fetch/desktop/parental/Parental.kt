package com.fetch.desktop.parental

import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.ProfileLimits
import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.core.parental.ContentRestriction
import com.fetch.tv.core.parental.PinAttemptPolicy
import com.fetch.tv.core.parental.ProfilePinHasher
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Why playback can't start on a limited profile right now. */
enum class ScreenTimeBlock(val title: String, val body: String) {
    DAILY("That’s all for today", "Today’s screen time is used up. See you tomorrow!"),
    BEDTIME("That’s all for today", "It’s bedtime. See you tomorrow!")
}

/** Minutes watched on one day, plus any extra time a parent added. */
@Serializable
data class ScreenTimeDay(val date: String, val watchedMs: Long = 0, val extraMinutes: Int = 0, val unlimited: Boolean = false)

/** The TV app's limit rules, on the desktop's saved state. Pure, so they're testable. */
object Parental {
    const val BEDTIME_END_MINUTE = 7 * 60

    val RatingChoices: List<CertificationLevel?> = listOf(
        null, CertificationLevel.ALL_AGES, CertificationLevel.OLDER_KIDS, CertificationLevel.PARENTAL_GUIDANCE,
        CertificationLevel.TEENS, CertificationLevel.MATURE
    )

    fun ratingLabel(level: CertificationLevel?): String = when (level) {
        null -> "No limit"
        CertificationLevel.ALL_AGES -> "All ages (G, TV-Y, TV-G)"
        CertificationLevel.OLDER_KIDS -> "Older kids (TV-Y7)"
        CertificationLevel.PARENTAL_GUIDANCE -> "PG, TV-PG"
        CertificationLevel.TEENS -> "PG-13, TV-14"
        CertificationLevel.MATURE -> "R, TV-MA"
        CertificationLevel.ADULTS_ONLY -> "Adults only"
    }

    fun restriction(limits: ProfileLimits) =
        ContentRestriction(CertificationLevel.fromName(limits.maxRating), limits.allowUnrated)

    /** Whether a title rated [certification] (e.g. "PG-13", "TV-MA") may be shown. */
    fun allows(limits: ProfileLimits, certification: String?, country: String? = null): Boolean =
        restriction(limits).allowsCertification(certification, country)

    fun isBedtime(limits: ProfileLimits, now: LocalDateTime): Boolean {
        val start = limits.bedtimeStartMinute ?: return false
        val minute = now.hour * 60 + now.minute
        return minute >= start || minute < BEDTIME_END_MINUTE
    }

    /** Null when playback may start; otherwise why not. */
    fun block(limits: ProfileLimits, day: ScreenTimeDay, now: LocalDateTime): ScreenTimeBlock? {
        if (day.unlimited) return null
        if (isBedtime(limits, now)) return ScreenTimeBlock.BEDTIME
        val daily = limits.dailyMinutes ?: return null
        val allowedMs = (daily + day.extraMinutes) * 60_000L
        return if (day.watchedMs >= allowedMs) ScreenTimeBlock.DAILY else null
    }

    /** Today's record: yesterday's resets at midnight. */
    fun today(day: ScreenTimeDay?, now: LocalDate): ScreenTimeDay =
        day?.takeIf { it.date == now.toString() } ?: ScreenTimeDay(now.toString())

    fun minutesLeft(limits: ProfileLimits, day: ScreenTimeDay): Int? {
        val daily = limits.dailyMinutes ?: return null
        if (day.unlimited) return null
        return ((daily + day.extraMinutes) - (day.watchedMs / 60_000L).toInt()).coerceAtLeast(0)
    }
}

/** Today's screen time for one profile, in its folder. */
fun screenTimeFile(dir: File) = JsonFile(File(dir, "screen-time.json"), ScreenTimeDay.serializer()) { ScreenTimeDay(LocalDate.now().toString()) }

/**
 * Checks PINs with the TV app's lockout: a few free tries, then waits that double. Kept in
 * memory, per profile id.
 */
class PinGate(private val now: () -> Long = System::currentTimeMillis) {
    private val failures = mutableMapOf<Int, Int>()
    private val lockedUntil = mutableMapOf<Int, Long>()

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val message: String) : Result
        data class Locked(val seconds: Int) : Result
    }

    fun check(profileId: Int, pinHash: String?, pin: String): Result {
        val wait = PinAttemptPolicy.retryAfterSeconds(lockedUntil[profileId] ?: 0, now())
        if (wait > 0) return Result.Locked(wait)
        if (pinHash == null || ProfilePinHasher.verify(pin, pinHash)) {
            failures.remove(profileId)
            return Result.Ok
        }
        val count = (failures[profileId] ?: 0) + 1
        failures[profileId] = count
        val lockout = PinAttemptPolicy.lockoutAfterFailures(count)
        if (lockout > 0) {
            lockedUntil[profileId] = now() + lockout
            return Result.Locked(PinAttemptPolicy.retryAfterSeconds(now() + lockout, now()))
        }
        return Result.Wrong("Incorrect PIN. Try again.")
    }
}
