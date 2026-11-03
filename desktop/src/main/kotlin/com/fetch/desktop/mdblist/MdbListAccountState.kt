package com.fetch.desktop.mdblist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.LibraryList
import com.fetch.desktop.ListPrivacy
import com.fetch.desktop.ListProvider

import com.fetch.desktop.data.LibraryEntry
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

fun mdbListAccountFile(dir: File) = MdbListAccountStore(File(dir, "mdblist.json"))

/**
 * This profile's MDBList account, as the TV's `MdbListTrackingLibraryProvider`: connected with
 * the device sign-in (when this build has a client id) or an API key; its Watchlist, static
 * lists, dynamic lists, external lists and liked lists as Library tabs; titles added to and
 * removed from the Watchlist and static lists; static lists created, renamed and deleted. Lists
 * can be hidden from the Library and Manage lists, except the Watchlist.
 */
class MdbListAccountState(
    private val scope: CoroutineScope,
    private val store: MdbListAccountStore,
    private val client: MdbListAccountClient,
    private val onMessage: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    var file: MdbListAccountFile by mutableStateOf(store.load())
        private set
    val account: MdbListAccount? get() = file.account
    val connected: Boolean get() = account != null
    val deviceSignInAvailable: Boolean get() = client.deviceSignInAvailable

    var pending: MdbListDeviceCode? by mutableStateOf(null)
        private set
    /** Every list in the account, the Watchlist first; null until loaded. */
    var lists: List<MdbListList>? by mutableStateOf(null)
        private set
    var items: Map<String, List<MdbListItem>> by mutableStateOf(emptyMap())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var lastSyncedAt: Long? by mutableStateOf(null)
        private set
    var connecting by mutableStateOf(false)
        private set

    private var signInJob: Job? = null
    private val authMutex = Mutex()

    // --- Connecting ---

    /** The TV's sign-in: a code to approve on mdblist.com, polled until it's entered. */
    fun signIn(onSignedIn: () -> Unit = {}) {
        if (!deviceSignInAvailable) return
        signInJob?.cancel()
        signInJob = scope.launch {
            try {
                val code = client.startDeviceAuth()
                pending = code
                var interval = code.intervalSec.coerceAtLeast(1) * 1000L
                val deadline = clock() + code.expiresInSec * 1000
                while (clock() < deadline) {
                    delay(interval)
                    when (val poll = client.pollDeviceAuth(code.deviceCode, clock())) {
                        MdbListPoll.Pending -> Unit
                        MdbListPoll.SlowDown -> interval += 5_000
                        MdbListPoll.Expired -> return@launch fail("The code expired. Try again.")
                        MdbListPoll.Denied -> return@launch fail("The connection was declined.")
                        is MdbListPoll.Authorized -> {
                            val user = client.user(MdbListAuth.Bearer(poll.accessToken))
                            save(file.copy(account = MdbListAccount(user.id, user.name, null, poll.accessToken, poll.refreshToken, poll.expiresAt)))
                            pending = null
                            onMessage("Connected to MDBList" + (user.name?.let { " as $it" } ?: ""))
                            onSignedIn()
                            return@launch
                        }
                    }
                }
                fail("The code expired. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Couldn't connect MDBList: ${e.message}")
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        pending = null
    }

    /** Connects the account with an API key from mdblist.com › Preferences, checked first. */
    fun connectWithKey(key: String, onSignedIn: () -> Unit = {}) {
        val clean = key.trim().takeIf { it.isNotEmpty() } ?: return
        if (connecting) return
        scope.launch {
            connecting = true
            try {
                val user = client.user(MdbListAuth.ApiKey(clean))
                save(file.copy(account = MdbListAccount(user.id, user.name, apiKey = clean)))
                onMessage("Connected to MDBList" + (user.name?.let { " as $it" } ?: ""))
                onSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(if ((e as? MdbListException)?.unauthorized == true) "MDBList didn't accept this API key" else "Couldn't reach MDBList: ${e.message}")
            } finally {
                connecting = false
            }
        }
    }

    fun signOut() {
        val previous = account
        save(file.copy(account = null))
        lists = null
        items = emptyMap()
        if (previous != null && !previous.usesApiKey) scope.launch { client.revoke(previous) }
        onMessage("Disconnected MDBList")
    }

    private fun fail(message: String) {
        pending = null
        onMessage(message)
    }

    private fun save(updated: MdbListAccountFile) {
        file = updated
        runCatching { store.save(updated) }.onFailure { onMessage("Couldn't save your MDBList account: ${it.message}") }
    }

    // --- Requests ---

    /** How to sign a request now: refreshes an OAuth token about to expire. Null when not connected. */
    suspend fun auth(): MdbListAuth? = authMutex.withLock {
        val current = account ?: return null
        if (current.usesApiKey || current.expiresAt - clock() > 60_000L) return current.auth()
        refreshTokens(current)?.auth()
    }

    private suspend fun refreshTokens(current: MdbListAccount): MdbListAccount? = try {
        client.refresh(current, clock()).also { save(file.copy(account = it)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: MdbListException) {
        if (e.unauthorized) expired()
        null
    }

    private fun expired() {
        save(file.copy(account = null))
        lists = null
        items = emptyMap()
        onMessage("Your MDBList connection has expired or been revoked. Connect it again in Settings › MDBList.")
    }

    /** Runs [block] signed in, refreshing the token once on a 401. Null when not connected. */
    suspend fun <T> withAuth(block: suspend MdbListAccountClient.(MdbListAuth) -> T): T? {
        val auth = auth() ?: return null
        return try {
            client.block(auth)
        } catch (e: MdbListException) {
            if (!e.unauthorized) throw e
            val current = account
            if (current == null || current.usesApiKey) {
                if (current != null) expired()
                return null
            }
            val refreshed = authMutex.withLock { refreshTokens(current) }?.auth() ?: return null
            client.block(refreshed)
        }
    }

    // --- Library ---

    /**
     * Loads the lists and their items, as the TV's `MdbListLibraryRemote.synchronize`. Items of
     * a list whose update time hasn't changed are kept; hidden lists aren't downloaded. External
     * and liked lists are extras: if MDBList can't serve them, the copies already here stay.
     */
    suspend fun refresh() {
        orders.clear()
        if (!connected || refreshing) return
        refreshing = true
        try {
            withAuth { auth ->
                val accountId = account?.userId
                val previous = lists.orEmpty().associateBy { it.key }
                val owned = userLists(auth, accountId)
                val external = optional(MdbListListKind.EXTERNAL) { externalLists(auth) }
                val liked = optional(MdbListListKind.LIKED) { likedLists(auth, accountId) }
                val all = listOf(MdbListList.WATCHLIST) + owned + external + liked
                val loaded = linkedMapOf<String, List<MdbListItem>>()
                for (list in all) {
                    if (list.key in file.hiddenLists) continue
                    val old = previous[list.key]
                    val cached = items[list.key]
                    loaded[list.key] = if (cached != null && old?.updatedAt != null && old.updatedAt == list.updatedAt) cached
                    else items(list, auth)
                }
                lists = all
                items = loaded
                lastSyncedAt = clock()
                // Forget hidden lists MDBList no longer has.
                val keys = all.map { it.key }.toSet()
                if (!keys.containsAll(file.hiddenLists)) save(file.copy(hiddenLists = file.hiddenLists intersect keys))
            }
        } finally {
            refreshing = false
        }
    }

    private suspend fun optional(kind: MdbListListKind, fetch: suspend () -> List<MdbListList>): List<MdbListList> = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if ((e as? MdbListException)?.retryAtEpochMs != null || (e as? MdbListException)?.unauthorized == true) throw e
        lists.orEmpty().filter { it.kind == kind }
    }

    /** The lists shown in the Library and Manage lists. */
    private val orders = mutableMapOf<String, List<String>>()

    /**
     * [listKey]'s items in MDBList's own order by `added` or `released` (oldest first), as the TV's
     * `MdbListLibrarySorter` reads them: content ids, cached until the lists refresh. MDBList
     * gives the newest first for `order=asc`, so the oldest are asked for with `desc`.
     */
    suspend fun order(listKey: String, sort: String): List<String>? {
        val cacheKey = "$listKey|$sort"
        orders[cacheKey]?.let { return it }
        val list = lists.orEmpty().firstOrNull { it.key == listKey } ?: return null
        val items = withAuth { auth -> items(list, auth, sort, "desc") } ?: return null
        val dated = items.mapNotNull { it.year?.toString() }
        val oldestFirst = if (sort == "released" && dated.isNotEmpty() && dated.first() > dated.last()) items.reversed() else items
        return oldestFirst.mapNotNull { it.contentId }.also { orders[cacheKey] = it }
    }

    fun visibleLists(): List<MdbListList> = lists.orEmpty().filter { it.key !in file.hiddenLists }

    /** Every list but the Watchlist, with whether it's shown (Settings › MDBList › Library lists). */
    fun listOptions(): List<Pair<MdbListList, Boolean>> =
        lists.orEmpty().filter { it.kind != MdbListListKind.WATCHLIST }.map { it to (it.key !in file.hiddenLists) }

    /** Shows or hides a list; a list shown again has its items loaded. */
    fun setListVisible(key: String, visible: Boolean) {
        if (key == MdbListList.WATCHLIST_KEY) return
        save(file.copy(hiddenLists = if (visible) file.hiddenLists - key else file.hiddenLists + key))
        if (!visible) {
            items = items - key
            return
        }
        val list = lists.orEmpty().firstOrNull { it.key == key } ?: return
        scope.launch {
            runCatching { withAuth { auth -> items(list, auth) } }
                .onSuccess { loaded -> if (loaded != null) items = items + (key to loaded) }
                .onFailure { if (it is CancellationException) throw it else onMessage("Couldn't load ${list.name}: ${it.message}") }
        }
    }

    /** The lists as the Library's tabs and Manage lists' destinations. */
    fun libraryLists(): List<LibraryList> = if (!connected) emptyList() else
        (lists ?: listOf(MdbListList.WATCHLIST)).filter { it.key !in file.hiddenLists }.map { list ->
            LibraryList(
                key = list.key,
                provider = ListProvider.MDBLIST,
                title = list.name,
                items = items[list.key].orEmpty().mapNotNull { it.toLibraryEntry() },
                description = list.description,
                privacy = if (list.kind == MdbListListKind.STATIC) (if (list.private) ListPrivacy.PRIVATE else ListPrivacy.PUBLIC) else null,
                editable = list.kind == MdbListListKind.STATIC,
                membershipDestination = list.writable,
                types = when (list.mediaType) {
                    "movie" -> setOf("movie")
                    "show" -> setOf("series")
                    else -> null
                }
            )
        }

    /** Puts [preview] on or off the list [key], keeping the list here in step. */
    suspend fun setMember(key: String, preview: MetaPreview, on: Boolean) {
        val list = lists.orEmpty().firstOrNull { it.key == key } ?: (if (key == MdbListList.WATCHLIST_KEY) MdbListList.WATCHLIST else null)
            ?: throw MdbListException(message = "This list is no longer on MDBList")
        val target = mdbListItemOf(preview) ?: throw MdbListException(message = "MDBList needs an IMDb or TMDB id")
        val current = items[key].orEmpty()
        // Remove what MDBList has (it may know more ids); add what this title is.
        val existing = current.firstOrNull(target::matches)
        if ((existing != null) == on) return
        withAuth { auth -> setMember(list, existing ?: target, on, auth) } ?: return
        val stamped = target.copy(listedAt = clock(), rank = (current.mapNotNull { it.rank }.maxOrNull() ?: 0) + 1)
        items = items + (key to (current.filterNot(target::matches) + listOfNotNull(stamped.takeIf { on })))
    }

    suspend fun createList(name: String, privacy: ListPrivacy) {
        val created = withAuth { auth -> createList(name, privacy != ListPrivacy.PUBLIC, auth) } ?: return
        lists = lists.orEmpty().ifEmpty { listOf(MdbListList.WATCHLIST) }.filterNot { it.key == created.key } + created
        items = items + (created.key to emptyList())
    }

    suspend fun updateList(key: String, name: String, privacy: ListPrivacy) {
        val list = staticList(key)
        withAuth { auth -> updateList(list.id, name, privacy != ListPrivacy.PUBLIC, auth) } ?: return
        lists = lists.orEmpty().map { if (it.key == key) it.copy(name = name.trim(), private = privacy != ListPrivacy.PUBLIC) else it }
    }

    suspend fun deleteList(key: String) {
        val list = staticList(key)
        withAuth { auth -> deleteList(list.id, auth) } ?: return
        lists = lists.orEmpty().filterNot { it.key == key }
        items = items - key
    }

    private fun staticList(key: String): MdbListList = lists.orEmpty().firstOrNull { it.key == key && it.kind == MdbListListKind.STATIC }
        ?: throw MdbListException(message = "This static list is no longer on MDBList")

    companion object {
        private val IMDB = Regex("tt\\d+")

        /** [preview] as MDBList knows it: by IMDb id, else a `tmdb:` id. Null without either. */
        fun mdbListItemOf(preview: MetaPreview): MdbListItem? {
            val imdb = preview.imdbId?.let { IMDB.find(it)?.value } ?: IMDB.matchEntire(preview.id.substringBefore(':'))?.value
            val tmdb = preview.id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')?.toLongOrNull()
            if (imdb == null && tmdb == null) return null
            return MdbListItem(
                type = if (preview.type == ContentType.SERIES) "show" else "movie",
                imdb = imdb,
                tmdb = tmdb,
                title = preview.name,
                year = preview.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() },
                poster = preview.poster,
                backdrop = preview.background,
                description = preview.description,
                genres = preview.genres
            )
        }
    }
}

/** A list item as a Library title. Items with neither an IMDb nor a TMDB id are left out. */
fun MdbListItem.toLibraryEntry(): LibraryEntry? {
    val id = contentId ?: return null
    return LibraryEntry(
        id = id,
        type = if (type == "show") "series" else "movie",
        name = title ?: id,
        poster = poster ?: imdb?.let { "https://images.metahub.space/poster/small/$it/img" },
        background = backdrop ?: imdb?.let { "https://images.metahub.space/background/medium/$it/img" },
        description = description,
        releaseInfo = year?.toString(),
        genres = genres,
        addedAt = listedAt
    )
}
