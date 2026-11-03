package com.fetch.desktop.collections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.sync.SyncClient
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.CollectionSource
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.withResolvedCatalogTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import java.util.UUID

/** One tab of a folder's page: a source, or "All" of them together. */
data class FolderTab(
    val label: String,
    val typeLabel: String,
    val source: CollectionSource?,
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val hasMore: Boolean = false,
    /** The last page loaded, counting from 1; 0 before the first. */
    val page: Int = 0,
    /** A website list of a PIN-protected profile: the profile slot whose PIN would show it. */
    val lockedSlot: Int? = null
) {
    val isAll: Boolean get() = source == null
}

/** Where a profile's collections are pulled from and pushed to: the sync server or the Fetch website. */
interface CollectionsRemote {
    /** The profile's collections there, or null when it has none yet. Throws when it can't be read. */
    suspend fun pull(): JsonArray?

    /** Replaces the profile's collections there. */
    suspend fun push(collections: JsonArray)
}

/** A folder's page as it's showing. */
data class FolderView(val collection: Collection, val folder: CollectionFolder, val tabs: List<FolderTab>, val selected: Int = 0) {
    val hasAllTab: Boolean get() = tabs.firstOrNull()?.isAll == true
}

/**
 * The profile's collections (the TV's Collections: rows of folders on Home, each folder a page of
 * sources), saved per profile in the TV's JSON and synced with the sync server (or, while paired,
 * the Fetch website) as the TV syncs them; and the folder page that's open.
 */
