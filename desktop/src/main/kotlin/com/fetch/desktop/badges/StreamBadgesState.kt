package com.fetch.desktop.badges

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.JsonFile
import com.fetch.tv.core.streams.CompiledStreamBadgeFilter
import com.fetch.tv.core.streams.STREAM_BADGE_IMPORT_LIMIT
import com.fetch.tv.core.streams.StreamBadgeImport
import com.fetch.tv.core.streams.StreamBadgeMatcher
import com.fetch.tv.core.streams.StreamBadgePlacement
import com.fetch.tv.core.streams.StreamBadgeRules
import com.fetch.tv.core.streams.StreamBadgeRulesParser
import com.fetch.tv.core.streams.StreamBadgeSettings
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBadge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** When a badge URL was imported and the name shown for it. Keyed by [url]. */
@Serializable
data class StreamBadgeSourceInfo(
    val url: String,
    val name: String = "",
    val lastUpdated: Long = 0L
)

/** What `stream-badges.json` holds: the shared rules plus the desktop's display settings. */
@Serializable
data class StreamBadgesFile(
    val rules: StreamBadgeRules = StreamBadgeRules(),
    val sources: List<StreamBadgeSourceInfo> = emptyList(),
    val showFileSizeBadges: Boolean = true,
    val showAddonLogo: Boolean = true,
    val badgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM
)

/** One imported badge URL as the settings page lists it. */
data class ImportedBadgeSource(
    val url: String,
    val name: String,
    val lastUpdated: Long,
    val isActive: Boolean,
    val enabledBadgeCount: Int,
    val groupCount: Int,
    /** The source's enabled badges, for a preview row. */
    val badges: List<StreamBadge>
)

/**
 * The Fusion-style stream badge imports, as on the TV app: up to [STREAM_BADGE_IMPORT_LIMIT] badge
 * JSON URLs, of which one is active and decides which badges streams get. Saved to [file].
 */
