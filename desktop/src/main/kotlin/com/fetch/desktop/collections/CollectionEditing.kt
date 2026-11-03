package com.fetch.desktop.collections

import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.CollectionSource
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import com.fetch.tv.domain.model.TmdbCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionSourceType
import com.fetch.tv.domain.model.catalogTypesMatch

/** Movies, series, or both (two sources, one of each), for the editor's TMDB and Trakt pickers. */
enum class MediaChoice(val label: String) {
    MOVIES("Movies"), SERIES("Series"), BOTH("Both");

    fun types(): List<TmdbCollectionMediaType> = when (this) {
        MOVIES -> listOf(TmdbCollectionMediaType.MOVIE)
        SERIES -> listOf(TmdbCollectionMediaType.TV)
        BOTH -> listOf(TmdbCollectionMediaType.MOVIE, TmdbCollectionMediaType.TV)
    }
}

/** An installed catalog a folder can use, with the genres its addon offers for it. */
data class PickableCatalog(val addon: Addon, val catalog: CatalogDescriptor) {
    val genres: List<String> = catalog.extra.firstOrNull { it.name.equals("genre", ignoreCase = true) }?.options.orEmpty()
    val genreRequired: Boolean =
        catalog.extra.any { it.name.equals("genre", ignoreCase = true) && it.isRequired } ||
            catalog.extraRequired.any { it.equals("genre", ignoreCase = true) }

    fun matches(source: AddonCatalogCollectionSource): Boolean =
        source.addonId == addon.id && catalogTypesMatch(source.type, catalog.apiType) && source.catalogId == catalog.id

    /** A catalog that must have a genre starts on the first; others start without one (the TV's rule). */
    fun genreFor(requested: String?): String? = when {
        genres.isEmpty() -> null
        requested != null && requested in genres -> requested
        genreRequired -> genres.firstOrNull()
        else -> null
    }

    fun toSource(): AddonCatalogCollectionSource =
        AddonCatalogCollectionSource(addon.id, catalog.apiType, catalog.id, genreFor(null), addon.baseUrl)
}

/** The collection editor's rules, as the TV's `CollectionEditorViewModel` applies them. */
object CollectionEditing {

    /** Sources with these types bring a cover to a folder that has none. */
    private val coverTypes = setOf(
        TmdbCollectionSourceType.COLLECTION,
        TmdbCollectionSourceType.COMPANY,
        TmdbCollectionSourceType.NETWORK,
        TmdbCollectionSourceType.PERSON,
        TmdbCollectionSourceType.DIRECTOR
    )

    fun newCollection(id: String) = Collection(id = id, title = "")

    /** A new folder is a poster tile, as on TV. */
    fun newFolder(id: String) = CollectionFolder(id = id, title = "", tileShape = com.fetch.tv.domain.model.PosterShape.POSTER)

    /** Every installed catalog a folder can show: not search-only, nor needing anything but a genre. */
    fun pickableCatalogs(addons: List<Addon>): List<PickableCatalog> = addons.filter { it.enabled }.flatMap { addon ->
        addon.catalogs
            .filter { c ->
                !c.isSearchOnlyCatalog() &&
                    c.extraRequired.none { it.equals("search", ignoreCase = true) } &&
                    c.extra.none { it.isRequired && !it.name.equals("genre", ignoreCase = true) && !it.name.equals("skip", ignoreCase = true) }
            }
            .map { PickableCatalog(addon, it) }
    }

    /** Adds or removes [catalog] from [folder]. */
    fun toggleCatalog(folder: CollectionFolder, catalog: PickableCatalog): CollectionFolder {
        val existing = folder.sources.indexOfFirst { it is AddonCatalogCollectionSource && catalog.matches(it) }
        return folder.copy(sources = if (existing >= 0) folder.sources.filterIndexed { i, _ -> i != existing } else folder.sources + catalog.toSource())
    }

    fun setGenre(folder: CollectionFolder, index: Int, genre: String?, catalogs: List<PickableCatalog>): CollectionFolder {
        val source = folder.sources.getOrNull(index) as? AddonCatalogCollectionSource ?: return folder
        val catalog = catalogs.firstOrNull { it.matches(source) }
        val chosen = catalog?.genreFor(genre) ?: genre
        return folder.copy(sources = folder.sources.toMutableList().also { it[index] = source.copy(genre = chosen) })
    }

    /**
     * Adds [sources] that aren't on [folder] yet. A cover from TMDB or Trakt is used when the
     * folder has none, replacing its emoji.
     */
    fun addSources(folder: CollectionFolder, sources: List<CollectionSource>, cover: String? = null): CollectionFolder {
        val fresh = sources.filter { it !in folder.sources }.distinct()
        if (fresh.isEmpty()) return folder
        val coverApplies = !cover.isNullOrBlank() && folder.coverImageUrl.isNullOrBlank() &&
            fresh.any { it !is TmdbCollectionSource || it.sourceType in coverTypes }
        return if (coverApplies) folder.copy(sources = folder.sources + fresh, coverImageUrl = cover, coverEmoji = null)
        else folder.copy(sources = folder.sources + fresh)
    }