class CollectionsState(
    private val scope: CoroutineScope,
    private val store: CollectionsStore,
    private val resolver: CollectionSourceResolver,
    private val addons: () -> List<Addon>,
    /** The sync server, while signed in. */
    private val remote: () -> SyncClient? = { null },
    /** The server's profile number for this profile. */
    private val profileId: Int = 1,
    private val onMessage: (String) -> Unit = {},
    private val pushDelayMs: Long = 500,
    /** The Fetch website, while paired; it takes the place of the sync server, as on TV. */
    private val website: () -> CollectionsRemote? = { null }
) {
    var collections: List<Collection> by mutableStateOf(store.load())
        private set

    /** Pinned collections, which lead Home. */
    val pinned: List<Collection> get() = collections.filter { it.pinToTop && it.folders.isNotEmpty() }

    /** The rest, after Home's catalog rows. */
    val unpinned: List<Collection> get() = collections.filter { !it.pinToTop && it.folders.isNotEmpty() }

    fun newId(): String = UUID.randomUUID().toString()

    fun find(id: String): Collection? = collections.firstOrNull { it.id == id }

    /** Adds [collection], or replaces the one with its id. Catalog types are matched to the installed addons, as on TV. */
    fun save(collection: Collection) {
        val resolved = collection.withResolvedCatalogTypes(addons())
        val index = collections.indexOfFirst { it.id == resolved.id }
        commit(if (index >= 0) collections.toMutableList().also { it[index] = resolved } else collections + resolved)
    }

    fun delete(id: String) = commit(collections.filterNot { it.id == id })

    fun move(id: String, delta: Int) {
        val from = collections.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in collections.indices) return
        commit(collections.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun setPinned(id: String, pinned: Boolean) {
        val collection = find(id) ?: return
        save(collection.copy(pinToTop = pinned))
    }

    fun exportJson(): String = CollectionsCodec.encode(collections)

    /**
     * Imports pasted JSON (the TV's export): [replace] swaps every collection for them, otherwise
     * they're added, replacing ones with the same id. Returns why it failed, or null.
     */
    fun import(text: String, replace: Boolean): String? {
        CollectionsCodec.validate(text)?.let { return it }
        val imported = CollectionsCodec.parse(text)
        val merged = if (replace) imported else {
            val ids = imported.map { it.id }.toSet()
            collections.filterNot { it.id in ids } + imported
        }
        commit(merged)
        onMessage("Imported ${imported.size} collection${if (imported.size == 1) "" else "s"}")
        return null
    }

    /** A local list was renamed: the sources showing it take its new name, as on TV. */
    fun renameLocalList(listId: String, name: String) {
        renameLocalListSources(collections, listId, name)?.let(::commit)
    }

    private fun commit(list: List<Collection>) {
        collections = list
        runCatching { store.save(list) }.onFailure { onMessage("Couldn't save collections: ${it.message}") }
        queuePush()
        refreshOpenFolder()
    }

    // --- Sync ---

    private var pushJob: Job? = null

    /** Pushes the whole list shortly after the last change, as the TV's `triggerPush` does. */
    private fun queuePush() {
        val target = website() ?: remote()?.let(::serverRemote) ?: return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(pushDelayMs)
            runCatching { target.push(CollectionsCodec.encodeElement(collections) as JsonArray) }
        }
    }

    private fun serverRemote(client: SyncClient): CollectionsRemote = object : CollectionsRemote {
        override suspend fun pull(): JsonArray? = client.pullCollections(profileId)
        override suspend fun push(collections: JsonArray) {
            client.pushCollections(profileId, collections)
        }
    }

    /** Pulls the server's collections for this profile and merges them (see [CollectionsSyncMerge]). */
    suspend fun sync(client: SyncClient) {
        sync(serverRemote(client))
    }

    /** Pulls [remote]'s collections for this profile and merges them (see [CollectionsSyncMerge]). */
    suspend fun sync(remote: CollectionsRemote) {
        val pulled = remote.pull()
        val merge = CollectionsSyncMerge.merge(collections, pulled?.let { CollectionsCodec.parse(it) })
        if (merge.changed) {
            collections = merge.local
            runCatching { store.save(merge.local) }
            refreshOpenFolder()
        }
        if (merge.push) remote.push(CollectionsCodec.encodeElement(merge.local) as JsonArray)
    }

    /** Loads the open folder again, after its locked website profile was unlocked. */
    fun reloadFolder() {
        val view = folder ?: return
        folder = null
        openFolder(view.collection.id, view.folder.id)
    }

    // --- The folder page ---

    var folder: FolderView? by mutableStateOf(null)
        private set
    private var folderToken = Any()

    fun openFolder(collectionId: String, folderId: String) {
        val collection = find(collectionId)
        val target = collection?.folders?.firstOrNull { it.id == folderId }
        if (collection == null || target == null) {
            folder = null
            return
        }
        if (folder?.collection?.id == collectionId && folder?.folder == target) return
        val token = Any().also { folderToken = it }
        val installed = addons()
        val sourceTabs = target.sources.map { source ->
            val (label, type) = CollectionSourceResolver.label(source, installed)
            FolderTab(label, type, source)
        }
        // "All" leads when the collection shows it and the folder has two sources or more.
        val showAll = collection.showAllTab && target.sources.size >= 2
        val tabs = if (showAll) listOf(FolderTab("All", "Combined", null)) + sourceTabs else sourceTabs
        folder = FolderView(collection, target, tabs)
        tabs.indices.filter { !tabs[it].isAll }.forEach { load(token, it) }
    }

    fun selectTab(index: Int) {
        val view = folder ?: return
        if (index in view.tabs.indices) folder = view.copy(selected = index)
    }

    /** The next page of tab [index]; "All" asks every source that has more. */
    fun loadMore(index: Int) {
        val view = folder ?: return
        val tab = view.tabs.getOrNull(index) ?: return
        if (tab.isAll) {
            view.tabs.indices.filter { !view.tabs[it].isAll && view.tabs[it].hasMore && !view.tabs[it].loading }.forEach { load(folderToken, it) }
        } else if (tab.hasMore && !tab.loading) {
            load(folderToken, index)
        }
    }

    /** Reopens the open folder when its collection changed (an edit here or a sync). */
    private fun refreshOpenFolder() {
        val view = folder ?: return
        val current = find(view.collection.id)?.folders?.firstOrNull { it.id == view.folder.id }
        if (current == null) {
            folder = null
        } else if (current != view.folder || find(view.collection.id) != view.collection) {
            folder = null
            openFolder(view.collection.id, view.folder.id)
        }
    }

    private fun load(token: Any, index: Int) {
        val tab = folder?.tabs?.getOrNull(index) ?: return
        val source = tab.source ?: return
        val page = tab.page + 1
        updateTab(token, index) { it.copy(loading = true, error = null, lockedSlot = null) }
        scope.launch {
            val result = try {
                Result.success(resolver.load(source, page))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            updateTab(token, index) { current ->
                result.fold(
                    onSuccess = { loaded ->
                        val seen = current.items.mapTo(HashSet()) { "${it.apiType}:${it.id}" }
                        val fresh = loaded.items.filter { seen.add("${it.apiType}:${it.id}") }
                        current.copy(
                            items = current.items + fresh,
                            loading = false,
                            page = page,
                            // A page with nothing new ends the source.
                            hasMore = loaded.hasMore && (fresh.isNotEmpty() || page == 1)
                        )
                    },
                    onFailure = {
                        current.copy(
                            loading = false,
                            error = it.message ?: "Couldn't load",
                            hasMore = false,
                            lockedSlot = (it as? com.fetch.desktop.website.WebsiteProfileLockedException)?.slot
                        )
                    }
                )
            }
        }
    }

    private fun updateTab(token: Any, index: Int, change: (FolderTab) -> FolderTab) {
        if (token !== folderToken) return
        val view = folder ?: return
        val tabs = view.tabs.toMutableList()
        if (index !in tabs.indices) return
        tabs[index] = change(tabs[index])
        if (view.hasAllTab) tabs[0] = allTab(tabs[0], tabs.drop(1))
        folder = view.copy(tabs = tabs)
    }

    private fun allTab(all: FolderTab, sources: List<FolderTab>): FolderTab {
        val loaded = sources.filter { it.page > 0 }
        return all.copy(
            items = CollectionSourceResolver.roundRobin(loaded.map { it.items }),
            loading = sources.any { it.loading },
            hasMore = sources.any { it.hasMore },
            error = if (sources.isNotEmpty() && sources.all { it.error != null }) sources.first().error else null,
            lockedSlot = sources.firstNotNullOfOrNull { it.lockedSlot },
            page = if (loaded.isEmpty()) 0 else 1
        )
    }
}
