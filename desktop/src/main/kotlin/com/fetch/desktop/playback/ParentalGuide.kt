package com.fetch.desktop.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** One line of the content warnings shown as playback starts ("Violence · Moderate"). */
data class ParentalWarning(val label: String, val severity: String)

/**
 * The parental guide the TV shows when playback starts (`ParentalGuideRepository` there): IMDb's
 * parents guide through the same API, one severity per category, the most-voted one unless
 * "none" had as many votes. The rating the desktop already knows leads, as "Rated R".
 */
class ParentalGuideClient(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_URL
) {
    @Serializable
    private data class Breakdown(val severityLevel: String = "", val voteCount: Int = 0)

    @Serializable
    private data class Category(val category: String = "", val severityBreakdowns: List<Breakdown>? = null)

    @Serializable
    private data class Response(val parentsGuide: List<Category>? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, List<ParentalWarning>>()

    /** The warnings for [imdbId], worst first, at most five; empty when there are none or the API can't be reached. */
    suspend fun warnings(imdbId: String): List<ParentalWarning> {
        if (!imdbId.startsWith("tt")) return emptyList()
        cache[imdbId]?.let { return it }
        val body = withContext(Dispatchers.IO) {
            try {
                val url = (baseUrl.trimEnd('/') + "/titles/").toHttpUrl().newBuilder()
                    .addPathSegment(imdbId).addPathSegment("parentsGuide").build()
                http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
        val parsed = runCatching { json.decodeFromString(Response.serializer(), body) }.getOrNull() ?: return emptyList()
        val byCategory = parsed.parentsGuide.orEmpty().associateBy { it.category.uppercase() }
        val warnings = warningsOf(CATEGORIES.mapNotNull { (key, label) -> severity(byCategory[key])?.let { label to it } })
        cache[imdbId] = warnings
        return warnings
    }

    private fun severity(category: Category?): String? {
        val breakdowns = category?.severityBreakdowns ?: return null
        val dominant = breakdowns.filter { !it.severityLevel.equals("none", ignoreCase = true) }.maxByOrNull { it.voteCount } ?: return null
        val none = breakdowns.firstOrNull { it.severityLevel.equals("none", ignoreCase = true) }?.voteCount ?: 0
        if (dominant.voteCount <= none) return null
        return dominant.severityLevel.lowercase()
    }

    companion object {
        const val DEFAULT_URL = "https://api.tiffara.com/"

        /** IMDb's categories, in the TV's order, with its labels. */
        private val CATEGORIES = listOf(
            "SEXUAL_CONTENT" to "Nudity",
            "VIOLENCE" to "Violence",
            "PROFANITY" to "Profanity",
            "ALCOHOL_DRUGS" to "Alcohol/Drugs",
            "FRIGHTENING_INTENSE_SCENES" to "Frightening"
        )

        private val SEVERITY_ORDER = mapOf("severe" to 0, "moderate" to 1, "mild" to 2)

        /** (label, severity) pairs as the overlay lists them: worst first, at most five, severities capitalised. */
        fun warningsOf(entries: List<Pair<String, String>>): List<ParentalWarning> =
            entries.sortedBy { SEVERITY_ORDER[it.second.lowercase()] ?: 3 }
                .map { (label, severity) -> ParentalWarning(label, severity.replaceFirstChar { it.uppercase() }) }
                .take(5)

        /** The overlay's lines: the rating first ("Rated", "R"), then the guide. */
        fun lines(rating: String?, warnings: List<ParentalWarning>): List<ParentalWarning> =
            listOfNotNull(rating?.takeIf { it.isNotBlank() }?.let { ParentalWarning("Rated", it) }) + warnings
    }
}
