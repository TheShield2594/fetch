package com.fetch.tv.core.streams

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keeps [AddonHealth] on the device so the history survives a restart. */
@Singleton
open class AddonHealthStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    open suspend fun load(): Map<String, AddonHealth> {
        val raw = preferences.getString(KEY_HEALTH, null) ?: return emptyMap()
        return decode(raw)
    }

    open suspend fun save(health: Map<String, AddonHealth>) {
        preferences.edit().putString(KEY_HEALTH, encode(health)).apply()
    }

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

    internal companion object {
        private const val PREFERENCES_NAME = "addon_health"
        private const val KEY_HEALTH = "health_v1"
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(health: Map<String, AddonHealth>): String = json.encodeToString(
            health.values.map { entry ->
                StoredHealth(
                    name = entry.addonName,
                    samples = entry.samples.map { StoredSample(it.outcome.name, it.latencyMs, it.atMs, it.kind.name) },
                    lastError = entry.lastError,
                    lastErrorAt = entry.lastErrorAtMs,
                    lastSuccessAt = entry.lastSuccessAtMs,
                    consecutiveFailures = entry.consecutiveFailures
                )
            }
        )

        fun decode(raw: String): Map<String, AddonHealth> = runCatching {
            json.decodeFromString<List<StoredHealth>>(raw).associate { stored ->
                stored.name to AddonHealth(
                    addonName = stored.name,
                    samples = stored.samples.mapNotNull { sample ->
                        val outcome = AddonRequestOutcome.entries.firstOrNull { it.name == sample.o }
                            ?: return@mapNotNull null
                        val kind = AddonRequestKind.entries.firstOrNull { it.name == sample.k }
                            ?: AddonRequestKind.STREAM
                        AddonHealthSample(outcome, sample.l, sample.t, kind)
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
