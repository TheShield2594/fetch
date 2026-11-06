package com.fetch.tv.core.tmdb

import com.fetch.tv.BuildConfig
import com.fetch.tv.data.local.TmdbSettingsDataStore
import com.fetch.tv.data.remote.api.TmdbApi
import com.fetch.tv.data.remote.api.TmdbPersonSearchResult
import com.fetch.tv.domain.model.MetaCastMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A person found by name; [preferCrew] when they're known for directing, writing, etc. */
data class PersonSearchResult(
    val person: MetaCastMember,
    val preferCrew: Boolean
)

/**
 * Finds people (actors, directors, …) by name on TMDB, for the people row in Search. Results open
 * the cast screen, which is otherwise only reachable from a title's cast row.
 */
@Singleton
class TmdbPeopleSearch @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    /** People matching [query], most relevant first; empty when TMDB is off or the call fails. */
    suspend fun search(query: String): List<PersonSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        val settings = tmdbSettingsDataStore.settings.first()
        if (!settings.enabled) return@withContext emptyList()
        val response = tmdbApi.searchPeople(BuildConfig.TMDB_API_KEY, trimmed, settings.language)
        if (!response.isSuccessful) return@withContext emptyList()
        response.body()?.results.orEmpty().toPeople()
    }

    companion object {
        const val MAX_RESULTS = 20
        private const val PROFILE_IMAGE_BASE = "https://image.tmdb.org/t/p/w185"

        /**
         * Keeps people with a name and at least a photo or a known-for title (TMDB returns many
         * near-empty stubs), and shows what they're known for under the name.
         */
        internal fun List<TmdbPersonSearchResult>.toPeople(): List<PersonSearchResult> =
            asSequence()
                .filter { it.adult != true }
                .mapNotNull { result ->
                    val name = result.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val knownFor = result.knownFor.orEmpty()
                        .mapNotNull { (it.title ?: it.name)?.trim()?.takeIf(String::isNotEmpty) }
                        .distinct()
                        .take(2)
                    val photo = result.profilePath?.takeIf { it.isNotBlank() }?.let { "$PROFILE_IMAGE_BASE$it" }
                    if (photo == null && knownFor.isEmpty()) return@mapNotNull null
                    PersonSearchResult(
                        person = MetaCastMember(
                            name = name,
                            character = knownFor.joinToString(", ").ifEmpty { null },
                            photo = photo,
                            tmdbId = result.id
                        ),
                        preferCrew = result.knownForDepartment != null &&
                            !result.knownForDepartment.equals("Acting", ignoreCase = true)
                    )
                }
                .distinctBy { it.person.tmdbId }
                .take(MAX_RESULTS)
                .toList()
    }
}
