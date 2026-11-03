package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.TrackingState.Companion.toLibraryEntry
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.tracking.RemoteList
import com.fetch.desktop.tracking.TrackedItem
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** A list of your own on this computer (the TV's local lists), saved in `lists.json`. */
@Serializable
data class LocalList(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val items: List<LibraryEntry> = emptyList()
)

@Serializable
data class LocalListsFile(val lists: List<LocalList> = emptyList())

fun localListsFile(dir: File) = JsonFile(File(dir, "lists.json"), LocalListsFile.serializer()) { LocalListsFile() }

/** Where a list lives: the Library's sources. */
enum class ListProvider(val label: String) { LOCAL("Local"), TRAKT("Trakt"), SIMKL("Simkl"), MDBLIST("MDBList") }

/** Trakt's list privacy choices. */
enum class ListPrivacy(val apiValue: String, val label: String) {
    PRIVATE("private", "Private"),
    LINK("link", "Anyone with the link"),
    FRIENDS("friends", "Friends"),
    PUBLIC("public", "Public");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.apiValue == value } ?: PRIVATE
    }
}

/**
 * One list the Library shows as a tab and "Manage lists" offers, as the TV's `LibraryListTab`.
 * [key]: `local:library`, `local:<id>`, `trakt:watchlist`, `trakt:list:<id>`,
 * `simkl:status:<status>` or `mdblist:…` (see [com.fetch.desktop.mdblist.MdbListList.key]). Lists in the same [selectionGroup] hold a title at most once (Simkl's
 * statuses); a list that isn't a [membershipDestination] can't be picked by hand (Completed).
 */
data class LibraryList(
    val key: String,
    val provider: ListProvider,
    val title: String,
    val items: List<LibraryEntry>,
    val description: String? = null,
    val privacy: ListPrivacy? = null,
    /** Personal lists: renamed, deleted and reordered from Manage lists. */
    val editable: Boolean = false,
    val selectionGroup: String? = null,
    val membershipDestination: Boolean = true,
    /** Content types it takes (Simkl: Watching and On Hold are for shows); null takes any. */
    val types: Set<String>? = null
) {
    fun accepts(preview: MetaPreview): Boolean = types == null || preview.apiType in types
}

/**
 * Your lists beyond the local library: lists of your own on this computer, Trakt's watchlist and
 * personal lists, and Simkl's status lists, as the TV Library has them. The local library itself
 * stays with [AppState]; [libraryItems] and [setInLibrary] reach it.
 */
