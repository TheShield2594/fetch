package com.fetch.desktop.home

/** One row of Home below Continue Watching, in the order Home shows them. */
sealed interface HomeRowRef {
    val key: String

    /** An addon catalog (or a filter row over one), by [com.fetch.desktop.CatalogRow.key]. */
    data class Catalog(override val key: String) : HomeRowRef

    data class Collection(val id: String) : HomeRowRef {
        override val key: String get() = HomeLayout.collectionKey(id)
    }

    /** TMDB's "Because you watched" row for one finished title, by its index. */
    data class BecauseYouWatched(val index: Int, val seedId: String) : HomeRowRef {
        override val key: String get() = "byw|$seedId"
    }

    /** A filter row over a server library. */
    data class ServerFilter(val id: String) : HomeRowRef {
        override val key: String get() = "server-filter|$id"
    }

    /** A Jellyfin, Emby or Plex library. */
    data class Server(val rowKey: String) : HomeRowRef {
        override val key: String get() = "server|$rowKey"
    }
}

/**
 * Where Home's rows go, as the TV's Home presentation decides: collections pinned to the top
 * first, then "Because you watched", then the catalogs and the other collections in the Home
 * catalogs order, then your server rows. A kids profile gets no recommendations and at most
 * [KIDS_MAX_ROWS] rows after Continue Watching.
 */
object HomeLayout {
    /** Rows a kids Home shows after Continue Watching (the TV's KIDS_HOME_MAX_ROWS). */
    const val KIDS_MAX_ROWS = 6

    /** How much bigger cards are on a kids Home (the TV's KIDS_HOME_CARD_SCALE). */
    const val KIDS_CARD_SCALE = 1.25f

    /** The TV's catalog-order key for a collection, also used to hide one from Home. */
    fun collectionKey(id: String) = "collection_$id"

    data class Collection(val id: String, val pinToTop: Boolean)

    /**
     * Catalogs and collections between the pinned collections and the server rows. [catalogs]
     * are Home's rows in their own order (pinned ones first, then addon order); [order] is the
     * order saved on the Home catalogs page, holding catalog keys and [collectionKey]s.
     *
     * Without a saved order, collections follow the catalogs. With one, everything takes its
     * saved place (pinned catalogs still lead); with [followAddonsOrder], catalogs keep their
     * own order and each collection goes after the catalog it was saved after.
     */
    fun middle(
        catalogs: List<String>,
        collections: List<String>,
        order: List<String>,
        pinnedCatalogs: List<String> = emptyList(),
        followAddonsOrder: Boolean = false
    ): List<String> {
        val collectionKeys = collections.map(::collectionKey)
        val known = (catalogs + collectionKeys).toSet()
        val saved = order.filter { it in known }.distinct()
        if (saved.isEmpty()) return catalogs + collectionKeys
        if (followAddonsOrder) {
            // Each collection goes after the last catalog before it in the saved order.
            val after = linkedMapOf<String?, MutableList<String>>()
            var lastCatalog: String? = null
            saved.forEach { key ->
                if (key in collectionKeys) after.getOrPut(lastCatalog) { mutableListOf() } += key
                else lastCatalog = key
            }
            val placed = after.values.flatten().toSet()
            val unplaced = collectionKeys.filter { it !in placed }
            return buildList {
                addAll(after[null].orEmpty())
                catalogs.forEach { key ->
                    add(key)
                    addAll(after[key].orEmpty())
                }
                // A collection saved after a catalog that's gone now goes at the end.
                addAll(after.filterKeys { it != null && it !in catalogs }.values.flatten())
                addAll(unplaced)
            }
        }
        val pinned = catalogs.filter { it in pinnedCatalogs }
        val rest = (catalogs - pinned.toSet()) + collectionKeys
        val rank = saved.withIndex().associate { (i, k) -> k to i }
        // Unsaved rows keep their place relative to their neighbours: after the saved ones before them.
        val ordered = rest.withIndex()
            .sortedWith(compareBy<IndexedValue<String>>({ rank[it.value]?.toDouble() ?: savedRankBefore(rest, it.index, rank) }, { it.index }))
            .map { it.value }
        return pinned + ordered
    }

    /** For a row not in the saved order: the rank of the nearest saved row before it, plus a half. */
    private fun savedRankBefore(rows: List<String>, index: Int, rank: Map<String, Int>): Double {
        for (i in index - 1 downTo 0) rank[rows[i]]?.let { return it + 0.5 }
        return -0.5
    }

    /**
     * Every row below Continue Watching. [hidden] holds hidden row keys, including
     * [collectionKey]s; [catalogs] are already filtered (Home's rows).
     */
    fun rows(
        catalogs: List<String>,
        collections: List<Collection>,
        becauseYouWatched: List<String>,
        serverFilters: List<String>,
        servers: List<String>,
        order: List<String>,
        pinnedCatalogs: List<String>,
        hidden: Set<String>,
        followAddonsOrder: Boolean,
        kids: Boolean
    ): List<HomeRowRef> {
        val shown = collections.filter { collectionKey(it.id) !in hidden }
        val pinnedCollections = shown.filter { it.pinToTop }.map { HomeRowRef.Collection(it.id) }
        val middle = middle(catalogs, shown.filter { !it.pinToTop }.map { it.id }, order, pinnedCatalogs, followAddonsOrder).map { key ->
            if (key.startsWith("collection_") && key !in catalogs) HomeRowRef.Collection(key.removePrefix("collection_")) else HomeRowRef.Catalog(key)
        }
        val recommendations = if (kids) emptyList() else becauseYouWatched.mapIndexed { i, id -> HomeRowRef.BecauseYouWatched(i, id) }
        val all = pinnedCollections + recommendations + middle +
            serverFilters.map { HomeRowRef.ServerFilter(it) } + servers.map { HomeRowRef.Server(it) }
        return if (kids) all.take(KIDS_MAX_ROWS) else all
    }

    /** [order] (or Home's current order when empty) with [key] moved [delta] places among [current]. */
    fun move(current: List<String>, key: String, delta: Int): List<String> {
        val list = current.toMutableList()
        val from = list.indexOf(key)
        if (from < 0) return current
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return current
        list.removeAt(from)
        list.add(to, key)
        return list
    }
}
