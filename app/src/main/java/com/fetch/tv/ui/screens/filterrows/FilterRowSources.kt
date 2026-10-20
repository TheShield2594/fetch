package com.fetch.tv.ui.screens.filterrows

import com.fetch.tv.data.mediaserver.ServerCatalog
import com.fetch.tv.domain.model.FilterRowSource
import com.fetch.tv.domain.model.enabledAddons
import com.fetch.tv.domain.model.supportsExtra
import com.fetch.tv.domain.repository.AddonRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

data class FilterRowSourceOption(
    val key: String,
    val label: String,
    val source: FilterRowSource,
    /** Genres an addon catalog offers; null for a server library, whose genres are fetched. */
    val addonGenres: List<String>?,
    /** The addon rejects requests for this catalog without a genre. */
    val genreRequired: Boolean = false
) {
    val isServer: Boolean
        get() = source is FilterRowSource.ServerLibrary
}

/** What a filter row can read from: server libraries, and addon catalogs that take a genre. */
class FilterRowSources @Inject constructor(
    private val serverCatalog: ServerCatalog,
    private val addonRepository: AddonRepository
) {
    suspend fun options(): List<FilterRowSourceOption> {
        val servers = serverCatalog.titleLibraries().map { ref ->
            val source = FilterRowSource.ServerLibrary(ref.connection.id, ref.library.id)
            FilterRowSourceOption(key(source), ref.title, source, addonGenres = null)
        }
        val addons = addonRepository.getInstalledAddons().first().enabledAddons().flatMap { addon ->
            addon.catalogs
                .filter { catalog ->
                    !catalog.isSearchOnlyCatalog() &&
                        catalog.supportsExtra("genre") &&
                        catalog.extra.none { it.isRequired && !it.name.equals("genre", ignoreCase = true) }
                }
                .map { catalog ->
                    val source = FilterRowSource.AddonCatalog(addon.id, catalog.apiType, catalog.id)
                    val genres = catalog.extra
                        .firstOrNull { it.name.equals("genre", ignoreCase = true) }
                        ?.options
                        .orEmpty()
                    FilterRowSourceOption(
                        key = key(source),
                        label = "${addon.displayName} · ${catalog.name} (${catalog.apiType})",
                        source = source,
                        addonGenres = genres,
                        genreRequired = catalog.extra.any { it.isRequired && it.name.equals("genre", ignoreCase = true) } ||
                            catalog.extraRequired.any { it.equals("genre", ignoreCase = true) }
                    )
                }
        }
        return servers + addons
    }

    suspend fun serverGenres(source: FilterRowSource.ServerLibrary): List<String> =
        serverCatalog.genres(source.connectionId, source.libraryId)

    companion object {
        fun key(source: FilterRowSource): String = when (source) {
            is FilterRowSource.ServerLibrary -> "server:${source.connectionId}:${source.libraryId}"
            is FilterRowSource.AddonCatalog -> "addon:${source.addonId}:${source.type}:${source.catalogId}"
        }
    }
}