class ListsState(
    private val scope: CoroutineScope,
    private val tracking: TrackingState,
    private val store: JsonFile<LocalListsFile>,
    private val libraryItems: () -> List<LibraryEntry>,
    private val setInLibrary: (MetaPreview, Boolean) -> Unit,
    private val onMessage: (String) -> Unit,
    /** The MDBList account's lists, when this profile has one connected. */
    private val mdblist: com.fetch.desktop.mdblist.MdbListAccountState? = null
) {
    var local: List<LocalList> by mutableStateOf(store.load().lists)
        private set
    var traktWatchlist by mutableStateOf<List<LibraryEntry>?>(null)
        private set
    var traktLists by mutableStateOf<List<RemoteList>>(emptyList())
        private set
    var traktItems by mutableStateOf<Map<Long, List<LibraryEntry>>>(emptyMap())
        private set
    var simklStatus by mutableStateOf<Map<String, List<LibraryEntry>>?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    /** A list operation is running (Manage lists disables its buttons). */
    var busy by mutableStateOf(false)
        private set

    /** The providers with lists to show: Local always, Trakt, Simkl and MDBList when connected. */
    val providers: List<ListProvider>
        get() = listOf(ListProvider.LOCAL) + tracking.connected.map {
            if (it == TrackingService.TRAKT) ListProvider.TRAKT else ListProvider.SIMKL
        } + listOfNotNull(ListProvider.MDBLIST.takeIf { mdblist?.connected == true })

    /** [provider]'s lists, in the TV's order. */
    fun lists(provider: ListProvider): List<LibraryList> = when (provider) {
        ListProvider.LOCAL -> listOf(LibraryList(LOCAL_LIBRARY, provider, "Library", libraryItems())) +
            local.map { LibraryList("local:${it.id}", provider, it.name, it.items, it.description, editable = true) }
        ListProvider.TRAKT -> if (tracking.account(TrackingService.TRAKT) == null) emptyList() else
            listOf(LibraryList(TRAKT_WATCHLIST, provider, "Watchlist", traktWatchlist.orEmpty())) +
                traktLists.map { l ->
                    LibraryList("trakt:list:${l.id}", provider, l.name, traktItems[l.id].orEmpty(), l.description, ListPrivacy.from(l.privacy), editable = true)
                }
        ListProvider.SIMKL -> if (tracking.account(TrackingService.SIMKL) == null) emptyList() else
            SIMKL_STATUSES.map { (status, title, types) ->
                LibraryList(
                    "simkl:status:$status", provider, title, simklStatus?.get(status).orEmpty(),
                    selectionGroup = SIMKL_GROUP,
                    membershipDestination = status != "completed",
                    types = types
                )
            }
        ListProvider.MDBLIST -> mdblist?.libraryLists().orEmpty()
    }

    /** Every list a title can go on, for Manage lists. */
    fun destinations(preview: MetaPreview): List<LibraryList> =
        providers.flatMap { lists(it) }.filter { it.membershipDestination && it.accepts(preview) }

    fun isMember(list: LibraryList, id: String): Boolean = list.items.any { it.id == id }

    /** Pulls Trakt's and Simkl's lists. Called with every tracking sync. */
    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            try {
                coroutineScope {
                    val trakt = async { runSafely { refreshTrakt() } }
                    val simkl = async { runSafely { refreshSimkl() } }
                    val mdb = async { runSafely { mdblist?.refresh() } }
                    listOf(trakt, simkl, mdb).awaitAll()
                }
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun refreshTrakt() {
        if (tracking.account(TrackingService.TRAKT) == null) {
            traktWatchlist = null; traktLists = emptyList(); traktItems = emptyMap()
            return
        }
        tracking.withTrakt { tokens ->
            val watchlist = watchlist(tokens).map { it.toLibraryEntry() }
            val lists = lists(tokens)
            val items = coroutineScope {
                lists.map { l -> async { l.id to listItems(tokens, l.id).map { it.toLibraryEntry() } } }.awaitAll().toMap()
            }
            traktWatchlist = watchlist
            traktLists = lists
            traktItems = items
        }
    }

    private suspend fun refreshSimkl() {
        if (tracking.account(TrackingService.SIMKL) == null) {
            simklStatus = null
            return
        }
        tracking.withSimkl { tokens -> simklStatus = statusLists(tokens).mapValues { (_, v) -> v.map { it.toLibraryEntry() } } }
    }

    // --- Membership ---

    /**
     * Puts [preview] on or off each list in [changes] (by key), as the TV's Manage lists saves:
     * local ones straight away, then Trakt and Simkl, keeping the lists here in step.
     */
    fun apply(preview: MetaPreview, changes: Map<String, Boolean>) {
        if (changes.isEmpty()) return
        val entry = LibraryEntry.from(preview, addedAt = System.currentTimeMillis())
        val item = trackedItem(preview)
        changes.forEach { (key, on) ->
            when {
                key == LOCAL_LIBRARY -> setInLibrary(preview, on)
                key.startsWith("local:") -> updateLocal(key.removePrefix("local:")) { list ->
                    list.copy(items = if (on) (list.items.filterNot { it.id == entry.id } + entry) else list.items.filterNot { it.id == entry.id })
                }
            }
        }
        val mdbChanges = changes.filterKeys { it.startsWith("mdblist:") }
        if (mdbChanges.isNotEmpty() && mdblist != null) remote(null) {
            mdbChanges.forEach { (key, on) -> mdblist.setMember(key, preview, on) }
            if (changes.keys.all { it.startsWith("mdblist:") || it.startsWith("local:") }) onMessage("Lists updated")
        }
        val remote = changes.filterKeys { !it.startsWith("local:") && !it.startsWith("mdblist:") }
        if (remote.isEmpty()) {
            if (mdbChanges.isNotEmpty()) return
            onMessage("Lists updated")
            return
        }
        if (item == null) {
            onMessage("Trakt and Simkl only take titles with an IMDb id")
            return
        }
        scope.launch {
            busy = true
            try {
                remote.forEach { (key, on) ->
                    when {
                        key == TRAKT_WATCHLIST -> tracking.withTrakt { setWatchlisted(it, item, on) }?.also {
                            traktWatchlist = traktWatchlist.orEmpty().filterNot { e -> e.id == entry.id } + listOfNotNull(entry.takeIf { on })
                        }
                        key.startsWith("trakt:list:") -> {
                            val id = key.removePrefix("trakt:list:").toLong()
                            tracking.withTrakt { setListed(it, id, item, on) }?.also {
                                traktItems = traktItems + (id to (traktItems[id].orEmpty().filterNot { e -> e.id == entry.id } + listOfNotNull(entry.takeIf { on })))
                            }
                        }
                        key.startsWith("simkl:status:") -> {
                            val status = key.removePrefix("simkl:status:")
                            tracking.withSimkl { if (on) setStatus(it, item, status) else setWatchlisted(it, item, false) }?.also {
                                val current = simklStatus.orEmpty().mapValues { (_, v) -> v.filterNot { e -> e.id == entry.id } }
                                simklStatus = if (on) current + (status to (current[status].orEmpty() + entry)) else current
                            }
                        }
                    }
                }
                onMessage("Lists updated")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Couldn't update your lists: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    // --- Editing lists ---

    fun create(provider: ListProvider, name: String, description: String?, privacy: ListPrivacy) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        when (provider) {
            ListProvider.LOCAL -> {
                val now = System.currentTimeMillis()
                saveLocal(local + LocalList(UUID.randomUUID().toString(), clean, description?.trim()?.ifEmpty { null }, now, now))
                onMessage("Created $clean")
            }
            ListProvider.TRAKT -> remote("Created $clean") {
                tracking.withTrakt { createList(it, clean, description?.trim(), privacy.apiValue) }?.let { traktLists = traktLists + it }
            }
            ListProvider.SIMKL -> Unit
            ListProvider.MDBLIST -> remote("Created $clean") { mdblist?.createList(clean, privacy) }
        }
    }

    fun update(list: LibraryList, name: String, description: String?, privacy: ListPrivacy) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        when (list.provider) {
            ListProvider.LOCAL -> updateLocal(list.key.removePrefix("local:")) {
                it.copy(name = clean, description = description?.trim()?.ifEmpty { null })
            }
            ListProvider.TRAKT -> remote("Saved $clean") {
                val id = list.key.removePrefix("trakt:list:").toLong()
                tracking.withTrakt { updateList(it, id, clean, description?.trim(), privacy.apiValue) }?.let {
                    traktLists = traktLists.map { l -> if (l.id == id) l.copy(name = clean, description = description?.trim(), privacy = privacy.apiValue) else l }
                }
            }
            ListProvider.SIMKL -> Unit
            ListProvider.MDBLIST -> remote("Saved $clean") { mdblist?.updateList(list.key, clean, privacy) }
        }
    }

    fun delete(list: LibraryList) {
        when (list.provider) {
            ListProvider.LOCAL -> {
                saveLocal(local.filterNot { "local:${it.id}" == list.key })
                onMessage("Deleted ${list.title}")
            }
            ListProvider.TRAKT -> remote("Deleted ${list.title}") {
                val id = list.key.removePrefix("trakt:list:").toLong()
                tracking.withTrakt { deleteList(it, id) }?.let {
                    traktLists = traktLists.filterNot { l -> l.id == id }
                    traktItems = traktItems - id
                }
            }
            ListProvider.SIMKL -> Unit
            ListProvider.MDBLIST -> remote("Deleted ${list.title}") { mdblist?.deleteList(list.key) }
        }
    }

    /** Moves a personal list up (-1) or down (+1) among its provider's. */
    fun move(list: LibraryList, delta: Int) {
        when (list.provider) {
            ListProvider.LOCAL -> {
                val i = local.indexOfFirst { "local:${it.id}" == list.key }
                val j = i + delta
                if (i < 0 || j !in local.indices) return
                saveLocal(local.toMutableList().apply { add(j, removeAt(i)) })
            }
            ListProvider.TRAKT -> {
                val id = list.key.removePrefix("trakt:list:").toLong()
                val i = traktLists.indexOfFirst { it.id == id }
                val j = i + delta
                if (i < 0 || j !in traktLists.indices) return
                val reordered = traktLists.toMutableList().apply { add(j, removeAt(i)) }
                traktLists = reordered
                remote(null) { tracking.withTrakt { reorderLists(it, reordered.map { l -> l.id }) } }
            }
            // MDBList keeps its own list order (TV: no reordering either).
            ListProvider.SIMKL, ListProvider.MDBLIST -> Unit
        }
    }

    private fun remote(success: String?, action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                action()
                success?.let(onMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Couldn't update your lists: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun updateLocal(id: String, change: (LocalList) -> LocalList) {
        saveLocal(local.map { if (it.id == id) change(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }

    private fun saveLocal(lists: List<LocalList>) {
        local = lists
        runCatching { store.save(LocalListsFile(lists)) }.onFailure { onMessage("Couldn't save your lists: ${it.message}") }
    }

    private suspend fun runSafely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onMessage("Couldn't load your lists: ${e.message}")
        }
    }

    companion object {
        const val LOCAL_LIBRARY = "local:library"
        const val TRAKT_WATCHLIST = "trakt:watchlist"
        const val SIMKL_GROUP = "simkl-status"

        /** Simkl's lists as the TV names them, with what each takes. */
        val SIMKL_STATUSES = listOf(
            Triple("watching", "Watching", setOf("series")),
            Triple("plantowatch", "Plan to Watch", null),
            Triple("hold", "On Hold", setOf("series")),
            Triple("completed", "Completed", null),
            Triple("dropped", "Dropped", null)
        )

        fun trackedItem(preview: MetaPreview): TrackedItem? {
            val imdb = (preview.imdbId ?: preview.id).substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
            return TrackedItem(
                imdb,
                if (preview.type == ContentType.SERIES) TrackedItem.TYPE_SERIES else TrackedItem.TYPE_MOVIE,
                title = preview.name,
                year = yearOf(preview.releaseInfo)
            )
        }

        /**
         * The changes Manage lists saves: lists whose box changed, with a single-choice group
         * (Simkl) sending only the newly picked list, or the old one turned off when none is.
         */
        fun changes(lists: List<LibraryList>, before: Set<String>, after: Set<String>): Map<String, Boolean> {
            val result = linkedMapOf<String, Boolean>()
            lists.groupBy { it.selectionGroup }.forEach { (group, members) ->
                val keys = members.map { it.key }
                if (group == null) {
                    keys.forEach { k -> if ((k in before) != (k in after)) result[k] = k in after }
                } else {
                    val was = keys.firstOrNull { it in before }
                    val now = keys.firstOrNull { it in after }
                    when {
                        now != null && now != was -> result[now] = true
                        now == null && was != null -> result[was] = false
                    }
                }
            }
            return result
        }
    }
}
