package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.servers.MediaBrowserServerClient
import com.fetch.desktop.servers.MediaServerClient
import com.fetch.desktop.servers.PlexLink
import com.fetch.desktop.servers.PlexServerClient
import com.fetch.desktop.servers.ServerAccount
import com.fetch.desktop.servers.ServerAuthException
import com.fetch.desktop.servers.ServerKind
import com.fetch.desktop.servers.ServerLib
import com.fetch.desktop.servers.ServerStream
import com.fetch.desktop.servers.ServersStore
import com.fetch.desktop.servers.clientFor
import com.fetch.desktop.servers.toStream
import com.fetch.desktop.servers.ServerPageResult
import com.fetch.desktop.servers.ServerSort
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A home row from a media server library: "Recently added" in one library. */
data class ServerRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

/** A Plex sign-in waiting for the user to enter [link]'s code at plex.tv/link. */
data class PendingPlexLink(val link: PlexLink)

/**
 * Jellyfin, Emby and Plex servers, as on the TV app: their files become sources (ranked first
 * with "Prefer my own library"), their libraries become "Recently added" rows on Home, and what
 * you were watching there joins Continue Watching. Kept beside the profile's addons.
 */
class ServersState(
    private val scope: CoroutineScope,
    private val store: ServersStore,
    private val onMessage: (String) -> Unit = {},
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    val accounts = mutableStateListOf<ServerAccount>().apply { addAll(store.load()) }
    var signingIn by mutableStateOf(false)
        private set
    /** Why the last Jellyfin or Emby sign-in failed, shown in its dialog. */
    var signInError: String? by mutableStateOf(null)
        private set
    var plexLink: PendingPlexLink? by mutableStateOf(null)
        private set
    val rows = mutableStateListOf<ServerRow>()
    private var plexJob: Job? = null

    /** Streams handed out by [streams], by URL, so playback can report back to their server. */
    private val issued = mutableMapOf<String, ServerStream>()

    private val deviceId: String by lazy { deviceId() }
    private fun client(kind: ServerKind): MediaServerClient = clientFor(kind, http, deviceId)

    val enabled: List<ServerAccount> get() = accounts.filter { it.enabled }

    fun label(account: ServerAccount): String = "${account.kind.label} · ${account.name}"

    // --- Adding and removing ---

    /** Jellyfin or Emby: address, user name and password. */
    fun signIn(kind: ServerKind, address: String, user: String, password: String, onDone: (Boolean) -> Unit) {
        scope.launch {
            signingIn = true
            signInError = null
            try {
                val client = client(kind) as MediaBrowserServerClient
                val account = withContext(Dispatchers.IO) { client.signIn(address, user, password) }
                add(listOf(account))
                onMessage("Connected to ${account.name}")
                onDone(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signInError = when {
                    e is java.net.ConnectException || e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ->
                        "Couldn't reach a server at that address. Check it, and that the server is running."
                    else -> e.message ?: "Couldn't sign in"
                }
                onDone(false)
            } finally {
                signingIn = false
            }
        }
    }

    fun clearSignInError() {
        signInError = null
    }

    /** Plex: shows a code for plex.tv/link, then adds every server the account can reach. */
    fun startPlexLink() {
        plexJob?.cancel()
        plexJob = scope.launch {
            val client = client(ServerKind.PLEX) as PlexServerClient
            try {
                val link = withContext(Dispatchers.IO) { client.startLink() }
                plexLink = PendingPlexLink(link)
                while (System.currentTimeMillis() < link.expiresAtEpochMs) {
                    delay(2_000)
                    val token = withContext(Dispatchers.IO) { client.pollLink(link) } ?: continue
                    val servers = withContext(Dispatchers.IO) { client.servers(token) }
                    plexLink = null
                    if (servers.isEmpty()) {
                        onMessage("That Plex account has no servers Fetch can reach")
                    } else {
                        add(servers)
                        onMessage("Connected to ${servers.joinToString { it.name }}")
                    }
                    return@launch
                }
                plexLink = null
                onMessage("The Plex code expired. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                plexLink = null
                onMessage(e.message ?: "Couldn't reach Plex")
            }
        }
    }

    fun cancelPlexLink() {
        plexJob?.cancel()
        plexLink = null
    }

    private fun add(new: List<ServerAccount>) {
        // Signing in to a server again replaces it rather than listing it twice.
        new.forEach { account ->
            val index = accounts.indexOfFirst { it.kind == account.kind && it.serverId == account.serverId && it.userId == account.userId }
            if (index >= 0) accounts[index] = account.copy(id = accounts[index].id) else accounts.add(account)
        }
        persist()
        refreshRows()
    }

    fun remove(account: ServerAccount) {
        accounts.removeAll { it.id == account.id }
        persist()
        rows.removeAll { it.key.startsWith(account.id) }
        scope.launch { runCatching { withContext(Dispatchers.IO) { client(account.kind).signOut(account) } } }
    }

    fun setEnabled(account: ServerAccount, on: Boolean) = update(account.copy(enabled = on))

    fun setLibrarySelected(account: ServerAccount, lib: ServerLib, on: Boolean) =
        update(account.copy(libraries = account.libraries.map { if (it.id == lib.id) it.copy(selected = on) else it }))

    /** Fetches the server's libraries again, keeping which ones were picked. */
    fun refreshLibraries(account: ServerAccount) {
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { client(account.kind).libraries(account) }
                val picked = account.libraries.associate { it.id to it.selected }
                update(account.copy(libraries = fresh.map { it.copy(selected = picked[it.id] ?: true) }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Couldn't reach ${account.name}: ${e.message}")
            }
        }
    }

    private fun update(account: ServerAccount) {
        val index = accounts.indexOfFirst { it.id == account.id }
        if (index < 0) return
        accounts[index] = account
        persist()
        refreshRows()
    }

    private fun persist() {
        runCatching { store.save(accounts.toList()) }.onFailure { onMessage("Couldn't save the media servers: ${it.message}") }
    }

    // --- Sources ---

    /**
     * Your own copies of a title or episode on every enabled server, as streams the ranker puts
     * first. A server that is off or slow just adds nothing.
     */
    suspend fun streams(type: String, videoId: String, title: String?, year: Int?): List<Stream> {
        val imdb = videoId.substringBefore(':').takeIf { it.startsWith("tt") } ?: return emptyList()
        val parts = videoId.split(':')
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        val kind = if (type == "movie") "movie" else "series"
        return coroutineScope {
            enabled.map { account ->
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                            client(account.kind).streams(account, imdb, kind, season, episode, title, year)
                        }.orEmpty().map { stream ->
                            synchronized(issued) { issued[stream.url] = stream }
                            stream.toStream(label(account))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    // --- Titles that only exist on a server (ids "srv1:<account>:<item>") ---

    private fun accountFor(ref: ServerItemRef): ServerAccount? = accounts.firstOrNull { it.id == ref.connectionId }

    /** Full details of a server-only title, with its episodes for a series. */
    suspend fun details(id: String): Meta? {
        val ref = ServerItemRef.parse(id) ?: return null
        val account = accountFor(ref) ?: return null
        return runCatching { withContext(Dispatchers.IO) { client(account.kind).details(account, ref.itemId) } }.getOrNull()
    }

    /** The streams of a server-only movie or episode. */
    suspend fun streamsForItem(id: String): List<Stream> {
        val ref = ServerItemRef.parse(id) ?: return emptyList()
        val account = accountFor(ref) ?: return emptyList()
        return runCatching { withContext(Dispatchers.IO) { client(account.kind).streamsForItem(account, ref.itemId) } }
            .getOrDefault(emptyList())
            .map { stream ->
                synchronized(issued) { issued[stream.url] = stream }
                stream.toStream(label(account))
            }
    }

    /**
     * The HLS address to play [stream] transcoded at [maxBitrateKbps] (Media server quality),
     * or null to play the file as it is.
     */
    fun transcodeUrl(stream: ServerStream, maxBitrateKbps: Int?): String? {
        val kbps = maxBitrateKbps ?: return null
        val account = accounts.firstOrNull { it.id == stream.accountId } ?: return null
        return client(account.kind).transcodeUrl(account, stream, kbps)
    }

    /** One page of a library, for the Library's server sources. */
    suspend fun browse(accountId: String, libId: String, start: Int, limit: Int, sort: ServerSort): ServerPageResult? {
        val account = accounts.firstOrNull { it.id == accountId } ?: return null
        val lib = account.libraries.firstOrNull { it.id == libId } ?: return null
        return withContext(Dispatchers.IO) { client(account.kind).browse(account, lib, start, limit, sort) }
    }

    /** A filter row's page of a library, narrowed by the server. */
    suspend fun filtered(accountId: String, libId: String, filters: com.fetch.desktop.FilterRowFilters, start: Int, limit: Int): ServerPageResult? {
        val account = accounts.firstOrNull { it.id == accountId } ?: return null
        val lib = account.libraries.firstOrNull { it.id == libId } ?: return null
        return withContext(Dispatchers.IO) { client(account.kind).filteredPage(account, lib, filters, start, limit) }
    }

    /** A library's genres, for the filter row editor. */
    suspend fun genres(accountId: String, libId: String): List<String> {
        val account = accounts.firstOrNull { it.id == accountId } ?: return emptyList()
        val lib = account.libraries.firstOrNull { it.id == libId } ?: return emptyList()
        return withContext(Dispatchers.IO) { client(account.kind).genres(account, lib) }
    }

    /** The server stream behind a [Stream] this state handed out, when it is one. */
    fun serverStream(stream: Stream): ServerStream? = stream.url?.let { synchronized(issued) { issued[it] } }

    /** Tells the server how far playback got, and marks it played when finished; null when its account is gone. */
    fun report(stream: ServerStream, positionMs: Long, finished: Boolean): Job? {
        val account = accounts.firstOrNull { it.id == stream.accountId } ?: return null
        return scope.launch(Dispatchers.IO) {
            runCatching {
                val client = client(account.kind)
                // mpv has closed either way, so the server's playback session ends too.
                client.reportPosition(account, stream, positionMs, stopped = true)
                if (finished) client.markPlayed(account, stream)
            }
        }
    }

    // --- Home and Continue Watching ---

    /** "Recently added" for every picked library of every enabled server. */
    fun refreshRows() {
        val wanted = enabled.flatMap { account ->
            account.libraries.filter { it.selected }.map { lib ->
                ServerRow(
                    key = "${account.id}|${lib.id}",
                    title = "Recently added in ${lib.name}",
                    subtitle = account.name
                ) to (account to lib)
            }
        }
        rows.clear()
        rows.addAll(wanted.map { it.first })
        wanted.forEach { (row, source) ->
            val (account, lib) = source
            scope.launch {
                val updated = try {
                    row.copy(items = withContext(Dispatchers.IO) { client(account.kind).latest(account, lib, ROW_SIZE) }, loading = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ServerAuthException) {
                    row.copy(loading = false, error = "Sign in to ${account.name} again in Settings › Media servers")
                } catch (e: Exception) {
                    row.copy(loading = false, error = e.message ?: "Couldn't reach ${account.name}")
                }
                val index = rows.indexOfFirst { it.key == row.key }
                if (index >= 0) rows[index] = updated
            }
        }
    }

    /** What you were watching on your servers, for Continue Watching. */
    suspend fun resume(): List<ProgressEntry> = coroutineScope {
        enabled.map { account ->
            async(Dispatchers.IO) {
                runCatching { client(account.kind).resume(account, 30) }.getOrDefault(emptyList()).map { item ->
                    val episodeId = if (item.season != null && item.episode != null) "${item.imdbId}:${item.season}:${item.episode}" else null
                    ProgressEntry(
                        contentId = item.imdbId,
                        contentType = item.type,
                        name = item.name,
                        poster = item.poster,
                        backdrop = item.backdrop,
                        videoId = episodeId ?: item.imdbId,
                        season = item.season,
                        episode = item.episode,
                        episodeTitle = item.episodeTitle,
                        positionMs = item.positionMs,
                        durationMs = item.durationMs,
                        lastWatched = item.lastPlayedEpochMs,
                        source = account.kind.name.lowercase()
                    )
                }
            }
        }.awaitAll().flatten()
    }

    companion object {
        const val STREAM_TIMEOUT_MS = 12_000L
        const val ROW_SIZE = 30

        /** One id per installation, as servers list each device that signs in. */
        fun deviceId(file: File = File(AppDirs.config, "device-id")): String {
            runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            val id = UUID.randomUUID().toString()
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(id)
            }
            return id
        }
    }
}