class StreamBadgesState(
    private val scope: CoroutineScope,
    file: File,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val store = JsonFile(file, StreamBadgesFile.serializer()) { StreamBadgesFile() }

    var data: StreamBadgesFile by mutableStateOf(store.load().let { it.copy(rules = it.rules.normalized()) })
        private set

    /** The last import or refresh failure, in words for the user. */
    var error: String? by mutableStateOf(null)

    /** True while a URL is being fetched by [add]. */
    var importing: Boolean by mutableStateOf(false)
        private set

    /** True while [refreshAll] runs. */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    val rules: StreamBadgeRules get() = data.rules
    val showFileSizeBadges: Boolean get() = data.showFileSizeBadges
    val showAddonLogo: Boolean get() = data.showAddonLogo
    val badgePlacement: StreamBadgePlacement get() = data.badgePlacement
    val canAddMore: Boolean get() = rules.imports.size < STREAM_BADGE_IMPORT_LIMIT

    /** The shared settings model, for code written against the TV app's types. */
    val settings: StreamBadgeSettings
        get() = StreamBadgeSettings(rules, showFileSizeBadges, showAddonLogo, badgePlacement)

    val sources: List<ImportedBadgeSource>
        get() = rules.imports.map { import ->
            val info = data.sources.firstOrNull { it.url.equals(import.sourceUrl, ignoreCase = true) }
            ImportedBadgeSource(
                url = import.sourceUrl,
                name = info?.name?.takeIf { it.isNotBlank() } ?: displayName(import.sourceUrl),
                lastUpdated = info?.lastUpdated ?: 0L,
                isActive = import.isActive,
                enabledBadgeCount = import.enabledFilterCount,
                groupCount = import.groups.size,
                badges = import.filters.filter { it.isEnabled }.map {
                    StreamBadge(it.name, it.imageURL, it.tagColor, it.tagStyle, it.textColor, it.borderColor)
                }.distinctBy { it.imageURL.ifBlank { it.name } }
            )
        }

    // Compiled filters for the current rules; rebuilt when the rules change.
    @Volatile private var compiledFor: StreamBadgeRules? = null
    @Volatile private var compiled: List<CompiledStreamBadgeFilter> = emptyList()

    /** The badges of the active import that match [stream]'s file name, title, description and so on. */
    fun badgesFor(stream: Stream): List<StreamBadge> {
        val current = rules
        val filters = if (compiledFor === current) {
            compiled
        } else {
            StreamBadgeMatcher.compile(current).also {
                compiled = it
                compiledFor = current
            }
        }
        return StreamBadgeMatcher.matchedBadges(stream, filters)
    }

    /**
     * Fetches and imports a badge JSON URL, making it the active one. Importing a URL that is
     * already there updates it. Failures land in [error].
     */
    fun add(url: String): Job? {
        val sourceUrl = url.trim()
        validate(sourceUrl)?.let {
            error = it
            return null
        }
        error = null
        importing = true
        return scope.launch {
            try {
                val import = fetchImport(sourceUrl)
                // Re-check: another add may have filled the slots while this one was fetching.
                val exists = rules.imports.any { it.sourceUrl.equals(sourceUrl, ignoreCase = true) }
                if (!exists && !canAddMore) {
                    error = limitMessage()
                } else {
                    save(withImport(data, import, activate = true))
                }
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: IMPORT_FAILED
            } finally {
                importing = false
            }
        }
    }

    fun remove(url: String) {
        val updated = data.copy(
            rules = rules.removeSource(url),
            sources = data.sources.filterNot { it.url.equals(url.trim(), ignoreCase = true) }
        )
        save(updated)
    }

    /** Makes [url] the import whose badges are shown. */
    fun setActive(url: String) = save(data.copy(rules = rules.setActiveSource(url)))

    /** Fetches every imported URL again, keeping the old badges for any that fail. */
    fun refreshAll(): Job? {
        val imports = rules.imports
        if (imports.isEmpty() || refreshing) return null
        error = null
        refreshing = true
        return scope.launch {
            val failures = mutableListOf<String>()
            try {
                imports.forEach { existing ->
                    try {
                        val fresh = fetchImport(existing.sourceUrl)
                        val stillThere = rules.imports.firstOrNull { it.sourceUrl.equals(existing.sourceUrl, ignoreCase = true) }
                        if (stillThere != null) save(withImport(data, fresh, activate = stillThere.isActive))
                    } catch (e: Exception) {
                        failures += "${displayName(existing.sourceUrl)}: ${e.message?.takeIf { it.isNotBlank() } ?: IMPORT_FAILED}"
                    }
                }
                if (failures.isNotEmpty()) error = "Could not refresh ${failures.joinToString("; ")}"
            } finally {
                refreshing = false
            }
        }
    }

    fun setShowFileSizeBadges(show: Boolean) = save(data.copy(showFileSizeBadges = show))

    fun setShowAddonLogo(show: Boolean) = save(data.copy(showAddonLogo = show))

    fun setBadgePlacement(placement: StreamBadgePlacement) = save(data.copy(badgePlacement = placement))

    fun clearError() {
        error = null
    }

    private fun validate(sourceUrl: String): String? = when {
        sourceUrl.isBlank() -> "Enter a badge JSON URL."
        !sourceUrl.startsWith("https://", ignoreCase = true) && !sourceUrl.startsWith("http://", ignoreCase = true) ->
            "Badge URL must start with http:// or https://."
        sourceUrl.toHttpUrlOrNull() == null -> IMPORT_FAILED
        !canAddMore && rules.imports.none { it.sourceUrl.equals(sourceUrl, ignoreCase = true) } -> limitMessage()
        else -> null
    }

    private suspend fun fetchImport(sourceUrl: String): StreamBadgeImport = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(sourceUrl).get().build()
        val payload = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalArgumentException(IMPORT_FAILED)
            response.body?.string().orEmpty()
        }
        StreamBadgeRulesParser.parse(sourceUrl, payload)
    }

    private fun withImport(current: StreamBadgesFile, import: StreamBadgeImport, activate: Boolean): StreamBadgesFile {
        val info = StreamBadgeSourceInfo(import.sourceUrl, displayName(import.sourceUrl), clock())
        return current.copy(
            rules = current.rules.upsert(import, activate = activate),
            sources = current.sources.filterNot { it.url.equals(import.sourceUrl, ignoreCase = true) } + info
        )
    }

    private fun save(updated: StreamBadgesFile) {
        val rules = updated.rules.normalized()
        // Drop metadata for URLs the rules no longer hold (e.g. past the import limit).
        val kept = updated.copy(
            rules = rules,
            sources = updated.sources.filter { info -> rules.imports.any { it.sourceUrl.equals(info.url, ignoreCase = true) } }
        )
        data = kept
        runCatching { store.save(kept) }
    }

    companion object {
        const val IMPORT_FAILED = "Badge import failed."

        fun limitMessage() = "You can import up to $STREAM_BADGE_IMPORT_LIMIT badge URLs."

        /** A short name for a badge URL: its file name without `.json`, else its host. */
        fun displayName(url: String): String {
            val parsed = url.trim().toHttpUrlOrNull() ?: return url.trim()
            val last = parsed.pathSegments.lastOrNull { it.isNotBlank() }
                ?.removeSuffix(".json")
                ?.takeIf { it.isNotBlank() && it.length <= 48 }
            return if (last != null) "${parsed.host} · $last" else parsed.host
        }
    }
}
