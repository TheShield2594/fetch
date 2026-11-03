package com.fetch.desktop.library

import com.fetch.desktop.ListProvider
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.LibrarySort

/**
 * A list's own order, as its provider keeps it (Trakt's ranks, MDBList's and Simkl's order), as
 * the TV's `LibraryProviderOrder`: titles by "type:id", in the order the provider listed them.
 */
data class LibraryProviderOrder(val ranks: Map<String, Int>) {
    fun comparator(): Comparator<LibraryEntry> =
        compareBy<LibraryEntry> { ranks[key(it)] ?: Int.MAX_VALUE }.thenBy { it.id }

    companion object {
        fun key(entry: LibraryEntry) = "${entry.type}:${entry.id}"

        /** The order of [items] as the provider gave them. */
        fun of(items: List<LibraryEntry>): LibraryProviderOrder {
            // A title listed twice keeps its first place.
            val ranks = LinkedHashMap<String, Int>()
            items.forEachIndexed { i, e -> ranks.putIfAbsent(key(e), i) }
            return LibraryProviderOrder(ranks)
        }

        /**
         * The sort options a provider's lists offer, as the TV's: the provider's own order first
         * for Trakt, Simkl and MDBList (MDBList also by release); local lists by when added or title.
         */
        fun sortOptions(provider: ListProvider): List<LibrarySort> = when (provider) {
            ListProvider.LOCAL -> listOf(LibrarySort.RECENTLY_ADDED, LibrarySort.FIRST_ADDED, LibrarySort.TITLE_ASC, LibrarySort.TITLE_DESC)
            ListProvider.MDBLIST -> listOf(
                LibrarySort.PROVIDER, LibrarySort.RECENTLY_ADDED, LibrarySort.FIRST_ADDED,
                LibrarySort.RELEASED_DESC, LibrarySort.RELEASED_ASC, LibrarySort.TITLE_ASC, LibrarySort.TITLE_DESC
            )
            ListProvider.TRAKT, ListProvider.SIMKL -> listOf(
                LibrarySort.PROVIDER, LibrarySort.RECENTLY_ADDED, LibrarySort.FIRST_ADDED, LibrarySort.TITLE_ASC, LibrarySort.TITLE_DESC
            )
        }

        /** [current] when [provider] offers it, else its first option. */
        fun sortFor(provider: ListProvider, current: LibrarySort): LibrarySort =
            sortOptions(provider).let { if (current in it) current else it.first() }
    }
}
