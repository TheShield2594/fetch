package com.fetch.desktop.addons

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AppDirs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Timer
import kotlin.concurrent.schedule

enum class AddonRequestOutcome { SUCCESS, EMPTY, ERROR, TIMEOUT }

enum class AddonRequestKind { STREAM, CATALOG, META }

enum class AddonHealthStatus { HEALTHY, SLOW, FAILING, UNKNOWN }

val AddonRequestOutcome.isFailure: Boolean
    get() = this == AddonRequestOutcome.ERROR || this == AddonRequestOutcome.TIMEOUT

data class AddonHealthSample(val outcome: AddonRequestOutcome, val latencyMs: Long, val atMs: Long, val kind: AddonRequestKind = AddonRequestKind.STREAM)

/** One addon's recent requests (the TV's `AddonHealth`): the last [WINDOW_SIZE] plus the last error and success. */
data class AddonHealth(
    val addonName: String,
    val samples: List<AddonHealthSample> = emptyList(),
    val lastError: String? = null,
    val lastErrorAtMs: Long? = null,
    val lastSuccessAtMs: Long? = null,
    /** Errors and timeouts since the last answer; failures within [FAILURE_BURST_MS] of the previous one count once. */
    val consecutiveFailures: Int = 0
) {
    val requests: Int get() = samples.size
    val lastOutcome: AddonRequestOutcome? get() = samples.lastOrNull()?.outcome

    /** Median response time of requests that answered; timeouts only say "slower than the budget". */
    val p50LatencyMs: Long?
        get() {
            val answered = samples.filter { it.outcome != AddonRequestOutcome.TIMEOUT }.map { it.latencyMs }.sorted()
            if (answered.isEmpty()) return null
            val mid = answered.size / 2
            return if (answered.size % 2 == 1) answered[mid] else (answered[mid - 1] + answered[mid]) / 2
        }

    /** Share of the window that errored or timed out, 0..1. */
    val failureRate: Float
        get() = if (samples.isEmpty()) 0f else samples.count { it.outcome.isFailure }.toFloat() / samples.size

    val shouldSuggestDisabling: Boolean get() = consecutiveFailures >= SUGGEST_DISABLE_AFTER_FAILURES

    val status: AddonHealthStatus
        get() = when {
            requests == 0 -> AddonHealthStatus.UNKNOWN
            lastOutcome?.isFailure == true -> AddonHealthStatus.FAILING
            (p50LatencyMs ?: 0L) >= SLOW_THRESHOLD_MS -> AddonHealthStatus.SLOW
            else -> AddonHealthStatus.HEALTHY
        }

    fun plus(sample: AddonHealthSample, error: String?): AddonHealth = copy(
        samples = (samples + sample).takeLast(WINDOW_SIZE),
        lastError = if (sample.outcome.isFailure) error?.take(200) ?: lastError else lastError,
        lastErrorAtMs = if (sample.outcome.isFailure) sample.atMs else lastErrorAtMs,
        lastSuccessAtMs = if (sample.outcome.isFailure) lastSuccessAtMs else sample.atMs,
        consecutiveFailures = when {
            !sample.outcome.isFailure -> 0
            lastErrorAtMs != null && sample.atMs - lastErrorAtMs < FAILURE_BURST_MS -> consecutiveFailures.coerceAtLeast(1)
            else -> consecutiveFailures + 1
        }
    )

    companion object {
        const val SLOW_THRESHOLD_MS = 5_000L
        const val WINDOW_SIZE = 50
        const val SUGGEST_DISABLE_AFTER_FAILURES = 5
        const val FAILURE_BURST_MS = 10_000L
    }
}

/** The health panel's sort chips, as on TV; the first is the default. */
enum class AddonHealthSort(val label: String) { FAILURE_RATE("Failure rate"), LATENCY("Response time"), LAST_SUCCESS("Last success"), NAME("Name") }

/**
 * Orders the panel's addons (by display name → health), as the TV's `sortAddonHealthRows`:
 * everything but [AddonHealthSort.NAME] puts the worst first, and addons without history go last.
 */
