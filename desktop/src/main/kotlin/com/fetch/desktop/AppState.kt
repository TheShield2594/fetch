package com.fetch.desktop

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AddonClient
import com.fetch.desktop.data.AddonStore
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.addonsDir
import com.fetch.desktop.data.profileDir
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.LibraryFile
import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.ProgressFile
import com.fetch.desktop.data.WatchHistory
import com.fetch.desktop.data.episodeTag
import com.fetch.desktop.data.libraryFile
import com.fetch.desktop.data.progressFile
import com.fetch.desktop.data.settingsFile
import com.fetch.desktop.data.toPreview
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.desktop.data.SubtitleMode
import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.tmdb.TmdbClient
import com.fetch.desktop.tmdb.TmdbEntityKind
import com.fetch.desktop.tmdb.TmdbState
import com.fetch.tv.domain.model.MetaCastMember
import com.fetch.tv.domain.model.MetaCompany
import com.fetch.desktop.data.VideoFit
import com.fetch.desktop.sync.RemoteWatchedKey
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.desktop.player.MpvArgs
import com.fetch.desktop.player.MpvProgress
import com.fetch.desktop.badges.StreamBadgesState
import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.ratings.CertificationCache
import com.fetch.desktop.ratings.RatingsResolver
import com.fetch.desktop.ratings.TmdbCertificationClient
import com.fetch.desktop.ratings.TmdbCredentials
import com.fetch.desktop.parental.PinGate
import com.fetch.desktop.sync.RemoteLibraryKey
import com.fetch.desktop.sync.RemotePinSetResult
import com.fetch.desktop.sync.SyncClient
import com.fetch.desktop.sync.applyTo
import com.fetch.desktop.sync.changedFrom
import com.fetch.desktop.sync.toRemoteSharedSettings
import com.fetch.desktop.sync.SyncAuthException
import com.fetch.desktop.sync.SyncBase
import com.fetch.desktop.sync.SyncMerge
import com.fetch.desktop.servers.ServerStream
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.desktop.servers.ServersStore
import com.fetch.desktop.skip.IntroDbClient
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SegmentType
import com.fetch.desktop.player.embedded.EmbeddedMpv
import com.fetch.desktop.player.embedded.LibMpv
import com.fetch.desktop.skip.SkipPlanner
import com.fetch.desktop.tracking.ScrobbleAction
import com.fetch.desktop.party.MpvPartyPlayer
import com.fetch.desktop.party.PartyEvent
import com.fetch.desktop.party.PartyMedia
import com.fetch.desktop.party.PartyPlayerSync
import com.fetch.desktop.party.PartyProfile
import com.fetch.desktop.party.PartyState
import com.fetch.desktop.party.PartySync
import com.fetch.desktop.party.SyncPartyBackend
import com.fetch.desktop.party.partyMediaKey
import kotlinx.coroutines.withTimeoutOrNull
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.desktop.tracking.TrackingStore
import com.fetch.tv.core.addon.AddonUrls
import com.fetch.tv.core.addon.needsConfiguration
import com.fetch.tv.core.streams.ranking.RankedStream
import com.fetch.tv.core.streams.ranking.StreamRanker
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.Video
import com.fetch.tv.domain.model.supportsExtra
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
import com.fetch.desktop.data.ProfileLimits
import com.fetch.desktop.parental.Parental
import com.fetch.desktop.parental.ScreenTimeBlock
import com.fetch.desktop.parental.ScreenTimeDay
import com.fetch.desktop.parental.screenTimeFile
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** The Settings pages, in rail order under their group headings (the TV app's groups). */
enum class SettingsPage(val label: String, val group: String, val parent: SettingsPage? = null) {
    PROFILES("Profiles", "People"),
    PLAYER("Player", "Playback"),
    STREAM_SELECTION("Stream Selection", "Playback"),
    UP_NEXT("Up Next", "Playback"),
    AUDIO("Audio", "Playback"),
    SUBTITLES("Subtitles", "Playback"),
    SKIP_SEGMENTS("Skip Segments", "Playback"),
    CONTENT_DISCOVERY("Content & Discovery", "Sources"),
    TMDB("TMDB", "Sources"),
    MDBLIST("MDBList", "Sources"),
    ADDONS("Addons", "Sources", parent = CONTENT_DISCOVERY),
    HOME_CATALOGS("Home catalogs", "Sources", parent = CONTENT_DISCOVERY),
    FILTER_ROWS("Filter rows", "Sources", parent = CONTENT_DISCOVERY),
    APPEARANCE("Appearance", "Home"),
    LAYOUT("Layout", "Home"),
    MEDIA_SERVERS("Media servers", "Sources", parent = CONTENT_DISCOVERY),
    STREAM_BADGES("Stream badges", "Playback", parent = STREAM_SELECTION),
    ACCOUNT("Account", "Accounts"),
    TRACKING("Tracking", "Accounts"),
    YOUTUBE("YouTube", "Accounts"),
    WATCH_PARTY("Watch party", "Accounts"),
    ABOUT("About", "System");

    /** The rail entry this page belongs under. */
    val railPage: SettingsPage get() = parent ?: this
}

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Discover : Screen
    data object Library : Screen
    data class Settings(val page: SettingsPage = SettingsPage.PLAYER) : Screen
    /**
     * [autoPlay] starts the best match as soon as the sources are in (Play on the home hero or a
     * Continue Watching card). [videoId] picks the episode, for Continue Watching.
     */
    data class Detail(val preview: MetaPreview, val autoPlay: Boolean = false, val videoId: String? = null) : Screen
    /** A cast or crew member's TMDB page. */
    data class Person(val tmdbId: Int, val name: String) : Screen
    /** A Home row's whole catalog, a page at a time (TV: See all). */
    data class SeeAll(val rowKey: String, val title: String) : Screen
    /** A studio's or network's titles, from TMDB. */
    data class Entity(val kind: TmdbEntityKind, val tmdbId: Int, val name: String) : Screen
    /** The YouTube page (sidebar, while signed in): its feeds and search. */
    data object YouTube : Screen
    /** A YouTube channel, playlist or "View all" page opened from it. */
    data class YouTubeBrowse(val target: com.fetch.desktop.youtube.YouTubeBrowseTarget) : Screen
}

/** A home row: one addon catalog and what it returned. */
data class CatalogRow(
    val addon: Addon,
    val catalog: CatalogDescriptor,
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Set for a filter row over this catalog: its own key and title, and its genre. */
    val filter: FilterRow? = null
) {
    val key: String get() = filter?.let(::filterRowKey) ?: "${addon.baseUrl}|${catalog.apiType}|${catalog.id}"

    /** The genre a filter row asks the catalog for. */
    val extra: Map<String, String> get() = filter?.filters?.genre?.let { mapOf("genre" to it) }.orEmpty()

    /** The row's heading, as the TV app writes it: "Popular - Movies", or just "Popular". */
    fun title(showType: Boolean): String {
        val name = (filter?.title ?: catalog.name).replaceFirstChar { it.uppercase() }
        return if (showType) "$name - ${typeLabel(catalog.apiType)}" else name
    }

    val title: String get() = title(showType = true)
}

fun typeLabel(type: String): String = when (type.lowercase()) {
    "movie" -> "Movies"
    "series" -> "Series"
    "channel" -> "Channels"
    "tv" -> "TV"
    else -> type.replaceFirstChar { it.uppercase() }
}

/** The sources for one title or episode, ranked by the TV app's StreamRanker. */
data class SourcesState(
    val preview: MetaPreview,
    val video: Video?,
    val videoId: String,
    val label: String,
    /** "S2 E6" for an episode, null for a movie. */
    val episodeTag: String? = null,
    val loading: Boolean = true,
    /** Every stream the addons sent, kept so a change to the rules can rank them again. */
    val streams: List<Stream> = emptyList(),
    val ranked: List<RankedStream> = emptyList(),
    val failedAddons: List<String> = emptyList()
) {
    /** What auto-play would pick: the best source the rules didn't rule out. */
    val best: RankedStream? get() = ranked.firstOrNull { it.isEligible }
}

/**
 * The desktop app's state. Network work runs on [Dispatchers.IO]; state is only written from
 * [scope], which is the window's composition scope.
 */