    fun removeSource(folder: CollectionFolder, index: Int): CollectionFolder =
        folder.copy(sources = folder.sources.filterIndexed { i, _ -> i != index })

    fun <T> move(list: List<T>, index: Int, delta: Int): List<T> {
        val to = index + delta
        if (index !in list.indices || to !in list.indices) return list
        return list.toMutableList().also { it.add(to, it.removeAt(index)) }
    }

    /** "Pixar" for one, "Pixar Movies" and "Pixar Series" when both were picked. */
    fun titleForMedia(title: String, type: TmdbCollectionMediaType, addSuffix: Boolean): String =
        if (!addSuffix) title else "$title ${if (type == TmdbCollectionMediaType.TV) "Series" else "Movies"}"

    /** The media types a TMDB source of [type] can have: networks are shows, lists and collections movies. */
    fun tmdbMediaTypes(type: TmdbCollectionSourceType, choice: MediaChoice): List<TmdbCollectionMediaType> = when (type) {
        TmdbCollectionSourceType.NETWORK -> listOf(TmdbCollectionMediaType.TV)
        TmdbCollectionSourceType.LIST, TmdbCollectionSourceType.COLLECTION -> listOf(TmdbCollectionMediaType.MOVIE)
        else -> choice.types()
    }

    /** The folder as saved: a title, and blank addresses dropped. */
    fun cleanFolder(folder: CollectionFolder): CollectionFolder = folder.copy(
        title = folder.title.trim().ifBlank { "Untitled" },
        coverImageUrl = folder.coverImageUrl?.trim()?.ifBlank { null },
        coverEmoji = folder.coverEmoji?.trim()?.ifBlank { null },
        focusGifUrl = folder.focusGifUrl?.trim()?.ifBlank { null },
        heroBackdropUrl = folder.heroBackdropUrl?.trim()?.ifBlank { null },
        heroVideoUrl = folder.heroVideoUrl?.trim()?.ifBlank { null },
        titleLogoUrl = folder.titleLogoUrl?.trim()?.ifBlank { null }
    )

    fun cleanCollection(collection: Collection): Collection = collection.copy(
        title = collection.title.trim().ifBlank { "Untitled collection" },
        backdropImageUrl = collection.backdropImageUrl?.trim()?.ifBlank { null }
    )

    /** A Trakt list's id or slug from `123`, a trakt.tv list address or `?id=` (the TV's parser). */
    fun parseTraktList(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        trimmed.toLongOrNull()?.let { return it.toString() }
        Regex("""[?&]id=([^&#/]+)""").find(trimmed)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        Regex("""trakt\.tv/lists/([^/?#]+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        Regex("""trakt\.tv/users/[^/]+/lists/([^/?#]+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return trimmed.takeIf { it.matches(Regex("""[A-Za-z0-9_-]+""")) }
    }

    /** The TV's emoji picks for folder covers (a few from each of its categories). */
    val emojis: List<String> = listOf(
        "🎬", "🎭", "🎥", "📺", "🍿", "🎞️", "📽️", "📡",
        "💀", "👻", "🔪", "💣", "🚀", "🛸", "🧙", "🦸", "🧟", "🤖", "💘", "😂", "😱", "🤯", "😈",
        "⚽", "🏀", "🏈", "🏎️", "🏆", "🎵", "🎤", "🎸", "🎹",
        "🌍", "🌊", "🏔️", "🌙", "⭐", "🔥", "❄️", "🌈", "🌸",
        "🐕", "🐈", "🦁", "🐻", "🦊", "🐉", "🦋", "🦈",
        "🍕", "🍔", "🍣", "🍩", "☕", "✈️", "🚗", "🏰", "🗽",
        "👨‍👩‍👧‍👦", "👶", "🥷", "🧑‍🚀", "🎮", "🕹️", "🔮", "💎", "🎁", "📚",
        "🏳️‍🌈", "🇺🇸", "🇬🇧", "🇯🇵", "🇰🇷", "🇮🇳", "🇫🇷", "🇩🇪", "🇪🇸", "🇮🇹", "🇧🇷", "🇲🇽"
    )

    /** The TV's quick picks for TMDB studios and networks. */
    val tmdbPresets: List<TmdbCollectionSource> = listOf(
        TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Marvel Studios", 420, TmdbCollectionMediaType.MOVIE),
        TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Walt Disney Pictures", 2, TmdbCollectionMediaType.MOVIE),
        TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Pixar", 3, TmdbCollectionMediaType.MOVIE),
        TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Lucasfilm", 1, TmdbCollectionMediaType.MOVIE),
        TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Warner Bros.", 174, TmdbCollectionMediaType.MOVIE),
        TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Netflix", 213, TmdbCollectionMediaType.TV),
        TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "HBO", 49, TmdbCollectionMediaType.TV),
        TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Disney+", 2739, TmdbCollectionMediaType.TV),
        TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Prime Video", 1024, TmdbCollectionMediaType.TV),
        TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Hulu", 453, TmdbCollectionMediaType.TV),
        TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Apple TV+", 2552, TmdbCollectionMediaType.TV)
    )
}