fun <T> sortByHealth(rows: List<T>, name: (T) -> String, health: (T) -> AddonHealth?, sort: AddonHealthSort): List<T> {
    val byName = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { name(it) }
    val hasHistory = compareBy<T> { (health(it)?.requests ?: 0) == 0 }
    return when (sort) {
        AddonHealthSort.NAME -> rows.sortedWith(byName)
        AddonHealthSort.LATENCY -> rows.sortedWith(hasHistory.thenByDescending<T> { health(it)?.p50LatencyMs ?: -1L }.then(byName))
        AddonHealthSort.FAILURE_RATE -> rows.sortedWith(hasHistory.thenByDescending<T> { health(it)?.failureRate ?: 0f }.then(byName))
        AddonHealthSort.LAST_SUCCESS -> rows.sortedWith(hasHistory.thenBy<T> { health(it)?.lastSuccessAtMs ?: Long.MIN_VALUE }.then(byName))
    }
}

/**
 * How each addon behaves (the TV's `AddonHealthTracker`), so a dead or slow one shows in Addons ›
 * Addon health. Kept in `addon-health.json` across restarts, for the whole app; nothing leaves the
 * computer. Saving waits a few seconds after the last request, as on TV.
 */
class AddonHealthTracker(private val file: File?, private val saveDelayMs: Long = 3_000L) {
    var health: Map<String, AddonHealth> by mutableStateOf(load())
        private set

    private val lock = Any()
    private var timer: Timer? = null

    fun record(addonName: String, outcome: AddonRequestOutcome, latencyMs: Long, error: String? = null, kind: AddonRequestKind = AddonRequestKind.STREAM, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val previous = health[addonName] ?: AddonHealth(addonName)
            health = health + (addonName to previous.plus(AddonHealthSample(outcome, latencyMs, nowMs, kind), error))
        }
        scheduleSave()
    }

    fun clear() {
        synchronized(lock) { health = emptyMap() }
        scheduleSave()
    }

    private fun scheduleSave() {
        val target = file ?: return
        synchronized(lock) {
            timer?.cancel()
            timer = Timer("addon-health", true).also { it.schedule(saveDelayMs) { save(target) } }
        }
    }

    /** Writes now (also used by tests). */
    fun save(target: File? = file) {
        if (target == null) return
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(encode(health))
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun load(): Map<String, AddonHealth> = file?.takeIf { it.exists() }?.let { runCatching { decode(it.readText()) }.getOrNull() }.orEmpty()

    @Serializable
    private data class StoredSample(val o: String, val l: Long, val t: Long, val k: String)

    @Serializable
    private data class StoredHealth(
        val name: String,
        val samples: List<StoredSample> = emptyList(),
        val lastError: String? = null,
        val lastErrorAt: Long? = null,
        val lastSuccessAt: Long? = null,
        val consecutiveFailures: Int = 0
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** One record for the whole app, as the TV keeps one per device. */
        val shared: AddonHealthTracker by lazy { AddonHealthTracker(File(AppDirs.config, "addon-health.json")) }

        /** The TV's stored format (`AddonHealthStore`). */
        fun encode(health: Map<String, AddonHealth>): String = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(StoredHealth.serializer()),
            health.values.map { entry ->
                StoredHealth(
                    entry.addonName,
                    entry.samples.map { StoredSample(it.outcome.name, it.latencyMs, it.atMs, it.kind.name) },
                    entry.lastError, entry.lastErrorAtMs, entry.lastSuccessAtMs, entry.consecutiveFailures
                )
            }
        )

        fun decode(raw: String): Map<String, AddonHealth> = runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(StoredHealth.serializer()), raw).associate { stored ->
                stored.name to AddonHealth(
                    addonName = stored.name,
                    samples = stored.samples.mapNotNull { s ->
                        val outcome = AddonRequestOutcome.entries.firstOrNull { it.name == s.o } ?: return@mapNotNull null
                        AddonHealthSample(outcome, s.l, s.t, AddonRequestKind.entries.firstOrNull { it.name == s.k } ?: AddonRequestKind.STREAM)
                    }.takeLast(AddonHealth.WINDOW_SIZE),
                    lastError = stored.lastError,
                    lastErrorAtMs = stored.lastErrorAt,
                    lastSuccessAtMs = stored.lastSuccessAt,
                    consecutiveFailures = stored.consecutiveFailures
                )
            }
        }.getOrDefault(emptyMap())
    }
}