class AppState(
    private val scope: CoroutineScope,
    val profiles: ProfilesState = ProfilesState(),
    /** The profile this state belongs to; switching profiles makes a new [AppState]. */
    val profile: DesktopProfile = profiles.active ?: profiles.profiles.first(),
    /** The sync server account (Settings › Account), shared by every profile; null in tests. */
    val account: SyncAccountState? = null,
    private val client: AddonClient = AddonClient.create(),
    private val store: AddonStore = AddonStore(File(addonsDir(profile), "addons.json")),
    private val settingsStore: JsonFile<DesktopSettings> = settingsFile(profileDir(profile)),
    private val libraryStore: JsonFile<LibraryFile> = libraryFile(profileDir(profile)),
    private val progressStore: JsonFile<ProgressFile> = progressFile(profileDir(profile))
) {
    var screen: Screen by mutableStateOf(Screen.Home)
        private set
    private val backStack = ArrayDeque<Screen>()

    var settings: DesktopSettings by mutableStateOf(settingsStore.load().migrated())
        private set

    val addons = mutableStateListOf<Addon>()
    var addonsLoading by mutableStateOf(true)
        private set
    /** Saved addon URLs that didn't answer at startup, with why. They stay saved. */
    val unreachableAddons = mutableStateListOf<Pair<String, String>>()

    /** Every home-able catalog, in addon order; [homeRows] applies the pins and hidden ones. */
    val rows = mutableStateListOf<CatalogRow>()
    /** Home's scroll position, kept here so coming back from a title lands where you left. */
    val homeListState = LazyListState()

    val library = mutableStateListOf<LibraryEntry>().apply { addAll(libraryStore.load().items) }
    var progress: ProgressFile by mutableStateOf(progressStore.load())
        private set

    var detailMeta: Meta? by mutableStateOf(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var sources: SourcesState? by mutableStateOf(null)
        private set
    private var sourcesJob: Job? = null
    private var autoPlayPending = false

    /** The sources list sliding in over the detail page. */
    var sourcesPanelOpen by mutableStateOf(false)

    var searchQuery by mutableStateOf("")
        private set
    val searchRows = mutableStateListOf<CatalogRow>()
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    val discover = DiscoverState(scope, client) { key -> updateSettings { it.copy(discoverCatalog = key) } }

    /** The catalog a row's "See all" is showing. */
    val seeAll = DiscoverState(scope, client) {}

    fun openSeeAll(row: CatalogRow, title: String) {
        val catalog = DiscoverCatalog(row.addon, row.catalog)
        if (seeAll.selected?.key != catalog.key || seeAll.genre != row.filter?.filters?.genre) {
            seeAll.select(catalog, remember = false)
            // A filter row's See all keeps its genre.
            row.filter?.filters?.genre?.let(seeAll::selectGenre)
        }
        navigate(Screen.SeeAll(row.key, title))
    }

    /** A one-line message for the snackbar, cleared once shown. */
    var message: String? by mutableStateOf(null)

    /** "Are you still watching?", shown in the player or over the app. */
    var stillWatching: StillWatchingPrompt? by mutableStateOf(null)
        private set

    /** "Default Player: Ask every time": the choice waiting for an answer. */
    var playerChoice: ((builtIn: Boolean?) -> Unit)? by mutableStateOf(null)
        private set

    /** A newer Fetch on GitHub, when About › Updates found one. */
    val updates = UpdateChecker(scope)

    // --- Limits (profile PIN and parental limits live in ProfilesState) ---

    private val screenTimeStore = screenTimeFile(profileDir(profile))
    /** Today's watched time on this profile, for its daily limit and bedtime. */
    var screenTime: ScreenTimeDay by mutableStateOf(Parental.today(screenTimeStore.load(), LocalDate.now()))
        private set
    /** Set when a limit stopped playback from starting; the UI shows "That's all for today". */
    var screenTimeBlock: ScreenTimeBlock? by mutableStateOf(null)
    /** Episodes Up Next has started in a row, for the autoplay cap. */
    private var autoplayRun = 0

    val limits: ProfileLimits get() = profiles.profiles.firstOrNull { it.id == profile.id }?.limits ?: profile.limits

    /**
     * Ratings from TMDB for titles whose addon gives none, as the TV app resolves them. Only
     * looked up while this profile has a rating limit.
     */
    val ratings = RatingsResolver(
        scope,
        TmdbCertificationClient(okhttp3.OkHttpClient(), apiKey = { settings.tmdbApiKey?.takeIf { it.isNotBlank() } ?: TmdbCredentials.load() }),
        CertificationCache(File(AppDirs.config, "certifications.json"))
    )

    /** The rating to judge [preview] by: the addon's, else TMDB's (asking TMDB if needed). */
    private fun ratingOf(id: String, type: String, addonRating: String?, addonCountry: String?): Pair<String?, String?> {
        if (addonRating != null) return addonRating to addonCountry
        val imdb = id.substringBefore(':').takeIf { it.startsWith("tt") } ?: return null to null
        val known = ratings.ratings[imdb]
        if (known == null && imdb !in ratings.ratings) ratings.request(listOf(imdb to type))
        return known?.rating to known?.country
    }

    /** Whether this profile may see [preview], by its rating (the addon's, else TMDB's). */
    fun allowed(preview: MetaPreview): Boolean {
        if (limits.maxRating == null) return true
        val (rating, country) = ratingOf(preview.imdbId ?: preview.id, preview.apiType, preview.ageRating, preview.country)
        return Parental.allows(limits, rating, country)
    }

    fun allowed(meta: Meta?, preview: MetaPreview): Boolean {
        if (limits.maxRating == null) return true
        val (rating, country) = ratingOf(
            meta?.imdbId ?: preview.imdbId ?: preview.id,
            preview.apiType,
            meta?.ageRating ?: preview.ageRating,
            meta?.country ?: preview.country
        )
        return Parental.allows(limits, rating, country)
    }

    private fun currentBlock(): ScreenTimeBlock? {
        screenTime = Parental.today(screenTime, LocalDate.now())
        return Parental.block(limits, screenTime, LocalDateTime.now())
    }

    /** "More time (parent)": extra minutes today, or no limit for the rest of it. */
    fun addScreenTime(minutes: Int?) {
        val today = Parental.today(screenTime, LocalDate.now())
        screenTime = if (minutes == null) today.copy(unlimited = true) else today.copy(extraMinutes = today.extraMinutes + minutes)
        runCatching { screenTimeStore.save(screenTime) }
        screenTimeBlock = null
    }

    private fun countWatched(deltaMs: Long) {
        if (deltaMs <= 0) return
        screenTime = Parental.today(screenTime, LocalDate.now()).let { it.copy(watchedMs = it.watchedMs + deltaMs) }
        runCatching { screenTimeStore.save(screenTime) }
    }

    /** The Movie Database: title page details, More like this, collections, people, studios. */
    val tmdb = TmdbState(
        scope,
        TmdbClient(okhttp3.OkHttpClient(), apiKey = { settings.tmdbApiKey?.takeIf { it.isNotBlank() } ?: TmdbCredentials.load() })
    ) { settings.tmdb }

    fun openPerson(member: MetaCastMember) {
        val id = member.tmdbId ?: return
        navigate(Screen.Person(id, member.name))
    }

    fun openEntity(company: MetaCompany, kind: TmdbEntityKind) {
        val id = company.tmdbId ?: return
        navigate(Screen.Entity(kind, id, company.name))
    }

    /**
     * Runs [action] with [preview]'s IMDb id in place of a `tmdb:` one when TMDB knows it, so
     * the library, watched marks and Trakt see the same title as everywhere else.
     */
    fun withImdbId(preview: MetaPreview, action: (MetaPreview) -> Unit) {
        val tmdbId = TmdbClient.parseTmdbId(preview.id) ?: return action(preview)
        scope.launch {
            val imdb = runCatching { tmdb.client.imdbId(tmdbId, preview.type == ContentType.SERIES) }.getOrNull()
            action(if (imdb == null) preview else preview.copy(id = imdb, imdbId = imdb))
        }
    }

    /** Fusion stream badges (Settings › Stream Selection › Stream badges), per profile. */
    val badges = StreamBadgesState(scope, File(profileDir(profile), "stream-badges.json"))

    /** Trakt and Simkl for this profile. */
    val tracking = TrackingState(scope, TrackingStore(File(profileDir(profile), "tracking.json")), onMessage = { message = it })

    /** MDBList: the account's lists (Settings › MDBList) and ratings on title pages and the Home hero. */
    private val mdbListHttp = com.fetch.desktop.mdblist.MdbListHttp(okhttp3.OkHttpClient())
    val mdbListAccount = com.fetch.desktop.mdblist.MdbListAccountState(
        scope,
        com.fetch.desktop.mdblist.mdbListAccountFile(profileDir(profile)),
        com.fetch.desktop.mdblist.MdbListAccountClient(mdbListHttp),
        onMessage = { message = it }
    )
    val mdbListRatings = com.fetch.desktop.mdblist.MdbListRatingsState(
        scope,
        com.fetch.desktop.mdblist.MdbListRatingsClient(mdbListHttp),
        com.fetch.desktop.mdblist.MdbListRatingsCache(File(AppDirs.config, "mdblist-ratings.json")),
        settings = { settings.mdblist },
        accountConnected = { mdbListAccount.connected },
        accountAuth = { mdbListAccount.auth() }
    )

    /** Lists of your own here, and Trakt's, Simkl's and MDBList's lists (the Library's sources and Manage lists). */
    val lists = ListsState(
        scope,
        tracking,
        localListsFile(profileDir(profile)),
        libraryItems = { library.toList() },
        setInLibrary = { preview, on -> setInLocalLibrary(preview, on) },
        onMessage = { message = it },
        mdblist = mdbListAccount
    )

    /** The title "Manage lists" is open for. */
    var manageListsFor: MetaPreview? by mutableStateOf(null)

    /** Opens Manage lists for [preview], by its IMDb id when it has a `tmdb:` one. */
    fun manageLists(preview: MetaPreview) = withImdbId(preview) { manageListsFor = it }

    /** Watchlists from the connected accounts, shown as Library sources. */
    var remoteLists: Map<TrackingService, List<LibraryEntry>> by mutableStateOf(emptyMap())
        private set

    // --- Sync server ---

    private val syncBaseStore = JsonFile(File(profileDir(profile), "sync-base.json"), SyncBase.serializer()) { SyncBase() }
    var accountSyncing by mutableStateOf(false)
        private set

    /**
     * Syncs this profile's Library, Continue Watching and watched history with the sync server,
     * as the TV app does, so both see the same. The server's profile number is this profile's id.
     */
    fun syncAccount() {
        val sync = account ?: return
        val remote = sync.client() ?: return
        if (accountSyncing) return
        scope.launch {
            accountSyncing = true
            try {
                syncAccountSetup(remote)
                val id = profile.id
                // Library: a three-way merge, so a removal on either side carries over.
                val merge = SyncMerge.library(library.toList(), remote.pullLibrary(id), syncBaseStore.load())
                if (merge.push.isNotEmpty()) remote.pushLibrary(id, merge.push.map { with(SyncMerge) { it.toRemote() } })
                if (merge.deleteRemote.isNotEmpty()) remote.deleteLibrary(id, merge.deleteRemote.map { RemoteLibraryKey(it.id, it.type) })
                library.clear()
                library.addAll(merge.local)
                runCatching { libraryStore.save(LibraryFile(library.toList())) }
                runCatching { syncBaseStore.save(merge.base) }

                // Continue Watching: the newest position wins, then ours go up.
                val known = progress.entries.associateBy { it.contentId }
                saveProgress(WatchHistory.mergeRemote(progress, SyncMerge.progressEntries(remote.pullProgress(id), known), emptySet()))
                SyncMerge.progressToPush(progress.entries).takeIf { it.isNotEmpty() }?.let { remote.pushProgress(id, it) }

                // Watched history: both ways.
                val remoteWatched = remote.pullWatched(id)
                val push = SyncMerge.watchedToPush(progress.watched, remoteWatched, System.currentTimeMillis())
                if (push.isNotEmpty()) remote.pushWatched(id, push)
                val watchedIds = remoteWatched.map { with(SyncMerge) { it.videoId() } }
                saveProgress(progress.copy(watched = (progress.watched + watchedIds).distinct()))

                fillProgressNames()
                sync.lastSyncedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncAuthException) {
                sync.sessionExpired()
            } catch (e: Exception) {
                message = "Couldn't sync with your server: ${e.message}"
            } finally {
                accountSyncing = false
            }
        }
    }

    /** The server keeps positions but not titles or artwork; fill them from the addons. */
    private suspend fun fillProgressNames() {
        progress.entries.filter { it.name == it.contentId }.forEach { entry ->
            val meta = runCatching {
                withContext(Dispatchers.IO) { client.meta(addons.toList(), entry.contentType, entry.contentId) }
            }.getOrNull() ?: return@forEach
            val episode = meta.videos.firstOrNull { it.id == entry.videoId }
            saveProgress(
                progress.copy(entries = progress.entries.map {
                    if (it.contentId != entry.contentId) it else it.copy(
                        name = meta.name,
                        poster = meta.poster ?: it.poster,
                        backdrop = meta.background ?: it.backdrop,
                        logo = meta.logo ?: it.logo,
                        episodeTitle = episode?.title?.takeIf { t -> t.isNotBlank() } ?: it.episodeTitle
                    )
                })
            )
        }
    }

    fun start() {
        scope.launch {
            reloadAddons()
            // Caught-up shows are checked for new episodes now and every few hours.
            while (true) {
                refreshAiring()
                delay(AIRING_RECHECK_MS)
            }
        }
        if (settings.checkForUpdates) updates.check(beta = settings.betaUpdates)
        syncAccount()
        party.refreshAvailability()
        scope.launch { party.events.collect(::onPartyEvent) }
        scope.launch { syncTracking() }
        servers.refreshRows()
        refreshServerFilterRows()
        syncServers()
    }

    /** Pulls Continue Watching, watched history and watchlists from Trakt and Simkl. */
    fun syncTracking() {
        scope.launch {
            // MDBList's lists load even when Trakt and Simkl aren't connected.
            val snapshot = tracking.sync() ?: return@launch lists.refresh()
            saveProgress(WatchHistory.mergeRemote(progress, snapshot.playback, snapshot.watchedIds))
            remoteLists = snapshot.watchlists
            lists.refresh()
        }
    }

    /** Jellyfin, Emby and Plex: sources, Home rows and their Continue Watching. */
    val servers = ServersState(scope, ServersStore(File(addonsDir(profile), "servers.json")), onMessage = { message = it })

    /** Merges what you were watching on your media servers into Continue Watching. */
    fun syncServers() {
        if (servers.enabled.isEmpty()) return
        scope.launch {
            val resume = servers.resume()
            if (resume.isNotEmpty()) saveProgress(WatchHistory.mergeRemote(progress, resume, emptySet()))
        }
    }

    // --- YouTube ---

    /** YouTube for this profile (its sign-in, feeds and playback); see [YouTubeState]. */
    val youtube = YouTubeState(
        scope,
        object : YouTubeHost {
            override val playerSettings get() = settings.player
            override val youTubeSettings get() = settings.youtube
            override val restricted get() = limits.isRestricted
            override fun message(text: String) { message = text }
            override suspend fun chooseBuiltIn(): Boolean? {
                if (!LibMpv.available) return false
                if (playerSession != null) return true
                if (!settings.player.askPlayer) return settings.player.builtInPlayer
                val answer = kotlinx.coroutines.CompletableDeferred<Boolean?>()
                playerChoice = { choice ->
                    playerChoice = null
                    answer.complete(choice)
                }
                return answer.await()
            }
            override suspend fun openBuiltInPlayer(): PlayerSession? = playerSession ?: try {
                PlayerSession(withContext(Dispatchers.IO) { EmbeddedMpv.create(preferGpu = settings.player.builtInGpu) }).also { playerSession = it }
            } catch (e: Exception) {
                message = "The built-in player couldn't start (${e.message}). Using mpv's window."
                null
            }
            override val currentPlayer get() = playerSession
            override fun closePlayer() = this@AppState.closePlayer()
        },
        com.fetch.desktop.youtube.youTubeAuthFile(profileDir(profile)),
        com.fetch.desktop.youtube.youTubeDataFile(profileDir(profile))
    )

    // --- Navigation ---

    fun navigate(to: Screen) {
        if (to == screen) return
        // Coming back to a page never re-triggers its auto-play.
        backStack.addLast(screen.let { if (it is Screen.Detail) it.copy(autoPlay = false) else it })
        show(to)
    }

    fun back() {
        if (sourcesPanelOpen) {
            sourcesPanelOpen = false
            return
        }
        val current = screen
        if (backStack.isEmpty() && current is Screen.Settings && current.page.parent != null) {
            show(Screen.Settings(current.page.parent))
            return
        }
        backStack.removeLastOrNull()?.let(::show)
    }

    val canGoBack: Boolean
        get() = sourcesPanelOpen || backStack.isNotEmpty() ||
            (screen as? Screen.Settings)?.page?.parent != null

    fun openSection(section: Screen) {
        backStack.clear()
        show(section)
    }

    private fun show(to: Screen) {
        screen = to
        sourcesPanelOpen = false
        when (to) {
            is Screen.Detail -> {
                autoPlayPending = to.autoPlay
                loadDetail(to.preview, to.videoId)
            }
            is Screen.Person -> tmdb.loadPerson(to.tmdbId)
            is Screen.Entity -> tmdb.openEntity(to.kind, to.tmdbId, to.name)
            else -> Unit
        }
    }

    // --- Settings ---

    fun updateSettings(change: (DesktopSettings) -> DesktopSettings) {
        val before = settings
        val after = change(before)
        if (after == before) return
        settings = after
        try {
            settingsStore.save(after)
        } catch (e: Exception) {
            message = "Couldn't save settings: ${e.message}"
        }
        if (after.rankingRules != before.rankingRules) rerankSources()
        if (!applyingFromServer) queueSettingsPush(before)
    }

    // --- Sync server: settings, addons, profiles ---

    private var applyingFromServer = false
    private var settingsBeforePush: DesktopSettings? = null
    private var settingsPushJob: Job? = null

    /** Pushes what changed in the shared settings, two seconds after the last change. */
    private fun queueSettingsPush(before: DesktopSettings) {
        val remote = account?.client() ?: return
        if (settingsBeforePush == null) settingsBeforePush = before
        settingsPushJob?.cancel()
        settingsPushJob = scope.launch {
            delay(2_000)
            val from = settingsBeforePush ?: return@launch
            settingsBeforePush = null
            val diff = settings.toRemoteSharedSettings().changedFrom(from.toRemoteSharedSettings()) ?: return@launch
            runCatching { remote.pushSharedSettings(profile.id, diff) }
        }
    }

    /** The profile whose addons this one uses on the server (profile 1's when shared). */
    private val addonProfileId: Int get() = if (profile.usesPrimaryAddons) DesktopProfile.PRIMARY_ID else profile.id

    /** Sends the addon list to the server after an install, removal or reorder here. */
    private fun pushAddonsToServer() {
        val remote = account?.client() ?: return
        // A profile sharing the main profile's addons doesn't push them, as on TV.
        if (!profile.isPrimary && profile.usesPrimaryAddons) return
        val urls = addons.map { it.baseUrl } + unreachableAddons.map { it.first }
        scope.launch { runCatching { remote.pushAddons(profile.id, SyncMerge.addonsToPush(urls, remote.pullAddons(profile.id))) } }
    }

    /** Settings, addons, profiles, locks and limits from the server; the account's win, as on TV. */
    private suspend fun syncAccountSetup(remote: SyncClient) {
        remote.pullSharedSettings(profile.id)?.let { shared ->
            applyingFromServer = true
            try {
                updateSettings { shared.applyTo(it) }
            } finally {
                applyingFromServer = false
            }
        }

        val remoteAddons = remote.pullAddons(addonProfileId)
        val localUrls = store.load()
        val addonMerge = SyncMerge.addons(localUrls, remoteAddons)
        if (addonMerge.local.map(SyncMerge::addonKey) != localUrls.map(SyncMerge::addonKey)) {
            runCatching { store.save(addonMerge.local) }
            reloadAddons()
        }

        // Profiles, locks and limits are the main profile's to sync.
        if (!profile.isPrimary) return
        val profileMerge = SyncMerge.profiles(profiles.profiles, remote.pullProfiles())
        val remoteLocked = SyncMerge.lockedOnlyRemotely(profileMerge.local, remote.pullProfileLocks()).toSet()
        val merged = profileMerge.local.map { p ->
            val limits = if (p.isPrimary) p.limits else SyncMerge.parentalLimits(p.limits, remote.pullParentalLimits(p.id))
            p.copy(serverLocked = p.id in remoteLocked, limits = limits)
        }
        profiles.applyFromServer(merged)
        profileMerge.push?.let { remote.pushProfiles(it) }
    }

    /** Tells the server about profile, PIN and limit changes made here, while signed in. */
    private val profileSync = object : ProfilesState.SyncListener {
        override fun onProfilesChanged(deleted: DesktopProfile?) {
            val remote = account?.client() ?: return
            scope.launch {
                runCatching {
                    deleted?.let { remote.deleteProfileData(it.id) }
                    remote.pushProfiles(SyncMerge.profilesToPush(profiles.profiles, remote.pullProfiles()))
                }
            }
        }

        override fun onPinChanged(profile: DesktopProfile, pin: String?) {
            val remote = account?.client() ?: return
            scope.launch {
                runCatching {
                    if (pin == null) {
                        remote.clearProfilePin(profile.id)
                    } else if (remote.setProfilePin(profile.id, pin) == RemotePinSetResult.CURRENT_PIN_REQUIRED) {
                        message = "${profile.name}'s PIN changed here only: the sync server wants the old PIN, so change it on the TV too."
                    }
                }
            }
        }

        override fun onLimitsChanged(profile: DesktopProfile) {
            if (profile.isPrimary) return
            val remote = account?.client() ?: return
            scope.launch {
                runCatching {
                    remote.pushParentalLimits(profile.id, SyncMerge.parentalLimitsToPush(profile.limits, remote.pullParentalLimits(profile.id)))
                }
            }
        }

        override suspend fun verifyRemotePin(profile: DesktopProfile, pin: String): PinGate.Result? {
            val remote = account?.client() ?: return null
            val result = runCatching { remote.verifyProfilePin(profile.id, pin) }.getOrNull() ?: return null
            return when {
                result.unlocked -> PinGate.Result.Ok
                result.retryAfterSeconds > 0 -> PinGate.Result.Locked(result.retryAfterSeconds)
                else -> PinGate.Result.Wrong("Incorrect PIN")
            }
        }
    }

    init {
        profiles.listener = profileSync
    }

    // --- Addons ---

    private suspend fun reloadAddons() {
        addonsLoading = true
        val urls = store.load()
        val results = withContext(Dispatchers.IO) {
            urls.map { url ->
                async {
                    url to try {
                        Result.success(client.fetchAddon(url))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        addons.clear()
        unreachableAddons.clear()
        results.forEach { (url, result) ->
            result.fold(
                onSuccess = { addons.add(it) },
                onFailure = { unreachableAddons.add(url to (it.message ?: "Unreachable")) }
            )
        }
        addonsLoading = false
        addonsChanged()
    }

    fun refreshAddons() {
        scope.launch { reloadAddons() }
    }

    fun installAddon(input: String) {
        scope.launch {
            val addon = try {
                withContext(Dispatchers.IO) { client.fetchAddon(input) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Couldn't install that addon"
                return@launch
            }
            if (addon.needsConfiguration) {
                openInBrowser(configureUrl(addon))
                message = "${addon.displayName} must be configured first: its page is open in your browser. Install the link it gives you."
                return@launch
            }
            val key = AddonUrls.split(addon.baseUrl).let { (p, q) -> p + q }
            if (addons.any { AddonUrls.split(it.baseUrl).let { (p, q) -> p + q } == key }) {
                message = "${addon.displayName} is already installed"
                return@launch
            }
            addons.add(addon)
            persist()
            pushAddonsToServer()
            message = "Installed ${addon.displayName}"
            addonsChanged()
        }
    }

    fun removeAddon(addon: Addon) {
        addons.remove(addon)
        persist()
        pushAddonsToServer()
        rows.removeAll { it.addon.baseUrl == addon.baseUrl }
        discover.setAddons(addons.toList(), settings.discoverCatalog)
    }

    /** The addon's own settings page, for addons that say they're configurable. */
    fun configureUrl(addon: Addon): String = com.fetch.tv.core.addon.addonConfigureUrl(addon.baseUrl)

    fun configureAddon(addon: Addon) {
        openInBrowser(configureUrl(addon))
        message = "Opened ${addon.displayName}'s settings in your browser. Install the new link it gives you here."
    }

    fun openInBrowser(url: String) {
        val opened = runCatching { ProcessBuilder("xdg-open", url).start(); true }.getOrDefault(false) ||
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)); true }.getOrDefault(false)
        if (!opened) message = "Couldn't open a browser. The link is $url"
    }

    fun forgetUnreachable(url: String) {
        unreachableAddons.removeAll { it.first == url }
        persist()
    }

    fun moveAddon(addon: Addon, delta: Int) {
        val from = addons.indexOf(addon)
        val to = from + delta
        if (from < 0 || to !in addons.indices) return
        addons.removeAt(from)
        addons.add(to, addon)
        persist()
        pushAddonsToServer()
        addonsChanged()
    }

    private fun persist() {
        // Unreachable addons stay saved so a network blip at startup doesn't uninstall them.
        val urls = addons.map { it.baseUrl } + unreachableAddons.map { it.first }
        try {
            store.save(urls)
        } catch (e: Exception) {
            message = "Couldn't save the addon list: ${e.message}"
        }
    }

    private fun addonsChanged() {
        reloadRows()
        discover.setAddons(addons.toList(), settings.discoverCatalog)
    }

    // --- Home ---

    /** The rows Home shows: pinned catalogs first in pin order, then the rest, minus disabled ones. */
    val homeRows: List<CatalogRow>
        get() {
            val pinned = settings.pinnedCatalogs
            val disabled = settings.disabledCatalogs.toSet()
            val visible = rows.filter { it.key !in disabled }
            val ordered = visible.filter { it.key in pinned }.sortedBy { pinned.indexOf(it.key) } +
                visible.filter { it.key !in pinned }
            // A rating limit hides what it rules out, as on TV, and so does "Hide unreleased".
            val hideUnreleased = settings.hideUnreleased
            if (limits.maxRating == null && !hideUnreleased) return ordered
            return ordered.map { row -> row.copy(items = row.items.filter { allowed(it) && !(hideUnreleased && isUnreleased(it)) }) }
        }

    fun setCatalogPinned(key: String, pinned: Boolean) = updateSettings { s ->
        s.copy(pinnedCatalogs = if (pinned) (s.pinnedCatalogs - key) + key else s.pinnedCatalogs - key)
    }

    fun setCatalogEnabled(key: String, enabled: Boolean) = updateSettings { s ->
        s.copy(disabledCatalogs = if (enabled) s.disabledCatalogs - key else (s.disabledCatalogs - key) + key)
    }

    private fun reloadRows() {
        val wanted = addons.flatMap { addon ->
            addon.catalogs
                // Catalogs that need an extra (search, genre) can't be shown as a plain row.
                .filter { catalog -> catalog.extra.none { it.isRequired } && catalog.extraRequired.isEmpty() }
                .map { CatalogRow(addon, it) } +
                // Filter rows over this addon's catalogs follow them, as on TV.
                filterRows.mapNotNull { row ->
                    val source = row.source as? FilterRowSource.AddonCatalog ?: return@mapNotNull null
                    if (source.addonId != addon.id) return@mapNotNull null
                    addon.catalogs.firstOrNull { it.id == source.catalogId && it.apiType == source.type }?.let { CatalogRow(addon, it, filter = row) }
                }
        }
        rows.clear()
        rows.addAll(wanted)
        wanted.forEach { row ->
            scope.launch {
                val updated = try {
                    row.copy(items = withContext(Dispatchers.IO) { client.catalog(row.addon, row.catalog, extra = row.extra) }, loading = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    row.copy(loading = false, error = e.message ?: "Couldn't load")
                }
                val index = rows.indexOfFirst { it.key == row.key }
                if (index >= 0) rows[index] = updated
            }
        }
    }

    // --- Filter rows ---

    private val filterRowsStore = filterRowsFile(profileDir(profile))

    /** Home rows built from filters (Settings › Content & Discovery › Filter rows). */
    var filterRows: List<FilterRow> by mutableStateOf(filterRowsStore.load().rows)
        private set

    /** A filter row over a media server library, as Home shows it. */
    data class ServerFilterRowView(val row: FilterRow, val items: List<MetaPreview> = emptyList(), val loading: Boolean = true, val error: String? = null)

    val serverFilterRows = mutableStateListOf<ServerFilterRowView>()

    fun saveFilterRow(row: FilterRow) {
        val updated = if (filterRows.any { it.id == row.id }) filterRows.map { if (it.id == row.id) row else it } else filterRows + row
        storeFilterRows(updated)
    }

    fun deleteFilterRow(id: String) = storeFilterRows(filterRows.filterNot { it.id == id })

    private fun storeFilterRows(rows: List<FilterRow>) {
        filterRows = rows
        runCatching { filterRowsStore.save(FilterRowsFile(rows)) }.onFailure { message = "Couldn't save filter rows: ${it.message}" }
        reloadRows()
        refreshServerFilterRows()
    }

    /** Loads each server filter row's first page from its server. */
    fun refreshServerFilterRows() {
        val wanted = filterRows.filter { r ->
            val s = r.source as? FilterRowSource.ServerLibrary ?: return@filter false
            servers.enabled.any { a -> a.id == s.accountId && a.libraries.any { it.id == s.libraryId } }
        }
        serverFilterRows.clear()
        serverFilterRows.addAll(wanted.map { ServerFilterRowView(it) })
        wanted.forEach { row ->
            val source = row.source as FilterRowSource.ServerLibrary
            scope.launch {
                val view = try {
                    val page = servers.filtered(source.accountId, source.libraryId, row.filters, 0, FILTER_ROW_PAGE)
                    ServerFilterRowView(row, page?.items.orEmpty(), loading = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ServerFilterRowView(row, loading = false, error = e.message ?: "Couldn't load")
                }
                val i = serverFilterRows.indexOfFirst { it.row.id == row.id }
                if (i >= 0) serverFilterRows[i] = view
            }
        }
    }

    // --- Continue Watching ---

    private val continueWatchingRows: Pair<List<ProgressEntry>, List<ProgressEntry>>
        get() = WatchHistory.continueWatchingRows(progress, LocalDate.now(), settings.showUnairedNextUp, settings.continueWatchingSort)

    /** Continue Watching, by Layout › Continue Watching's settings. */
    val continueWatching: List<ProgressEntry> get() = continueWatchingRows.first

    /** Unaired Next Up episodes, soonest first, with "Separate Upcoming row". */
    val upcoming: List<ProgressEntry> get() = continueWatchingRows.second

    fun isUpcoming(entry: ProgressEntry): Boolean = WatchHistory.isUpcoming(entry, LocalDate.now())

    // --- New episodes for caught-up shows ---

    private val airingChecked = mutableMapOf<String, Long>()

    /**
     * Re-reads the episode lists of caught-up shows and Next Up shows, at most every
     * [AIRING_RECHECK_MS] each, so a show comes back to Continue Watching when a new episode is
     * listed and the Upcoming dates stay right, as the TV's Continue Watching refresh does.
     */
    private suspend fun refreshAiring() {
        val now = System.currentTimeMillis()
        val shows = (progress.caughtUp.map { Triple(it.contentId, it.contentType, it.name) } +
            progress.entries.filter { it.upNext && it.contentType != "movie" }.map { Triple(it.contentId, it.contentType, it.name) })
            .distinctBy { it.first }
            .filter { (id, _, _) -> !ServerItemRef.isServerId(id) && now - (airingChecked[id] ?: 0L) >= AIRING_RECHECK_MS }
        for ((id, type, name) in shows) {
            airingChecked[id] = now
            val meta = runCatching { withContext(Dispatchers.IO) { client.meta(addons.toList(), type, id) } }.getOrNull() ?: continue
            if (meta.videos.isEmpty()) continue
            val known = progress.entries.firstOrNull { it.contentId == id }
            val caught = progress.caughtUp.firstOrNull { it.contentId == id }
            val target = PlaybackTarget(
                contentId = id,
                contentType = type,
                name = meta.name.ifBlank { name },
                poster = known?.poster ?: caught?.poster ?: meta.poster,
                backdrop = known?.backdrop ?: caught?.backdrop ?: meta.background,
                logo = known?.logo ?: caught?.logo ?: meta.logo,
                video = null,
                episodes = meta.videos
            )
            saveProgress(WatchHistory.refreshShow(progress, target, System.currentTimeMillis()))
        }
    }

    /** The saved position for this title, when it is in progress or has a next episode waiting. */
    fun progressFor(contentId: String): ProgressEntry? =
        progress.entries.firstOrNull { it.contentId == contentId && !it.hidden }

    fun resumeEntry(videoId: String): ProgressEntry? =
        progress.entries.firstOrNull { it.videoId == videoId && !it.upNext && WatchHistory.resumeStart(progress, videoId) != null }

    fun openContinueWatching(entry: ProgressEntry, play: Boolean) {
        // An episode that hasn't aired can't play yet: its show's page opens instead.
        navigate(Screen.Detail(entry.toPreview(), autoPlay = play && !isUpcoming(entry), videoId = entry.videoId))
    }

    fun removeFromContinueWatching(entry: ProgressEntry) {
        saveProgress(WatchHistory.hide(progress, entry.contentId))
        message = "Removed ${entry.name} from Continue Watching"
    }

    fun startFromBeginning(entry: ProgressEntry) {
        saveProgress(WatchHistory.clearPosition(progress, entry.contentId))
        navigate(Screen.Detail(entry.toPreview(), autoPlay = true, videoId = entry.videoId))
    }

    /** Detail's "Start over": forget where this title was stopped and play it from the top. */
    fun startOverDetail() {
        val current = sources ?: return
        saveProgress(WatchHistory.clearPosition(progress, current.preview.id))
        playBest()
    }

    // --- Watched state ---

    fun isWatched(videoId: String): Boolean = videoId in watchedSet

    private var watchedCache: Pair<List<String>, Set<String>> = emptyList<String>() to emptySet()
    private val watchedSet: Set<String>
        get() {
            val list = progress.watched
            if (watchedCache.first !== list) watchedCache = list to list.toSet()
            return watchedCache.second
        }

    /**
     * Marks [videos] of [preview] (or the movie itself when empty) watched or unwatched here, on
     * Trakt and Simkl, and on the sync server, as the TV's "Mark as watched" does.
     */
    fun setWatched(preview: MetaPreview, videos: List<Video>, watched: Boolean, episodes: List<Video> = detailMeta?.takeIf { it.id == preview.id }?.videos.orEmpty()) {
        val base = PlaybackTarget(
            contentId = preview.id,
            contentType = preview.apiType,
            name = preview.name,
            poster = preview.poster,
            backdrop = preview.backdropUrl,
            logo = preview.logo,
            video = null,
            episodes = episodes
        )
        val targets = if (videos.isEmpty()) listOf(base) else videos.map { base.copy(video = it) }
        val ids = targets.map { it.videoId }
        val changed = ids.filter { (it in watchedSet) != watched }
        if (changed.isEmpty()) return
        saveProgress(WatchHistory.setWatched(progress, base, ids, watched, System.currentTimeMillis()))
        targets.filter { it.videoId in changed }.forEach { tracking.setWatched(it, watched) }
        val remote = account?.client()
        if (remote != null) {
            scope.launch {
                runCatching {
                    if (watched) {
                        remote.pushWatched(profile.id, changed.mapNotNull { SyncMerge.watchedItem(it, System.currentTimeMillis()) })
                    } else {
                        remote.deleteWatched(profile.id, changed.mapNotNull { id ->
                            SyncMerge.watchedItem(id, 0L)?.let { RemoteWatchedKey(it.contentId, it.season, it.episode) }
                        })
                    }
                }
            }
        }
        message = when {
            videos.size > 1 -> "Marked ${videos.size} episodes ${if (watched) "watched" else "unwatched"}"
            videos.size == 1 -> "Marked ${episodeTag(videos[0].season, videos[0].episode) ?: "episode"} ${if (watched) "watched" else "unwatched"}"
            else -> "Marked ${preview.name} ${if (watched) "watched" else "unwatched"}"
        }
    }

    /** Whether a whole title counts as watched: the movie, or every aired episode of a series. */
    fun isTitleWatched(preview: MetaPreview, episodes: List<Video>): Boolean {
        if (preview.type != ContentType.SERIES) return isWatched(preview.id)
        val real = episodes.filter { (it.season ?: 0) > 0 }
        return real.isNotEmpty() && real.all { isWatched(it.id) }
    }

    /** From a poster's menu: a movie directly, a series by every episode (fetched first). */
    fun setTitleWatched(preview: MetaPreview, watched: Boolean) {
        if (preview.type != ContentType.SERIES) {
            setWatched(preview, emptyList(), watched)
            return
        }
        scope.launch {
            val meta = runCatching { withContext(Dispatchers.IO) { client.meta(addons.toList(), preview.rawType, preview.id) } }.getOrNull()
            val episodes = meta?.videos.orEmpty().filter { (it.season ?: 0) > 0 && isAired(it) }
            if (episodes.isEmpty()) {
                message = "Couldn't load ${preview.name}'s episodes"
                return@launch
            }
            setWatched(preview, episodes, watched, meta?.videos.orEmpty())
        }
    }

    private fun saveProgress(file: ProgressFile) {
        progress = file
        try {
            progressStore.save(file)
        } catch (_: Exception) {
            // Losing one position update isn't worth interrupting playback for.
        }
    }

    // --- Library ---

    fun isInLibrary(id: String): Boolean = library.any { it.id == id }

    /** Adds to or removes from the local library only (Manage lists); Trakt and Simkl are left alone. */
    fun setInLocalLibrary(preview: MetaPreview, on: Boolean) {
        if (on == isInLibrary(preview.id)) return
        if (on) library.add(LibraryEntry.from(preview, addedAt = System.currentTimeMillis())) else library.removeAll { it.id == preview.id }
        runCatching { libraryStore.save(LibraryFile(library.toList())) }.onFailure { message = "Couldn't save the library: ${it.message}" }
    }

    fun toggleLibrary(preview: MetaPreview, meta: Meta? = null) {
        if (isInLibrary(preview.id)) {
            library.removeAll { it.id == preview.id }
            tracking.setWatchlisted(preview, on = false)
            message = "Removed from library"
        } else {
            val enriched = if (meta == null) preview else preview.copy(
                name = meta.name,
                poster = meta.poster ?: preview.poster,
                background = meta.background ?: preview.background,
                logo = meta.logo ?: preview.logo,
                description = meta.description ?: preview.description,
                releaseInfo = meta.releaseInfo ?: preview.releaseInfo,
                imdbRating = meta.imdbRating ?: preview.imdbRating,
                genres = meta.genres.ifEmpty { preview.genres }
            )
            library.add(LibraryEntry.from(enriched, addedAt = System.currentTimeMillis()))
            tracking.setWatchlisted(enriched, on = true)
            message = if (tracking.connected.isEmpty()) "Added to library"
            else "Added to library and ${tracking.connected.joinToString(" and ") { it.watchlistName }}"
        }
        try {
            libraryStore.save(LibraryFile(library.toList()))
        } catch (e: Exception) {
            message = "Couldn't save the library: ${e.message}"
        }
    }

    // --- Search ---

    /** Catalogs that can answer a search: they take a "search" extra and need nothing else. */
    private fun searchableRows(): List<CatalogRow> = addons.flatMap { addon ->
        addon.catalogs
            .filter { catalog ->
                catalog.supportsExtra("search") &&
                    catalog.extra.none { it.isRequired && !it.name.equals("search", ignoreCase = true) } &&
                    catalog.extraRequired.none { !it.equals("search", ignoreCase = true) }
            }
            .map { CatalogRow(addon, it) }
    }

    /** Searches every searchable catalog once typing pauses for [SEARCH_DEBOUNCE_MS]. */
    fun search(query: String) {
        searchQuery = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            searchRows.clear()
            searching = false
            return
        }
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val wanted = searchableRows()
            searchRows.clear()
            searchRows.addAll(wanted)
            searching = true
            coroutineScope {
                wanted.forEach { row ->
                    launch {
                        val updated = try {
                            row.copy(
                                items = withContext(Dispatchers.IO) { client.search(row.addon, row.catalog, trimmed) },
                                loading = false
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            row.copy(loading = false, error = e.message ?: "Couldn't search")
                        }
                        val index = searchRows.indexOfFirst { it.key == row.key }
                        if (index >= 0) searchRows[index] = updated
                    }
                }
            }
            searching = false
        }
    }

    val hasSearchableCatalogs: Boolean get() = searchableRows().isNotEmpty()

    /** Saves the current query in Recent searches (on Enter, or opening a result), newest first. */
    fun rememberSearch(query: String = searchQuery) {
        val q = query.trim()
        if (q.length < 2) return
        updateSettings { s -> s.copy(recentSearches = (listOf(q) + s.recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT_SEARCHES)) }
    }

    fun removeRecentSearch(query: String) = updateSettings { s -> s.copy(recentSearches = s.recentSearches - query) }

    fun clearRecentSearches() = updateSettings { it.copy(recentSearches = emptyList()) }

    // --- Detail and sources ---

    private fun loadDetail(requested: MetaPreview, videoId: String?) {
        detailMeta = null
        sources = null
        sourcesJob?.cancel()
        detailLoading = true
        scope.launch {
            // A title from TMDB (More like this, a person's page) opens by its IMDb id when TMDB
            // knows one, so it comes from your addons like any other.
            var preview = requested
            val tmdbOnly = TmdbClient.parseTmdbId(requested.id)
            if (tmdbOnly != null) {
                val imdb = runCatching { tmdb.client.imdbId(tmdbOnly, requested.type == ContentType.SERIES) }.getOrNull()
                if (imdb != null) {
                    preview = requested.copy(id = imdb, imdbId = imdb)
                    val current = screen
                    if (current is Screen.Detail && current.preview.id == requested.id) screen = current.copy(preview = preview)
                }
            }
            val isServer = ServerItemRef.isServerId(preview.id)
            val meta = when {
                // A title only on your media server comes from the server, not the addons.
                isServer -> servers.details(preview.id)
                TmdbClient.parseTmdbId(preview.id) != null -> tmdb.metaFor(tmdbOnly!!, preview.type == ContentType.SERIES)
                else -> withContext(Dispatchers.IO) { client.meta(addons.toList(), preview.rawType, preview.id) }
            }
            val current = screen
            if (current is Screen.Detail && current.preview.id == preview.id) {
                detailMeta = meta
                detailLoading = false
                // Look up sources straight away so the Play button can name what it will start:
                // the movie, or for a series the episode asked for, the one in Continue Watching,
                // or the first.
                val videos = meta?.videos.orEmpty()
                val first = firstEpisode(videos)
                val isSeries = preview.type == ContentType.SERIES || first != null
                val wantedId = videoId ?: progressFor(preview.id)?.videoId
                val episode = videos.firstOrNull { it.id == wantedId } ?: first
                when {
                    !isSeries -> loadSources(preview)
                    episode != null -> loadSources(preview, episode)
                }
                // TMDB's details arrive after the addon's, so the page and sources don't wait.
                if (meta != null && !isServer && TmdbClient.parseTmdbId(meta.id) == null) {
                    val enriched = tmdb.enrich(meta, isSeries)
                    if (enriched !== meta && detailMeta === meta && (screen as? Screen.Detail)?.preview?.id == preview.id) {
                        detailMeta = enriched
                        // The episode being offered gets TMDB's title and artwork too.
                        sources?.video?.let { v -> enriched.videos.firstOrNull { it.id == v.id } }?.let { v -> sources = sources?.copy(video = v) }
                    }
                }
            }
        }
    }

    fun loadSources(preview: MetaPreview, video: Video? = null) {
        val videoId = video?.id ?: preview.id
        val tag = video?.let { episodeTag(it.season, it.episode) }
        val label = video?.let { v -> listOfNotNull(tag, v.title.takeIf { it.isNotBlank() }).joinToString(" · ") }
            ?.let { "${preview.name} · $it" }
            ?: preview.name
        sourcesJob?.cancel()
        sources = SourcesState(preview = preview, video = video, videoId = videoId, label = label, episodeTag = tag)
        sourcesJob = scope.launch {
            // Addons and your own servers are asked at once; the ranker puts your copies first.
            val (results, own) = if (ServerItemRef.isServerId(videoId)) {
                emptyList<com.fetch.desktop.data.AddonStreamResult>() to servers.streamsForItem(videoId)
            } else coroutineScope {
                val fromAddons = async(Dispatchers.IO) { client.streams(addons.toList(), preview.rawType, videoId, timeoutMs = addonTimeoutMs) }
                val fromServers = async { servers.streams(preview.rawType, videoId, preview.name, yearOf(preview.releaseInfo)) }
                fromAddons.await() to fromServers.await()
            }
            val streams = own + results.flatMap { it.streams }
            val loaded = SourcesState(
                preview = preview,
                video = video,
                videoId = videoId,
                label = label,
                episodeTag = tag,
                loading = false,
                streams = streams,
                ranked = rank(streams),
                failedAddons = results.filter { it.error != null }.map { "${it.addon.displayName}: ${it.error}" }
            )
            sources = loaded
            if (autoPlayPending) {
                autoPlayPending = false
                if (settings.autoPlayMode == AutoPlayMode.MANUAL) {
                    sourcesPanelOpen = true
                } else {
                    val pick = pickAuto(loaded.ranked)
                    if (pick != null) play(pick) else message = "No source to play automatically. Pick one below."
                }
            }
        }
    }

    private val addonTimeoutMs: Long get() = settings.addonTimeoutSeconds.coerceIn(3, 120) * 1000L

    /**
     * The source Play starts, by Settings › Stream Selection › Play: the best match, the first
     * playable source in addon order, or the first matching a pattern. Null for "Pick every time"
     * or when nothing qualifies.
     */
    fun pickAuto(ranked: List<RankedStream>, mode: AutoPlayMode = settings.autoPlayMode): RankedStream? =
        pickSource(ranked, mode, settings.autoPlayRegex)

    /** A rule from a source's menu ("Prefer addon", "Never this release group"), saved to Stream Selection. */
    fun applyRankingRule(change: (StreamRankingRules) -> StreamRankingRules, confirmation: String) {
        updateSettings { it.copy(rankingRules = change(it.rankingRules)) }
        message = confirmation
    }

    fun releaseGroupOf(stream: Stream): String? =
        if (stream.serverTarget == null) StreamTitleParser.parse(stream).releaseGroup else null

    private suspend fun rank(streams: List<Stream>): List<RankedStream> = withContext(Dispatchers.Default) {
        StreamRanker.rank(streams, rules = settings.rankingRules, isPlayable = ExternalPlayer::canPlay)
    }

    private fun rerankSources() {
        val current = sources ?: return
        if (current.loading) return
        scope.launch {
            val ranked = rank(current.streams)
            if (sources?.videoId == current.videoId) sources = current.copy(ranked = ranked)
        }
    }

    /** Plays the best match now, or as soon as the sources are in. */
    fun playBest() {
        val current = sources ?: return
        if (current.loading) {
            autoPlayPending = true
            message = if (settings.autoPlayMode == AutoPlayMode.MANUAL) "Your sources open as soon as your addons answer…"
            else "Starting the best match as soon as your addons answer…"
            return
        }
        val pick = pickAuto(current.ranked)
        if (pick != null) play(pick) else sourcesPanelOpen = true
    }

    fun playTrailer(ytId: String, title: String) {
        when (val result = ExternalPlayer.playUrl("https://www.youtube.com/watch?v=$ytId", "$title (trailer)")) {
            is ExternalPlayer.Result.Started -> message = "Opening the trailer in ${result.player}"
            is ExternalPlayer.Result.Failed -> message = result.message
        }
    }

    /** Plays [ranked] for the title or episode the sources are for, resuming where it was left. */
    fun play(ranked: RankedStream, builtIn: Boolean? = null) {
        autoPlayPending = false
        autoplayRun = 0
        val context = sources ?: return
        val meta = detailMeta?.takeIf { it.id == context.preview.id }
        if (!allowed(meta, context.preview)) {
            message = "Not available on ${profile.name}"
            return
        }
        val target = PlaybackTarget(
            contentId = context.preview.id,
            contentType = context.preview.apiType,
            name = meta?.name ?: context.preview.name,
            poster = meta?.poster ?: context.preview.poster,
            backdrop = meta?.background ?: context.preview.background,
            logo = meta?.logo ?: context.preview.logo,
            video = context.video,
            episodes = meta?.videos.orEmpty()
        )
        launchPlayback(ranked.stream, context.label, target, builtIn = builtIn, alternatives = context.ranked)
    }

    /**
     * Starts mpv with the player settings and any addon subtitles, resuming from the saved
     * position, then follows it for Continue Watching and Up Next.
     */
    private fun launchPlayback(
        stream: Stream,
        label: String,
        target: PlaybackTarget,
        start: String? = null,
        builtIn: Boolean? = null,
        alternatives: List<RankedStream> = emptyList()
    ) {
        currentBlock()?.let {
            screenTimeBlock = it
            return
        }
        // "Ask every time": the built-in player or mpv's window, unless one is already open.
        // A watch party needs the built-in player: it keeps mpv in step with the others.
        val inParty = party.state.value.party != null && LibMpv.available
        if (builtIn == null && settings.player.askPlayer && playerSession == null && LibMpv.available && !inParty) {
            playerChoice = { choice ->
                playerChoice = null
                if (choice != null) launchPlayback(stream, label, target, start, choice, alternatives)
            }
            return
        }
        scope.launch {
            val player = settings.player
            val serverStream = servers.serverStream(stream)
            // Fetch's own position first; otherwise where the server says you stopped.
            val resume = start ?: partyStart(target) ?: WatchHistory.resumeStart(progress, target.videoId)
                ?: serverStream?.resumePositionMs?.takeIf { it > 60_000 }?.let { (it / 1000).toString() }
            val subtitles = if (player.subtitleMode == SubtitleMode.OFF) {
                emptyList()
            } else {
                // The server's own subtitle files, then the addons' in your language.
                serverStream?.subtitles.orEmpty().map { it.url } +
                    if (player.forwardAddonSubtitles && !ServerItemRef.isServerId(target.contentId)) {
                        addonSubtitles(target, player.subtitleLanguage ?: Locale.getDefault().language)
                    } else {
                        emptyList()
                    }
            }
            // Media server quality: a capped bitrate plays the server's transcode instead of the file.
            val played = serverStream?.let { servers.transcodeUrl(it, player.serverMaxBitrateKbps) }
                ?.let { stream.copy(url = it) } ?: stream
            val options = MpvArgs.build(player, subtitles)
            // The built-in player also offers a Skip button for segments that aren't automatic.
            val segments = if (player.builtInPlayer || player.autoSkip.isNotEmpty()) segmentsFor(target) else emptyList()
            val useBuiltIn = (builtIn ?: (player.builtInPlayer || playerSession != null)) || inParty
            val partyOverrode = inParty && builtIn != true && !player.builtInPlayer && playerSession == null
            if (useBuiltIn && LibMpv.available &&
                playEmbedded(stream, played, label, target, resume, options, serverStream, segments, alternatives)
            ) {
                if (partyOverrode) playerSession?.party?.showMessage("Watch parties play in the built-in player")
                return@launch
            }
            val skipper = SkipPlanner(segments, player.autoSkip).takeUnless { it.isEmpty }
            closePlayer()
            when (val result = ExternalPlayer.play(played, label, start = resume, options = options)) {
                is ExternalPlayer.Result.Started -> {
                    message = if (resume != null) "Resuming in ${result.player}" else "Opening in ${result.player}"
                    followProgress(result, target, serverStream, skipper)
                }
                is ExternalPlayer.Result.Failed -> message = result.message
            }
        }
    }

    /** Up to three addon subtitles in [language], best first as the addons sent them. */
    private suspend fun addonSubtitles(target: PlaybackTarget, language: String): List<String> {
        val wanted = MpvArgs.languageCodes(language).split(',').toSet()
        return withContext(Dispatchers.IO) {
            client.subtitles(addons.toList(), target.contentType, target.videoId, timeoutMs = 4_000)
        }.filter { (lang, _) -> lang in wanted }.map { it.second }.distinct().take(3)
    }

    /** IntroDB's intro, recap and credits times for [target]; empty when unknown. */
    private suspend fun segmentsFor(target: PlaybackTarget): List<Segment> {
        val imdb = target.contentId.takeIf { it.startsWith("tt") } ?: return emptyList()
        val video = target.video
        return withTimeoutOrNull(4_000) { introDb.segments(imdb, video?.season, video?.episode) }.orEmpty()
    }

    private val introDb by lazy {
        IntroDbClient(okhttp3.OkHttpClient()) { settings.player.introDbUrl?.takeIf { it.isNotBlank() } ?: IntroDbClient.DEFAULT_URL }
    }

    private fun followProgress(
        result: ExternalPlayer.Result.Started,
        target: PlaybackTarget,
        serverStream: ServerStream? = null,
        skipper: SkipPlanner? = null
    ) {
        val process = result.process ?: return
        val socket = result.ipcSocket ?: return
        follow(target, serverStream, skipper) { onPosition, onSample ->
            MpvProgress.followToEnd(socket, process, onPosition = onPosition, onSample = onSample)
        }
    }

    /**
     * Follows playback, in mpv's window or the built-in player ([run]): saves the position for
     * Continue Watching and screen time, skips segments, scrobbles, tells the media server, and
     * at the end runs Up Next and syncs.
     */
    private fun follow(
        target: PlaybackTarget,
        serverStream: ServerStream?,
        skipper: SkipPlanner?,
        afterEnd: suspend (ending: MpvProgress.Ending, continued: Boolean) -> Unit = { _, _ -> },
        run: suspend (onPosition: (Double) -> MpvProgress.Seek?, onSample: (MpvProgress.Sample) -> Unit) -> MpvProgress.Ending
    ) {
        scope.launch {
            playbackFollowers++
            var scrobbled = false
            var lastPosition: Double? = null
            val ending = withContext(Dispatchers.IO) {
                run(
                    { position -> skipper?.at(position)?.let { MpvProgress.Seek(it.endSec, "Skipped ${it.type.label.lowercase()}") } }
                ) { sample ->
                    scope.launch {
                        // Screen time counts what actually played, not seeks.
                        lastPosition?.let { previous ->
                            val played = sample.positionSec - previous
                            if (played in 0.0..30.0) countWatched((played * 1000).toLong())
                        }
                        lastPosition = sample.positionSec
                        recordProgress(target, sample)
                        // Trakt and Simkl hear about it once it has really started.
                        if (!scrobbled) {
                            scrobbled = true
                            tracking.scrobble(target, ScrobbleAction.START, sample.percent())
                        }
                    }
                }
            }
            // Stop at 80% or more marks it watched on both services.
            ending.last?.let { if (scrobbled) tracking.scrobble(target, ScrobbleAction.STOP, it.percent()) }
            // And the media server it came from hears where you stopped, or that you finished.
            val last = ending.last
            if (serverStream != null && last != null) {
                servers.report(
                    serverStream,
                    positionMs = (last.positionSec * 1000).toLong(),
                    finished = last.percent() >= WatchProgress.COMPLETED_THRESHOLD * 100
                )
            }
            playbackFollowers--
            val continued = playbackEnded(target, ending)
            afterEnd(ending, continued)
            syncAccount()
        }
    }

    /** Playbacks being followed (mpv's window or the built-in player); Ambient Mode waits while any are. */
    var playbackFollowers by mutableStateOf(0)
        private set

    val isPlaying: Boolean get() = playbackFollowers > 0 || playerSession != null || youtube.nowPlaying != null

    // --- Built-in player ---

    /** The built-in player while it's open; null shows the app. */
    var playerSession: PlayerSession? by mutableStateOf(null)
        private set

    /** Closes the built-in player; the position up to now is saved as it stops. */
    fun closePlayer() {
        val session = playerSession ?: return
        playerSession = null
        session.party?.leave()
        partySyncJob?.cancel()
        session.mpv.stop()
        scope.launch {
            // Let the stop reach Continue Watching, then free libmpv.
            delay(1_500)
            withContext(Dispatchers.IO) { session.mpv.close() }
        }
    }

    private suspend fun playEmbedded(
        original: Stream,
        stream: Stream,
        label: String,
        target: PlaybackTarget,
        resume: String?,
        options: List<String>,
        serverStream: ServerStream?,
        segments: List<Segment>,
        alternatives: List<RankedStream>
    ): Boolean {
        val url = ExternalPlayer.playableUrl(stream) ?: run {
            message = "This source has no playable link. Fetch plays direct and debrid links only."
            return true
        }
        val session = playerSession ?: try {
            PlayerSession(withContext(Dispatchers.IO) { EmbeddedMpv.create(preferGpu = settings.player.builtInGpu) })
        } catch (e: Exception) {
            message = "The built-in player couldn't start (${e.message}). Using mpv's window."
            return false
        }
        playerSession = session
        if (session.party == null) attachParty(session)
        val sameTitle = session.target?.videoId == target.videoId
        session.title = label
        session.segments = segments
        session.autoSkip = settings.player.autoSkip
        session.skipButton = settings.player.skipButton
        session.target = target
        session.stream = original
        if (alternatives.isNotEmpty() || !sameTitle) session.sources = alternatives
        if (!sameTitle) session.addonSubtitles = null
        session.upNextDismissed = false
        upNextCancelledFor = null
        session.nextEpisode = target.video?.takeIf { settings.player.autoplayNextEpisode }
            ?.let { WatchHistory.nextEpisode(target.episodes, it, progress.watched.toSet()) }?.takeIf { isAired(it) }
        session.description = (target.video?.overview ?: detailMeta?.takeIf { it.id == target.contentId }?.description)
        val load = ++session.loads
        session.mpv.load(url, label, resume, options, stream.behaviorHints?.proxyHeaders?.request.orEmpty())
        if (session.loads == 1 && settings.player.videoFit != VideoFit.FIT) setFit(settings.player.videoFit)
        follow(
            target,
            serverStream,
            SkipPlanner(segments, settings.player.autoSkip).takeUnless { it.isEmpty },
            afterEnd = { ending, continued ->
                // Played to the end and nothing came next: close, as the mpv window would.
                if (ending.reachedEnd && !continued && stillWatching == null && playerSession === session && session.loads == load) closePlayer()
            }
        ) { onPosition, onSample -> session.mpv.follow(onPosition = partySkips(session, onPosition), onSample = onSample) }
        return true
    }

    // --- Built-in player: switching and panels ---

    /** Plays another source for what's on screen, from where it is now. */
    fun switchSource(ranked: RankedStream) {
        val session = playerSession ?: return
        val target = session.target ?: return
        val at = session.mpv.position.takeIf { it > 5 }?.let { it.toLong().toString() }
        session.panel = null
        launchPlayback(ranked.stream, session.title, target, start = at, builtIn = true, alternatives = session.sources)
    }

    /** Loads the sources for what's playing, for the player's Sources panel. */
    fun loadPlayerSources() {
        val session = playerSession ?: return
        val target = session.target ?: return
        if (session.sources.isNotEmpty() || session.sourcesLoading) return
        session.sourcesLoading = true
        scope.launch {
            try {
                session.sources = rank(streamsFor(target.contentType, target.videoId, target.name))
            } finally {
                session.sourcesLoading = false
            }
        }
    }

    /** Every addon subtitle for what's playing, for the player's Subtitles panel. */
    fun loadAddonSubtitles() {
        val session = playerSession ?: return
        val target = session.target ?: return
        if (session.addonSubtitles != null || ServerItemRef.isServerId(target.contentId)) return
        if (session.addonSubtitlesLoading) return
        session.addonSubtitlesLoading = true
        scope.launch {
            val subs = runCatching {
                withContext(Dispatchers.IO) { client.subtitles(addons.toList(), target.contentType, target.videoId, timeoutMs = 8_000) }
            }.getOrDefault(emptyList())
            session.addonSubtitles = subs.distinctBy { it.second }.map { (lang, url) -> AddonSubtitle(lang, url, "") }
                .sortedWith(compareBy({ !preferredSubtitle(it.language) }, { it.language }))
            session.addonSubtitlesLoading = false
        }
    }

    private fun preferredSubtitle(language: String): Boolean {
        val wanted = listOfNotNull(settings.player.subtitleLanguage ?: Locale.getDefault().language, settings.player.secondarySubtitleLanguage)
        return wanted.any { language in MpvArgs.languageCodes(it).split(',') }
    }

    /** Plays [video] of what's on screen (the player's Episodes panel), its best match first. */
    fun playEpisodeInPlayer(video: Video) {
        val session = playerSession ?: return
        val target = session.target ?: return
        if (!session.controls.mayChangeMedia()) return
        session.panel = null
        scope.launch { startEpisode(target, video, viaUpNext = false) }
    }

    /** The Up Next card's "Play now". */
    fun playNextNow() {
        val session = playerSession ?: return
        val target = session.target ?: return
        if (!session.controls.mayChangeMedia()) return
        val next = session.nextEpisode ?: return
        session.upNextDismissed = true
        autoplayRun++
        scope.launch { startEpisode(target, next, viaUpNext = true) }
    }

    /** The Up Next card's "Cancel": this episode ends without starting the next. */
    fun cancelUpNext() {
        val session = playerSession ?: return
        session.upNextDismissed = true
        upNextCancelledFor = session.target?.videoId
    }

    private var upNextCancelledFor: String? = null

    fun setFit(fit: VideoFit) {
        val session = playerSession ?: return
        session.fit = fit
        session.mpv.setFit(MpvArgs.fitProperties(fit), fill = fit != VideoFit.FIT)
    }

    fun answerStillWatching(keepWatching: Boolean) {
        val prompt = stillWatching ?: return
        stillWatching = null
        if (keepWatching) {
            autoplayRun = 0
            prompt.onContinue()
        } else {
            closePlayer()
        }
    }

    fun answerPlayerChoice(builtIn: Boolean?) {
        playerChoice?.invoke(builtIn)
    }

    private suspend fun streamsFor(type: String, videoId: String, name: String): List<Stream> =
        servers.streams(type, videoId, name, null) +
            withContext(Dispatchers.IO) { client.streams(addons.toList(), type, videoId, timeoutMs = addonTimeoutMs) }.flatMap { it.streams }

    /** Finds and plays [next]'s source for [target]'s show; false when there's none. */
    private suspend fun startEpisode(target: PlaybackTarget, next: Video, viaUpNext: Boolean): Boolean {
        val tag = episodeTag(next.season, next.episode)
        val title = listOfNotNull(tag, next.title.takeIf { it.isNotBlank() }).joinToString(" · ")
        message = if (viaUpNext) "Up Next: $title" else "Loading $title…"
        val ranked = rank(streamsFor(target.contentType, next.id, target.name))
        // "Pick every time" still needs a source for Up Next: it takes the best match.
        val pick = pickAuto(ranked) ?: ranked.firstOrNull { it.isEligible }
        if (pick == null) {
            message = "No source to play ${tag ?: "the next episode"} automatically"
            return false
        }
        val label = listOfNotNull(target.name, tag, next.title.takeIf { it.isNotBlank() }).joinToString(" · ")
        // Stays in the player that's playing: no "Ask every time" between episodes.
        launchPlayback(pick.stream, label, target.copy(video = next), builtIn = playerSession != null, alternatives = ranked)
        return true
    }

    private fun MpvProgress.Sample.percent(): Float =
        if (durationSec > 0) (positionSec / durationSec * 100).toFloat().coerceIn(0f, 100f) else 0f

    private fun recordProgress(target: PlaybackTarget, sample: MpvProgress.Sample) {
        saveProgress(
            WatchHistory.record(
                progress,
                target,
                positionMs = (sample.positionSec * 1000).toLong(),
                durationMs = (sample.durationSec * 1000).toLong(),
                now = System.currentTimeMillis(),
                fromFurthest = settings.nextUpFromFurthest
            )
        )
    }

    /**
     * Up Next: an episode that played to its end starts the next one's best match. Returns true
     * when something else is starting (or waiting on "Are you still watching?").
     */
    private suspend fun playbackEnded(target: PlaybackTarget, ending: MpvProgress.Ending): Boolean {
        val video = target.video ?: return false
        if (playerSession?.controls?.mayChangeMedia(userInitiated = false) == false) return false
        if (!ending.reachedEnd || !settings.player.autoplayNextEpisode) return false
        if (upNextCancelledFor == target.videoId) {
            upNextCancelledFor = null
            return false
        }
        limits.autoplayCap?.let { cap ->
            if (autoplayRun >= cap) {
                message = "Up Next stopped after $cap ${if (cap == 1) "episode" else "episodes"} in a row"
                return false
            }
        }
        currentBlock()?.let {
            screenTimeBlock = it
            return false
        }
        // An episode that hasn't aired yet waits in Continue Watching (or Upcoming) instead.
        val next = WatchHistory.nextEpisode(target.episodes, video, progress.watched.toSet())?.takeIf { isAired(it) } ?: return false
        val player = settings.player
        if (player.stillWatching && autoplayRun >= player.stillWatchingEpisodes.coerceAtLeast(1)) {
            val tag = episodeTag(next.season, next.episode)
            stillWatching = StillWatchingPrompt(
                label = listOfNotNull(target.name, tag).joinToString(" · ")
            ) { scope.launch { startEpisode(target, next, viaUpNext = true) } }
            return true
        }
        autoplayRun++
        return startEpisode(target, next, viaUpNext = true)
    }

    // --- Watch party ---

    /** The watch party (TV: `PartyRepository`), on the sync server's account; see [PartyState]. */
    val party = PartyState(
        parentScope = scope,
        connect = {
            val account = account
            account?.client()?.let { client -> SyncPartyBackend(client, account.httpClient, account.state.originClientId.orEmpty(), scope) }
        },
        signedIn = { account?.signedIn == true },
        profile = { PartyProfile(name = profile.name, imageUrl = null, color = profile.colorHex) },
        capability = { account?.watchPartyCapability() },
        onAuthLost = { scope.launch { account?.sessionExpired() } }
    )

    private var partySyncJob: Job? = null

    /** The party's position for [target] when the party is watching it, as mpv's --start. */
    private fun partyStart(target: PlaybackTarget): String? {
        val current = party.state.value.party ?: return null
        val key = partyMediaKey(target.contentType, target.contentId, target.video?.season, target.video?.episode)
        if (current.media.key != key) return null
        val playback = party.playback.value?.takeIf { it.mediaKey == null || it.mediaKey == key } ?: return null
        val ms = PartySync.expectedPositionMs(playback, party.nowMs())
        return "%.3f".format(Locale.ROOT, ms / 1000.0)
    }

    /** Keeps [session] with the watch party while one is on (the loop idles otherwise). */
    private fun attachParty(session: PlayerSession) {
        val sync = PartyPlayerSync(party, MpvPartyPlayer(session), MpvControls(session.mpv))
        session.party = sync
        session.controls = sync
        partySyncJob?.cancel()
        partySyncJob = scope.launch { sync.run() }
    }

    /**
     * Auto-skips in a party go to everyone, like any seek; a member the host controls leaves
     * them to the host. Called on mpv's thread, so the seek is made on the UI's.
     */
    private fun partySkips(session: PlayerSession, onPosition: (Double) -> MpvProgress.Seek?): (Double) -> MpvProgress.Seek? = { position ->
        val seek = onPosition(position)
        val sync = session.party
        when {
            seek == null || sync == null || party.state.value.party == null || !sync.isSynced -> seek
            sync.isControlLocked -> null
            else -> {
                scope.launch {
                    session.controls.seekTo(seek.toSec)
                    session.mpv.showText(seek.message)
                }
                null
            }
        }
    }

    private fun onPartyEvent(event: PartyEvent) {
        val session = playerSession
        if (event is PartyEvent.MediaChanged && session != null) {
            // The host moved on: the next episode follows straight away; anything else offers
            // "Watch with the party" in the party panel, as on TV.
            val target = session.target
            val next = session.nextEpisode
            val playingKey = target?.let { partyMediaKey(it.contentType, it.contentId, it.video?.season, it.video?.episode) }
            if (event.media.key == playingKey) return
            if (target != null && event.media.metaId == target.contentId && next != null && isAired(next) &&
                next.season == event.media.season && next.episode == event.media.episode
            ) {
                scope.launch { startEpisode(target, next, viaUpNext = false) }
                return
            }
            session.panel = null
            session.partyPanelOpen = true
            return
        }
        if (session == null) message = event.message else session.party?.showMessage(event.message)
    }

    /** Starts a watch party for the title page's title or episode, then plays it in the built-in player. */
    fun startPartyFromDetail() {
        val context = sources ?: return
        val meta = detailMeta?.takeIf { it.id == context.preview.id }
        val video = context.video
        val media = PartyMedia(
            type = context.preview.apiType,
            metaId = context.preview.id,
            metaType = context.preview.apiType,
            videoId = video?.id,
            season = video?.season,
            episode = video?.episode,
            title = meta?.name ?: context.preview.name,
            episodeTitle = video?.title?.takeIf { it.isNotBlank() },
            description = (video?.overview ?: meta?.description ?: context.preview.description)?.take(600),
            poster = meta?.poster ?: context.preview.poster,
            background = meta?.background ?: context.preview.background,
            logo = meta?.logo ?: context.preview.logo,
        )
        scope.launch {
            val error = party.create(media)
            if (error != null) message = error.message else playBest()
        }
    }

    /** Starts a watch party for what the built-in player is playing (the TV's player panel). */
    fun startPartyInPlayer() {
        val session = playerSession ?: return
        val media = MpvPartyPlayer(session).currentMedia() ?: return
        scope.launch {
            val error = party.create(media)
            if (error != null) session.party?.showMessage(error.message)
        }
    }

    /**
     * "Watch with the party": plays what the party is watching in the built-in player: the
     * host's shared stream when there is one, else your source that matches the host's, else
     * your best match. With none, its title page opens to pick one.
     */
    fun watchWithParty(media: PartyMedia) {
        scope.launch {
            val preview = MetaPreview(
                id = media.metaId,
                type = ContentType.fromString(media.metaType),
                rawType = media.metaType,
                name = media.title,
                poster = media.poster,
                posterShape = com.fetch.tv.domain.model.PosterShape.POSTER,
                background = media.background,
                logo = media.logo,
                description = media.description,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            )
            val meta = runCatching { withContext(Dispatchers.IO) { client.meta(addons.toList(), media.metaType, media.metaId) } }.getOrNull()
            if (!allowed(meta, preview)) {
                message = "Not available on ${profile.name}"
                return@launch
            }
            val videos = meta?.videos.orEmpty()
            val video = media.videoId?.let { id -> videos.firstOrNull { it.id == id } }
                ?: videos.firstOrNull { media.season != null && it.season == media.season && it.episode == media.episode }
                ?: media.videoId?.takeIf { media.season != null }?.let {
                    Video(id = it, title = media.episodeTitle.orEmpty(), released = null, thumbnail = null, season = media.season, episode = media.episode, overview = null)
                }
            val target = PlaybackTarget(
                contentId = media.metaId,
                contentType = media.metaType,
                name = meta?.name ?: media.title,
                poster = meta?.poster ?: media.poster,
                backdrop = meta?.background ?: media.background,
                logo = meta?.logo ?: media.logo,
                video = video,
                episodes = videos
            )
            playerSession?.party?.showMessage("Loading ${media.displayTitle}…") ?: run { message = "Loading ${media.displayTitle}…" }
            val ranked = rank(streamsFor(media.metaType, target.videoId, target.name))
            val current = party.state.value.party
            val shared = party.sharedStream.value?.takeIf { current != null && !current.isHost && it.mediaKey == media.key }
            val pick = shared?.toStream()
                ?: ranked.firstOrNull { media.source?.matches(it.stream) == true && ExternalPlayer.canPlay(it.stream) }?.stream
                ?: (pickAuto(ranked) ?: ranked.firstOrNull { it.isEligible })?.stream
            if (pick == null) {
                closePlayer()
                message = "No source to play ${media.displayTitle} automatically. Pick one below."
                navigate(Screen.Detail(preview, videoId = video?.id))
                return@launch
            }
            val label = listOfNotNull(target.name, video?.let { episodeTag(it.season, it.episode) }, video?.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
            launchPlayback(pick, label, target, builtIn = true, alternatives = ranked)
        }
    }

    /** The host's "Switch the party to …": the party follows what this player plays. */
    fun switchPartyToPlayer() {
        val session = playerSession ?: return
        val media = MpvPartyPlayer(session).currentMedia() ?: return
        party.publishMedia(media, positionMs = (session.mpv.position * 1000).toLong())
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        const val MAX_RECENT_SEARCHES = 10
        const val FILTER_ROW_PAGE = 40
        const val AIRING_RECHECK_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * The source Play starts in [mode]: the best match, the first playable source in addon order, or
 * the first whose name or release matches [regex] (any case). Null for manual or no match.
 */
fun pickSource(ranked: List<RankedStream>, mode: AutoPlayMode, regex: String): RankedStream? = when (mode) {
    AutoPlayMode.BEST_MATCH -> ranked.firstOrNull { it.isEligible }
    AutoPlayMode.FIRST_STREAM -> ranked.filter { ExternalPlayer.canPlay(it.stream) }.minByOrNull { it.originalIndex }
    AutoPlayMode.REGEX_MATCH -> {
        val pattern = regex.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        if (pattern == null) null
        else ranked.filter { ExternalPlayer.canPlay(it.stream) }.sortedBy { it.originalIndex }.firstOrNull { r ->
            listOfNotNull(r.stream.name, r.stream.title, r.stream.description, r.stream.behaviorHints?.filename, r.stream.addonName)
                .any { pattern.containsMatchIn(it) }
        }
    }
    AutoPlayMode.MANUAL -> null
}

/** Whether [preview] comes out later: a release date after today, or a year after this one. */
fun isUnreleased(preview: MetaPreview, today: LocalDate = LocalDate.now()): Boolean {
    preview.released?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { return it.isAfter(today) }
    val year = yearOf(preview.releaseInfo) ?: return false
    return year > today.year
}

/** Whether an episode has aired: no date counts as aired. */
fun isAired(video: Video, today: LocalDate = LocalDate.now()): Boolean =
    video.released?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { !it.isAfter(today) } ?: true

/** The first year in a release line such as "2008–2013". */
fun yearOf(releaseInfo: String?): Int? = releaseInfo?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

/** The first episode of the first real season (season 0 holds specials), or null for a movie. */
fun firstEpisode(videos: List<Video>): Video? {
    val episodes = videos.filter { it.season != null }
    if (episodes.isEmpty()) return null
    val season = episodes.mapNotNull { it.season }.filter { it > 0 }.minOrNull() ?: episodes.mapNotNull { it.season }.min()
    return episodes.filter { it.season == season }.minByOrNull { it.episode ?: Int.MAX_VALUE }
}
