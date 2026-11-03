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
import com.fetch.desktop.sync.copyProfileSetupKeepingLimits
import com.fetch.desktop.sync.verifyPin
import com.fetch.desktop.sync.toRemoteSharedSettings
import com.fetch.desktop.sync.SyncAuthException
import com.fetch.desktop.sync.SyncBase
import com.fetch.desktop.sync.SyncMerge
import com.fetch.desktop.servers.ServerStream
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.desktop.servers.ServersStore
import com.fetch.desktop.skip.IntroDbClient
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.ui.extras
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
import kotlinx.coroutines.flow.transformWhile
import com.fetch.desktop.playback.NextEpisodeRules
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
import kotlinx.coroutines.sync.withLock
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
    /** Essential mode's single Playback page (see [com.fetch.desktop.data.ExperiencePolicy]). */
    PLAYBACK_BASICS("Playback basics", "Playback"),
    CONTENT_DISCOVERY("Content & Discovery", "Sources"),
    TMDB("TMDB", "Sources"),
    MDBLIST("MDBList", "Sources"),
    ADDONS("Addons", "Sources", parent = CONTENT_DISCOVERY),
    HOME_CATALOGS("Home catalogs", "Sources", parent = CONTENT_DISCOVERY),
    FILTER_ROWS("Filter rows", "Sources", parent = CONTENT_DISCOVERY),
    COLLECTIONS("Collections", "Sources", parent = CONTENT_DISCOVERY),
    APPEARANCE("Appearance", "Home"),
    LAYOUT("Layout", "Home"),
    MEDIA_SERVERS("Media servers", "Sources", parent = CONTENT_DISCOVERY),
    STREAM_BADGES("Stream badges", "Playback", parent = STREAM_SELECTION),
    ACCOUNT("Account", "Accounts"),
    TRACKING("Tracking", "Accounts"),
    YOUTUBE("YouTube", "Accounts"),
    WATCH_PARTY("Watch party", "Accounts"),
    /** System › Advanced: the Essential / Advanced switch, as on TV. */
    ADVANCED("Advanced", "System"),
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
    /** A collection folder's page (see [com.fetch.desktop.collections.CollectionsState]). */
    data class Folder(val collectionId: String, val folderId: String) : Screen
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

    /** The row's keys for Catalog order: its own, and the TV's (`addonId_type_catalogId`). */
    val ordered: com.fetch.desktop.addons.OrderedCatalog
        get() = com.fetch.desktop.addons.OrderedCatalog(key, if (filter != null) key else com.fetch.desktop.addons.tvCatalogKey(addon.id, catalog.apiType, catalog.id))

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
    val failedAddons: List<String> = emptyList(),
    /** Each addon's answer so far, for the fetching panel (sources arrive as each addon answers). */
    val addons: List<com.fetch.desktop.playback.AddonProgress> = emptyList(),
    /** Not asked yet: "Show the pick on the Play button" is off, so Play asks the addons. */
    val idle: Boolean = false
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
    private val client: AddonClient = AddonClient.create().apply { health = com.fetch.desktop.addons.AddonHealthTracker.shared },
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

    /** Queue, "Prefer this next time", content warnings, the movie's recommendation; see [PlaybackExtras]. */
    val extras = PlaybackExtras(scope, object : PlaybackExtras.Host {
        override val settings: DesktopSettings get() = this@AppState.settings
        override fun updateSettings(change: (DesktopSettings) -> DesktopSettings) = this@AppState.updateSettings(change)
        override fun message(text: String) { message = text }
        override val tmdb: TmdbClient? get() = this@AppState.tmdb.client.takeIf { this@AppState.tmdb.active && settings.tmdb.useMoreLikeThis }
        override val tmdbLanguage: String get() = settings.tmdb.language
        override fun allowed(preview: MetaPreview): Boolean = this@AppState.allowed(preview)
        override fun isWatched(id: String): Boolean = this@AppState.isWatched(id)
        override fun rating(target: PlaybackTarget): String? {
            val meta = detailMeta?.takeIf { it.id == target.contentId }
            return ratingOf(meta?.imdbId ?: target.contentId, target.contentType, meta?.ageRating, meta?.country).first
        }
    })

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

    /** The titles a parent hid from this profile or allowed on it. */
    val titleLists: com.fetch.desktop.parental.ProfileTitleLists get() = profiles.titleLists.listsFor(profile.id)

    /**
     * Whether this profile may see [preview]: never when a parent hid it, always when they allowed
     * it, otherwise by its rating (the addon's, else TMDB's).
     */
    fun allowed(preview: MetaPreview): Boolean {
        val lists = titleLists
        if (lists.isHidden(preview.id, preview.imdbId, preview.apiType)) return false
        if (limits.maxRating == null || lists.isAllowed(preview.id, preview.imdbId, preview.apiType)) return true
        val (rating, country) = ratingOf(preview.imdbId ?: preview.id, preview.apiType, preview.ageRating, preview.country)
        return Parental.allows(limits, rating, country)
    }

    fun allowed(meta: Meta?, preview: MetaPreview): Boolean {
        val lists = titleLists
        val imdb = meta?.imdbId ?: preview.imdbId
        if (lists.isHidden(preview.id, imdb, preview.apiType)) return false
        if (limits.maxRating == null || lists.isAllowed(preview.id, imdb, preview.apiType)) return true
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
    val tracking = TrackingState(
        scope, TrackingStore(File(profileDir(profile), "tracking.json")), onMessage = { message = it },
        animeIdPreference = { settings.tracking.animeId },
        mdbList = { mdbListTracker }
    )

    /** MDBList: the account's lists (Settings › MDBList) and ratings on title pages and the Home hero. */
    private val mdbListHttp = com.fetch.desktop.mdblist.MdbListHttp(okhttp3.OkHttpClient())
    val mdbListAccount = com.fetch.desktop.mdblist.MdbListAccountState(
        scope,
        com.fetch.desktop.mdblist.mdbListAccountFile(profileDir(profile)),
        com.fetch.desktop.mdblist.MdbListAccountClient(mdbListHttp),
        onMessage = { message = it }
    )

    /** MDBList's history, resume points and scrobbling for the connected account (in [tracking]). */
    val mdbListTracker = com.fetch.desktop.mdblist.MdbListTracker(mdbListAccount, com.fetch.desktop.mdblist.MdbListSyncClient(mdbListHttp))

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
        mdblist = mdbListAccount,
        onLocalListRenamed = { id, name -> collections.renameLocalList(id, name) }
    )

    /** Collections: Home rows of folders, each a page of sources, synced with the TV. */
    val collections: com.fetch.desktop.collections.CollectionsState = com.fetch.desktop.collections.CollectionsState(
        scope,
        com.fetch.desktop.collections.CollectionsStore.inProfile(profileDir(profile)),
        com.fetch.desktop.collections.CollectionSourceResolver(
            addons = { activeAddons },
            disabledAddons = { addons.filter { !it.enabled } },
            addonClient = client,
            tmdb = com.fetch.desktop.tmdb.TmdbCollectionSources(tmdb.client),
            tmdbLanguage = { settings.tmdb.language },
            trakt = { id, type, page, limit, sortBy, sortHow ->
                tracking.withTraktPublic { publicListItems(it, id.toString(), type, page, limit, sortBy, sortHow) }
            },
            localLists = { lists.local },
            websiteList = { id, types -> com.fetch.desktop.website.FetchWebsiteState.shared.listItems(profile.id, id, types) }
        ),
        addons = { activeAddons },
        remote = { account?.client() },
        profileId = profile.id,
        onMessage = { message = it }
    )

    /** Home's extras: Ready tonight, Continue Watching artwork, TMDB on the hero, shuffle. */
    val homeExtras = com.fetch.desktop.home.HomeExtras(
        scope, client, { activeAddons },
        com.fetch.desktop.tmdb.MetaPreviewEnricher(tmdb.client) { settings.tmdb.language },
        { settings }, profileDir(profile),
        rankedSources = { type, id, name -> rank(streamsFor(type, id, name)) },
        autoPlayIsBestMatch = { settings.autoPlayMode == AutoPlayMode.BEST_MATCH }
    )

    /** The title page's IMDb episode ratings grid and Trakt comments. */
    val detailExtras = com.fetch.desktop.detail.DetailExtras(scope, tmdb.client, { tracking.credentials.traktClientId })

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
                syncProviderCredentials(remote)
                pullHomeCatalogs(remote)
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

                // Collections: the server's copy wins, as on TV; ours go up when it has none.
                try {
                    collections.sync(remote)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SyncAuthException) {
                    throw e
                } catch (e: Exception) {
                    message = "Couldn't sync collections: ${e.message}"
                }

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

    private val providerCredentialBase = com.fetch.desktop.sync.providerCredentialBaseFile(profileDir(profile))

    /** MDBList's API key with the account, as the TV's provider credential sync does. */
    private suspend fun syncProviderCredentials(remote: com.fetch.desktop.sync.SyncClient) {
        val key = try {
            com.fetch.desktop.sync.ProviderCredentialSync.sync(
                rpc = { name, params -> remote.callRpc(name, params) },
                profileId = profile.id,
                localMdbListKey = settings.mdblist.apiKey,
                base = providerCredentialBase,
                originClientId = account?.state?.originClientId
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncAuthException) {
            throw e
        } catch (_: Exception) {
            return // An older server without provider credentials: keys stay per device.
        }
        if (key != settings.mdblist.apiKey) {
            applyingFromServer = true
            try {
                updateSettings { it.copy(mdblist = it.mdblist.copy(apiKey = key)) }
            } finally {
                applyingFromServer = false
            }
        }
    }

    /** The server keeps positions but not titles or artwork; fill them from the addons. */
    private suspend fun fillProgressNames() {
        progress.entries.filter { it.name == it.contentId }.forEach { entry ->
            val meta = runCatching {
                withContext(Dispatchers.IO) { client.meta(activeAddons, entry.contentType, entry.contentId) }
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
            // Settings › Tracking › Watch progress source: whose resume points join Continue Watching.
            val playback = com.fetch.desktop.tracking.playbackForSource(
                snapshot.playback, settings.tracking.progress, com.fetch.desktop.ui.availableProgressSources(this@AppState)
            )
            saveProgress(WatchHistory.mergeRemote(progress, playback, snapshot.watchedIds))
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
            override fun openBrowse(target: com.fetch.desktop.youtube.YouTubeBrowseTarget) = navigate(Screen.YouTubeBrowse(target))
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
        // The fetching panel first: Play stops waiting, the page stays.
        if (extras.autoPlayWaiting) return stopAutoPlayWait()
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
        stopAutoPlayWait()
        when (to) {
            is Screen.Detail -> {
                autoPlayPending = to.autoPlay
                loadDetail(to.preview, to.videoId)
            }
            is Screen.Person -> tmdb.loadPerson(to.tmdbId)
            is Screen.Entity -> tmdb.openEntity(to.kind, to.tmdbId, to.name)
            is Screen.Folder -> collections.openFolder(to.collectionId, to.folderId)
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
        if (!applyingFromServer && (after.catalogOrder != before.catalogOrder || after.pinnedCatalogs != before.pinnedCatalogs ||
                after.disabledCatalogs != before.disabledCatalogs || after.hideUnreleased != before.hideUnreleased ||
                after.layout.homeOrder != before.layout.homeOrder)) {
            pushHomeCatalogs()
        }
        // A new MDBList key goes to the account straight away, as on TV.
        if (after.mdblist.apiKey != before.mdblist.apiKey && !applyingFromServer) {
            account?.client()?.let { remote -> scope.launch { runCatching { syncProviderCredentials(remote) } } }
        }
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

    /**
     * The sync server's side of "Copy settings" (RPC `sync_copy_profile_setup`), run after the
     * copy on this computer, as the TV does it: [source]'s pending changes are pushed first, the
     * server copies the blob, then [target]'s own limits go back (the copy brings the source's).
     * When [target] is this profile its settings are pulled again. Signed out, nothing reaches the
     * server: the copy stays on this computer, and the account's settings win on the next sync.
     */
    fun copyProfileSetupOnServer(source: DesktopProfile, target: DesktopProfile, copyMissingKeys: Boolean) {
        val remote = account?.client() ?: return
        if (source.id == target.id) return
        // The local copy just queued this profile's settings push; the server copy replaces the
        // whole blob, so that push must not land after it. Kept to re-queue if the copy fails.
        val pendingBase = if (target.id == profile.id) {
            settingsPushJob?.cancel()
            settingsBeforePush.also { settingsBeforePush = null }
        } else null
        scope.launch {
            try {
                if (source.id == profile.id && settingsBeforePush != null) {
                    val from = settingsBeforePush
                    settingsPushJob?.cancel()
                    settingsBeforePush = null
                    from?.let { settings.toRemoteSharedSettings().changedFrom(it.toRemoteSharedSettings()) }
                        ?.let { remote.pushSharedSettings(source.id, it) }
                }
                val current = profiles.profiles.firstOrNull { it.id == target.id } ?: target
                remote.copyProfileSetupKeepingLimits(profiles, source, current, copyMissingKeys)
                if (target.id == profile.id) {
                    settingsPushJob?.cancel()
                    settingsBeforePush = null
                    remote.pullSharedSettings(target.id)?.let { shared ->
                        applyingFromServer = true
                        try {
                            updateSettings { shared.applyTo(it) }
                        } finally {
                            applyingFromServer = false
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingBase?.let(::queueSettingsPush)
                message = "Settings copied on this computer, but the sync server didn't copy them: ${e.message}"
            }
        }
    }

    /** The profile whose addons this one uses on the server (profile 1's when shared). */
    private val addonProfileId: Int get() = if (profile.usesPrimaryAddons) DesktopProfile.PRIMARY_ID else profile.id

    /** Sends the addon list to the server after an install, removal or reorder here. */
    /** Orders addon pushes, so an older list can't reach the server after a newer one. */
    private val addonPushLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Sends the addon list to the server. The list and switches are read when the push runs, under
     * [addonPushLock], so the last push always carries the latest state. [removed] are addons
     * removed here, kept out of the push even when the account has them switched off.
     */
    private fun pushAddonsToServer(removed: Set<String> = emptySet()) {
        val remote = account?.client() ?: return
        // A profile sharing the main profile's addons doesn't push them, as on TV.
        if (!profile.isPrimary && profile.usesPrimaryAddons) return
        scope.launch {
            addonPushLock.withLock {
                runCatching {
                    val pulled = remote.pullAddons(profile.id)
                    val urls = addons.map { it.baseUrl } + unreachableAddons.map { it.first }
                    remote.pushAddons(profile.id, SyncMerge.addonsToPush(urls, pulled, disabledAddonUrls(), removed))
                }
            }
        }
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
        val localDisabled = store.loadDisabled()
        val addonMerge = SyncMerge.addons(localUrls, remoteAddons, localDisabled = localDisabled)
        val listChanged = addonMerge.local.map(SyncMerge::addonKey) != localUrls.map(SyncMerge::addonKey)
        val switchesChanged = addonMerge.disabled.map(SyncMerge::addonKey).toSet() != localDisabled.map(SyncMerge::addonKey).toSet()
        if (listChanged || switchesChanged) {
            runCatching { store.save(addonMerge.local, addonMerge.disabled) }
                .onFailure { message = "Couldn't save the addon list from your account: ${it.message}" }
            reloadAddons()
        }

        // Profiles, locks and limits are the main profile's to sync.
        if (!profile.isPrimary) return
        // Limits a server-side settings copy replaced go back first, so they aren't tightened to the source's.
        for ((id, owed) in profiles.file.pendingLimitsPush) {
            val p = profiles.profiles.firstOrNull { it.id == id }
            if (p == null || p.isPrimary) {
                profiles.setPendingLimits(id, null)
                continue
            }
            runCatching { remote.pushParentalLimits(id, SyncMerge.parentalLimitsToPush(owed, null)) }
                .onSuccess { profiles.setPendingLimits(id, null) }
        }
        // Still owed after a failed retry: the account holds the copy's source limits, so keep the saved ones.
        val stillOwed = profiles.file.pendingLimitsPush
        val profileMerge = SyncMerge.profiles(profiles.profiles, remote.pullProfiles())
        val remoteLocked = SyncMerge.lockedOnlyRemotely(profileMerge.local, remote.pullProfileLocks()).toSet()
        val merged = profileMerge.local.map { p ->
            val limits = when {
                p.isPrimary -> p.limits
                p.id in stillOwed -> stillOwed.getValue(p.id)
                else -> SyncMerge.parentalLimits(p.limits, remote.pullParentalLimits(p.id))
            }
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

        override suspend fun verifyRemotePin(profile: DesktopProfile, pin: String): PinGate.Result? =
            account?.client()?.verifyPin(profile, pin)
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
        // Read after the fetches, so a switch flipped while they ran isn't undone here.
        val disabled = store.loadDisabled()
        addons.clear()
        unreachableAddons.clear()
        results.forEach { (url, result) ->
            result.fold(
                onSuccess = { addons.add(it.copy(enabled = url !in disabled)) },
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
            // The link from an addon's configure page replaces the addon in place (TV: installOrReplace),
            // keeping its order: one Configure was opened for, or one that can't work until configured.
            val replaced = addons.indexOfFirst { it.id == addon.id && (configuring.remove(it.id) || it.needsConfiguration) }
            if (replaced >= 0) {
                val old = addons[replaced]
                addons[replaced] = addon.copy(enabled = old.enabled)
                rows.removeAll { it.addon.baseUrl == old.baseUrl }
                persist()
                pushAddonsToServer()
                message = "Updated ${addon.displayName}"
                addonsChanged()
                return@launch
            }
            addons.add(addon)
            persist()
            pushAddonsToServer()
            message = "Installed ${addon.displayName}"
            addonsChanged()
        }
    }

    /** The installed addons that are switched on: the ones streams, catalogs, meta and subtitles come from. */
    val activeAddons: List<Addon> get() = addons.filter { it.enabled }

    /** Installed addons switched off, by saved URL; unreachable ones keep what was saved for them. */
    private fun disabledAddonUrls(): Set<String> {
        val unreachableDisabled = store.loadDisabled().filter { url -> unreachableAddons.any { it.first == url } }
        return addons.filter { !it.enabled }.map { it.baseUrl }.toSet() + unreachableDisabled
    }

    /** The TV's per-addon enable switch: a switched-off addon stays installed but is never asked. */
    fun setAddonEnabled(addon: Addon, enabled: Boolean) {
        val index = addons.indexOfFirst { it.baseUrl == addon.baseUrl }
        if (index < 0 || addons[index].enabled == enabled) return
        addons[index] = addons[index].copy(enabled = enabled)
        persist()
        pushAddonsToServer()
        addonsChanged()
        // Search results come from the switched-on addons only.
        if (searchQuery.isNotBlank()) search(searchQuery)
    }

    fun removeAddon(addon: Addon) {
        addons.remove(addon)
        persist()
        pushAddonsToServer(removed = setOf(addon.baseUrl))
        rows.removeAll { it.addon.baseUrl == addon.baseUrl }
        discover.setAddons(activeAddons, settings.discoverCatalog)
    }

    /** The addon's own settings page, for addons that say they're configurable. */
    fun configureUrl(addon: Addon): String = com.fetch.tv.core.addon.addonConfigureUrl(addon.baseUrl)

    /** Addons whose configure page is open: the link it sends back replaces them. */
    private val configuring = mutableSetOf<String>()

    fun configureAddon(addon: Addon) {
        configuring += addon.id
        openInBrowser(configureUrl(addon))
        message = "Opened ${addon.displayName}'s settings in your browser. Its Install button sends the new link back to Fetch."
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
            store.save(urls, disabledAddonUrls())
        } catch (e: Exception) {
            message = "Couldn't save the addon list: ${e.message}"
        }
    }

    private fun addonsChanged() {
        reloadRows()
        discover.setAddons(activeAddons, settings.discoverCatalog)
    }

    // --- Home ---

    /** The rows Home shows: pinned catalogs first in pin order, then the rest, minus disabled ones. */
    val homeRows: List<CatalogRow>
        get() {
            val visible = rows.filter { !com.fetch.desktop.addons.HomeCatalogOrder.isDisabled(settings, it.ordered) }
            // Pinned rows first, then the catalog order (synced with the TV), then the rest in addon
            // order. "Follow addons order" keeps addon order (pins still lead). Collections are
            // placed among these by Layout's Home order (HomeLayout).
            val ordered = if (settings.layout.followAddonsOrder) {
                visible.filter { it.key in settings.pinnedCatalogs }.sortedBy { settings.pinnedCatalogs.indexOf(it.key) } +
                    visible.filter { it.key !in settings.pinnedCatalogs }
            } else com.fetch.desktop.addons.HomeCatalogOrder.ordered(visible, settings, { it.key }, { it.ordered.tvKey })
            // A rating limit hides what it rules out, as on TV, and so does "Hide unreleased".
            val hideUnreleased = settings.hideUnreleased
            if (limits.maxRating == null && titleLists.hidden.isEmpty() && !hideUnreleased) return ordered
            return ordered.map { row -> row.copy(items = row.items.filter { allowed(it) && !(hideUnreleased && isUnreleased(it)) }) }
        }

    fun setCatalogPinned(key: String, pinned: Boolean) = updateSettings { s ->
        s.copy(pinnedCatalogs = if (pinned) (s.pinnedCatalogs - key) + key else s.pinnedCatalogs - key)
    }

    fun setCatalogEnabled(key: String, enabled: Boolean) = updateSettings { s ->
        val row = rows.firstOrNull { it.key == key }
        if (row != null) return@updateSettings com.fetch.desktop.addons.HomeCatalogOrder.setEnabled(s, row.ordered, enabled)
        s.copy(disabledCatalogs = if (enabled) s.disabledCatalogs - key else (s.disabledCatalogs - key) + key)
    }

    // --- Home catalog order and its sync (the TV's HomeCatalogSettingsSyncService) ---

    private var homeCatalogRemote: kotlinx.serialization.json.JsonObject? = null
    private var homeCatalogPush: Job? = null

    /** Home's addon catalogs (not filter rows), as the TV's catalog sync names them. */
    val syncableCatalogs: List<com.fetch.desktop.addons.SyncableCatalog>
        get() = rows.filter { it.filter == null }.map { com.fetch.desktop.addons.SyncableCatalog(it.key, it.addon.id, it.catalog.apiType, it.catalog.id) }

    /** At start-up the account's catalog order comes down (pull only, as on TV). */
    private suspend fun pullHomeCatalogs(remote: com.fetch.desktop.sync.SyncClient) {
        val json = runCatching {
            com.fetch.desktop.addons.HomeCatalogSync.settingsJsonOf(remote.callRpc("sync_pull_home_catalog_settings", com.fetch.desktop.addons.HomeCatalogSync.pullParams(profile.id)))
        }.getOrNull() ?: return
        homeCatalogRemote = json
        val (payload, hasHide) = com.fetch.desktop.addons.HomeCatalogSync.decode(json) ?: return
        applyingFromServer = true
        try {
            updateSettings { com.fetch.desktop.addons.HomeCatalogSync.apply(it, payload, hasHide) }
        } finally {
            applyingFromServer = false
        }
    }

    /** A change to Home's catalogs here goes to the account half a second later. */
    private fun pushHomeCatalogs() {
        val remote = account?.client() ?: return
        homeCatalogPush?.cancel()
        homeCatalogPush = scope.launch {
            delay(500)
            val previous = homeCatalogRemote?.let(com.fetch.desktop.addons.HomeCatalogSync::decode)?.first
            val payload = com.fetch.desktop.addons.HomeCatalogSync.payload(syncableCatalogs, settings, previous, collections.collections.map { it.id })
            val json = com.fetch.desktop.addons.HomeCatalogSync.merged(homeCatalogRemote, payload)
            runCatching {
                remote.callRpc("sync_push_home_catalog_settings", com.fetch.desktop.addons.HomeCatalogSync.pushParams(profile.id, json, account?.state?.originClientId))
                homeCatalogRemote = json
            }
        }
    }

    private fun reloadRows() {
        val wanted = activeAddons.flatMap { addon ->
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
        val generation = ++rowsGeneration
        wanted.forEach { row ->
            scope.launch {
                val updated = try {
                    row.copy(items = withContext(Dispatchers.IO) { client.catalog(row.addon, row.catalog, extra = row.extra) }, loading = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    row.copy(loading = false, error = e.message ?: "Couldn't load")
                }
                // A newer reload replaced these rows; its own request fills them.
                if (generation != rowsGeneration) return@launch
                val index = rows.indexOfFirst { it.key == row.key }
                if (index >= 0) rows[index] = updated
            }
        }
    }

    private var rowsGeneration = 0

    // --- Links (fetch://, stremio://) ---

    /** A party code from a `fetch://party/…` link, for Settings › Watch party to fill in. */
    var pendingPartyCode: String? by mutableStateOf(null)

    /**
     * Opens a link as the TV's MainActivity does: a title's page, an addon to install (not on
     * a restricted profile), or the watch party join page with the code filled in.
     */
    fun openDeepLink(url: String) {
        when (val link = com.fetch.desktop.deeplink.DeepLinkParser.parse(url)) {
            is com.fetch.desktop.deeplink.AppDeepLink.Meta -> navigate(
                Screen.Detail(
                    MetaPreview(
                        id = link.id, type = ContentType.fromString(link.type), name = "", poster = null,
                        posterShape = com.fetch.tv.domain.model.PosterShape.POSTER, background = null, logo = null,
                        description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
                        imdbId = link.id.takeIf { it.startsWith("tt") }
                    )
                )
            )
            is com.fetch.desktop.deeplink.AppDeepLink.AddonInstall -> if (limits.isRestricted) {
                message = "Addons can't be installed on ${profile.name}"
            } else {
                openSection(Screen.Settings(SettingsPage.ADDONS))
                message = "Installing the addon…"
                installAddon(com.fetch.tv.core.addon.normalizeAddonInstallUrl(link.manifestUrl) ?: link.manifestUrl)
            }
            is com.fetch.desktop.deeplink.AppDeepLink.Party -> {
                pendingPartyCode = link.code
                openSection(Screen.Settings(SettingsPage.WATCH_PARTY))
            }
            null -> message = "Fetch can't open that link"
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
        get() = WatchHistory.continueWatchingRows(
            com.fetch.desktop.tracking.withinWindow(
                com.fetch.desktop.parental.visibleProgress(progress, titleLists),
                settings.tracking.continueWatchingDays, System.currentTimeMillis()
            ),
            LocalDate.now(), settings.showUnairedNextUp, settings.continueWatchingSort
        )

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
            val meta = runCatching { withContext(Dispatchers.IO) { client.meta(activeAddons, type, id) } }.getOrNull() ?: continue
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
        // MDBList's resume point goes too, as on TV, so the next sync doesn't bring it back.
        if (entry.source == "mdblist") scope.launch { mdbListTracker.clear(entry) }
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
            val meta = runCatching { withContext(Dispatchers.IO) { client.meta(activeAddons, preview.rawType, preview.id) } }.getOrNull()
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
    private fun searchableRows(): List<CatalogRow> = activeAddons.flatMap { addon ->
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
                else -> withContext(Dispatchers.IO) {
                    client.meta(com.fetch.desktop.home.metaAddonOrder(activeAddons, preview, settings.layout.preferExternalMeta), preview.rawType, preview.id)
                }
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
                    !isSeries -> offerSources(preview)
                    episode != null -> offerSources(preview, episode)
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
        val serverItem = ServerItemRef.isServerId(videoId)
        val asked = if (serverItem) emptyList() else client.streamAddons(activeAddons, preview.rawType, videoId)
        sources = SourcesState(
            preview = preview, video = video, videoId = videoId, label = label, episodeTag = tag,
            addons = com.fetch.desktop.playback.FetchProgress.start(asked.map { it.displayName })
        )
        // Play pressed before the sources: the last link that worked, or the fetching panel's wait.
        if (autoPlayPending && !reuseLastLink()) startAutoPlayWait()
        sourcesJob = scope.launch {
            // Addons and your own servers are asked at once; each answer is ranked in as it comes
            // (the TV's fetching panel), and the ranker puts your copies first.
            var own = emptyList<Stream>()
            val results = mutableListOf<com.fetch.desktop.data.AddonStreamResult>()
            suspend fun publish(done: Boolean) {
                val streams = own + results.flatMap { it.streams }
                val ranked = rank(streams)
                val current = sources?.takeIf { it.videoId == videoId } ?: return
                var progress = current.addons
                if (done) progress = com.fetch.desktop.playback.FetchProgress.finished(progress)
                sources = current.copy(
                    loading = !done,
                    streams = streams,
                    ranked = ranked,
                    failedAddons = results.filter { it.error != null }.map { "${it.addon.displayName}: ${it.error}" },
                    addons = progress
                )
                checkAutoPlay()
            }
            if (serverItem) {
                own = servers.streamsForItem(videoId)
            } else coroutineScope {
                launch {
                    own = servers.streams(preview.rawType, videoId, preview.name, yearOf(preview.releaseInfo))
                    if (own.isNotEmpty()) publish(done = false)
                }
                client.streamsAsAnswered(activeAddons, preview.rawType, videoId, timeoutMs = addonRequestTimeoutMs).collect { result ->
                    results += result
                    sources?.takeIf { it.videoId == videoId }?.let { s ->
                        sources = s.copy(addons = com.fetch.desktop.playback.FetchProgress.answered(s.addons, result))
                    }
                    publish(done = false)
                }
            }
            publish(done = true)
            // What Best match picked, for Ready tonight and the Home hero's spec line.
            sources?.takeIf { it.videoId == videoId }?.let { homeExtras.saveBestMatch(preview.apiType, videoId, it.best) }
        }
    }

    /** A title page's sources: asked for now, or left for Play when "Show the pick on the Play button" is off. */
    private fun offerSources(preview: MetaPreview, video: Video? = null) {
        if (settings.autoPlay.bestMatchPreview || autoPlayPending) return loadSources(preview, video)
        sourcesJob?.cancel()
        val tag = video?.let { episodeTag(it.season, it.episode) }
        val label = video?.let { v -> listOfNotNull(tag, v.title.takeIf { it.isNotBlank() }).joinToString(" · ") }?.let { "${preview.name} · $it" } ?: preview.name
        sources = SourcesState(preview, video, video?.id ?: preview.id, label, tag, loading = false, idle = true)
    }

    /** How long one addon's request may take (Settings › Stream Selection › Stream selection timeout). */
    private val addonRequestTimeoutMs: Long get() = com.fetch.desktop.data.AutoPlaySettings.requestTimeoutMs(settings.addonTimeoutSeconds)

    // --- Auto-play: the fetching panel's wait (the TV's direct auto-play) ---

    private val linkCache = com.fetch.desktop.playback.StreamLinkCache(profileDir(profile))
    private var autoPlayTimer: Job? = null
    private var autoPlayWaitOver = false

    /** The binge group to look for first, when Reuse Binge Group remembers one for this show. */
    private fun rememberedBingeGroup(contentId: String): com.fetch.desktop.playback.BingeHint? {
        val ap = settings.autoPlay
        return if (ap.reuseBingeGroup && ap.preferBingeGroup) linkCache.bingeGroup(contentId) else null
    }

    /** Starts waiting for the addons as the TV does: Instant, a few seconds, or Unlimited (every addon). */
    private fun startAutoPlayWait() {
        val current = sources ?: return
        if (settings.autoPlayMode == AutoPlayMode.MANUAL && rememberedBingeGroup(current.preview.id) == null) {
            // "Pick every time": the sources open and fill in as the addons answer.
            stopAutoPlayWait()
            sourcesPanelOpen = true
            return
        }
        val wait = settings.addonTimeoutSeconds
        autoPlayTimer?.cancel()
        autoPlayWaitOver = wait == 0
        extras.autoPlayWaiting = true
        extras.autoPlayDeadlineMs = if (com.fetch.desktop.data.AutoPlaySettings.isBoundedTimeout(wait)) System.currentTimeMillis() + wait * 1000L else null
        if (com.fetch.desktop.data.AutoPlaySettings.isBoundedTimeout(wait)) {
            autoPlayTimer = scope.launch {
                delay(wait * 1000L)
                autoPlayWaitOver = true
                extras.autoPlayDeadlineMs = null
                checkAutoPlay()
            }
        }
        checkAutoPlay()
    }

    private fun stopAutoPlayWait() {
        autoPlayPending = false
        autoPlayTimer?.cancel()
        autoPlayWaitOver = false
        extras.autoPlayWaiting = false
        extras.autoPlayDeadlineMs = null
    }

    /** Runs auto-play's rules on the sources in so far; see [com.fetch.desktop.playback.AutoPlayPicker.decide]. */
    private fun checkAutoPlay() {
        if (!autoPlayPending) return
        val current = sources ?: return
        if (current.idle) return
        val ap = settings.autoPlay
        val decision = com.fetch.desktop.playback.AutoPlayPicker.decide(
            ranked = current.ranked,
            mode = settings.autoPlayMode,
            regex = settings.autoPlayRegex,
            rules = settings.rankingRules,
            startEarly = ap.startEarly,
            bingeHint = rememberedBingeGroup(current.preview.id),
            next = current.video?.let { it.season to (it.episode ?: 0) },
            anyAnswered = current.ranked.isNotEmpty() || current.addons.any { it.status != com.fetch.desktop.playback.AddonFetchStatus.LOADING },
            allAnswered = !current.loading,
            waitOver = autoPlayWaitOver
        )
        when (decision) {
            is com.fetch.desktop.playback.AutoPlayDecision.Play -> {
                stopAutoPlayWait()
                play(decision.pick, manual = false)
            }
            com.fetch.desktop.playback.AutoPlayDecision.ShowSources -> {
                stopAutoPlayWait()
                sourcesPanelOpen = true
                message = if (current.ranked.isEmpty() && !current.loading) "No sources found" else "No source to play automatically. Pick one below."
            }
            com.fetch.desktop.playback.AutoPlayDecision.Wait -> Unit
        }
    }

    /** The fetching panel's "Play now": the best so far, without waiting for the rest. */
    fun playBestSoFar() {
        val pick = sources?.best ?: return
        stopAutoPlayWait()
        play(pick, manual = false)
    }

    /** The fetching panel's "See all sources". */
    fun showAllSourcesNow() {
        stopAutoPlayWait()
        sourcesPanelOpen = true
    }

    /** "Reuse Last Link": plays the link that last worked for these sources' video, if it's fresh enough. */
    private fun reuseLastLink(): Boolean {
        val ap = settings.autoPlay
        val current = sources ?: return false
        if (!ap.reuseLastLink || ServerItemRef.isServerId(current.videoId)) return false
        val link = linkCache.validLink(current.videoId, ap.reuseLastLinkCacheHours) ?: return false
        // Not a link from an addon that is switched off now.
        if (activeAddons.none { it.displayName == link.addonName } && addons.any { !it.enabled && it.displayName == link.addonName }) return false
        stopAutoPlayWait()
        playStream(link.toStream(), builtIn = null)
        return true
    }

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
        if (current.idle) {
            // Not asked yet ("Show the pick on the Play button" is off): ask now, then play.
            autoPlayPending = true
            loadSources(current.preview, current.video)
            return
        }
        if (current.loading) {
            if (autoPlayPending) return
            autoPlayPending = true
            if (!reuseLastLink()) startAutoPlayWait()
            return
        }
        if (reuseLastLink()) return
        val remembered = rememberedBingeGroup(current.preview.id)
            ?.let { com.fetch.desktop.playback.AutoPlayPicker.bingeMatch(current.ranked, it, null, current.video?.let { v -> v.season to (v.episode ?: 0) }) }
        val pick = remembered ?: pickAuto(current.ranked)
        if (pick != null) play(pick, manual = false) else sourcesPanelOpen = true
    }

    /** Plays a trailer in the built-in player when it's the Default Player, else in mpv's window. */
    fun playTrailer(ytId: String, title: String) = trailers.play(ytId, title)

    private val trailers by lazy { com.fetch.desktop.trailer.TrailerPlayer(scope, youtube.host) }

    /** Plays [ranked] for the title or episode the sources are for, resuming where it was left. */
    fun play(ranked: RankedStream, builtIn: Boolean? = null, manual: Boolean = true) {
        val context = sources ?: return
        // Picked by hand over Best match's choice: offer "Prefer this next time" once it plays.
        if (manual) {
            com.fetch.tv.core.streams.ranking.PreferNextTime.recordManualPick(
                ranked.stream,
                context.streams.filter(ExternalPlayer::canPlay),
                settings.rankingRules,
                com.fetch.tv.core.streams.ranking.DeviceDecodeCapabilities.Permissive
            )
        }
        playStream(ranked.stream, builtIn)
    }

    /** Plays [stream] for the title or episode the sources are for. */
    private fun playStream(stream: Stream, builtIn: Boolean?) {
        stopAutoPlayWait()
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
        launchPlayback(stream, context.label, target, builtIn = builtIn, alternatives = context.ranked)
    }

    /** The stream playing (or last played), for the next episode's binge group. */
    private var playingStream: Stream? = null

    /**
     * Bookkeeping as [stream] starts for [target]: Reuse Last Link and the binge group remember
     * it, the queue lets go of it, and "Prefer this next time" is offered if it was picked by hand.
     */
    private fun startedPlaying(stream: Stream, target: PlaybackTarget) {
        playingStream = stream
        linkCache.saveLink(target.videoId, stream)
        if (stream.serverTarget == null) linkCache.saveBingeGroup(target.contentId, stream)
        extras.markStarted(target.videoId)
        extras.clearPostPlay(exceptVideoId = target.videoId)
        extras.offerPreferNextTime()
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
        startedPlaying(stream, target)
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
            client.subtitles(activeAddons, target.contentType, target.videoId, timeoutMs = 4_000)
        }.filter { (lang, _) -> lang in wanted }.map { it.second }.distinct().take(3)
    }

    /** Intro, recap and credits times for [target] from IntroDB, AniSkip and Anime-Skip; empty when unknown. */
    private suspend fun segmentsFor(target: PlaybackTarget): List<Segment> {
        val video = target.video
        return withTimeoutOrNull(6_000) {
            skipSources.segments(target.contentId, video?.id, video?.season, video?.episode, isMovie = target.contentType == "movie")
        }.orEmpty()
    }

    private val skipSources by lazy { com.fetch.desktop.skip.defaultSkipSources { settings.player } }

    // --- Skip markers: IntroDB submitting and AniSkip "wrong marker" reports, as on TV ---

    /** Where a marker recorded on [target] goes on IntroDB; null when it has no IMDb id there. */
    suspend fun markerTargetFor(target: PlaybackTarget): com.fetch.desktop.skip.MarkerTarget? {
        val video = target.video
        return withTimeoutOrNull(6_000) {
            skipSources.markerTarget(target.contentId, video?.id, video?.season, video?.episode, isMovie = target.contentType == "movie")
        }
    }

    /**
     * The player's Mark button: the first press records where the intro (a movie's credits)
     * starts, the next sends start-to-here to IntroDB with your API key.
     */
    fun markSkipSegment(session: PlayerSession) {
        val apiKey = settings.player.introDbApiKey?.takeIf { it.isNotBlank() } ?: return
        val target = session.target ?: return
        val marker = session.extras.markerTarget ?: return
        val mpv = session.mpv
        when (val press = com.fetch.desktop.skip.SkipMarkerRules.press(session.extras.markerStartSec, mpv.position)) {
            is com.fetch.desktop.skip.SkipMarkerRules.Press.Start -> {
                session.extras.markerStartSec = press.atSec
                mpv.showText("Start marked. Press again where it ends.")
            }
            is com.fetch.desktop.skip.SkipMarkerRules.Press.Submit -> {
                session.extras.markerStartSec = null
                val type = com.fetch.desktop.skip.SkipMarkerRules.markedType(marker.isMovie)
                scope.launch {
                    val ok = skipSources.submitMarker(apiKey, target.contentId, marker, type, press.startSec, press.endSec)
                    mpv.showText(if (ok) "Marker sent to IntroDB" else "Couldn't send the marker to IntroDB")
                }
            }
        }
    }

    /** Reports the Skip button's [segment] as wrong to AniSkip, and stops offering it. */
    fun reportSkipSegment(session: PlayerSession, segment: Segment) {
        if (!com.fetch.desktop.skip.SkipMarkerRules.canReport(segment)) return
        session.segments = session.segments - segment
        scope.launch {
            val ok = skipSources.reportWrong(segment)
            session.mpv.showText(if (ok) "Reported the wrong marker. Thanks!" else "Couldn't report the marker")
        }
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
                    { position -> skipper?.at(position)?.let { MpvProgress.Seek(it.targetSec, "Skipped ${it.displayLabel}") } }
                ) { sample ->
                    scope.launch {
                        // Screen time counts what actually played, not seeks.
                        lastPosition?.let { previous ->
                            val played = sample.positionSec - previous
                            if (played in 0.0..30.0) countWatched((played * 1000).toLong())
                        }
                        lastPosition = sample.positionSec
                        recordProgress(target, sample)
                        // A movie's recommendation is looked up before its end (mpv's window too).
                        if (target.video == null && com.fetch.desktop.playback.PostPlayTiming.shouldPrefetch(sample.positionSec, sample.durationSec, emptyList(), settings.autoPlay)) {
                            extras.prefetchPostPlay(target)
                        }
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
        extras.playerClosed()
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
        // The card (and with autoplay, the next episode) comes at the TV's threshold.
        session.upNextAt = { position, duration -> NextEpisodeRules.thresholdReached(position, duration, session.segments, settings.autoPlay) }
        session.nextEpisode?.let { extras.seedNextEpisode(target, it) }
        extras.startGuide(target)
        val load = ++session.loads
        session.mpv.load(url, label, resume, options, stream.behaviorHints?.proxyHeaders?.request.orEmpty())
        if (session.loads == 1 && settings.player.videoFit != VideoFit.FIT) setFit(settings.player.videoFit)
        val watch = PositionWatch(session, target, load)
        follow(
            target,
            serverStream,
            SkipPlanner(segments, settings.player.autoSkip).takeUnless { it.isEmpty },
            afterEnd = end@{ ending, continued ->
                // A source that wouldn't open: the next one in the ranking, when that's on.
                if (!ending.reachedEnd && ending.last == null && session.mpv.error != null && playerSession === session &&
                    session.loads == load && tryNextSource(session, target, original)
                ) return@end
                // Played to the end and nothing came next: close, as the mpv window would.
                if (ending.reachedEnd && !continued && stillWatching == null && playerSession === session && session.loads == load) closePlayer()
            }
        ) { onPosition, onSample ->
            val skips = partySkips(session, onPosition)
            session.mpv.follow(onPosition = { position -> watch.on(position); skips(position) }, onSample = onSample)
        }
        return true
    }

    /**
     * Follows the built-in player's position (on mpv's thread) for what the TV does near the end:
     * preloading the next episode's sources, starting Up Next at the threshold, and looking up a
     * movie's recommendation. Nothing fires until the position was once away from the end.
     */
    private inner class PositionWatch(val session: PlayerSession, val target: PlaybackTarget, val load: Int) {
        @Volatile private var armed = false
        @Volatile private var preloaded = false
        @Volatile private var upNextStarted = false
        @Volatile private var recommendationLooked = false

        fun on(position: Double) {
            val duration = session.mpv.duration
            if (duration <= 0 || session.loads != load) return
            val ap = settings.autoPlay
            val segments = session.segments
            if (!armed) {
                if (!NextEpisodeRules.isAwayFromEnd(position, duration, segments, ap)) return
                armed = true
            }
            val next = session.nextEpisode
            if (next != null) {
                if (ap.preloadNextEpisode && !preloaded && NextEpisodeRules.shouldPreload(position, duration, segments, ap, settings.addonTimeoutSeconds)) {
                    preloaded = true
                    scope.launch { preloadEpisode(target, next) }
                }
                if (!upNextStarted && !session.upNextDismissed && NextEpisodeRules.thresholdReached(position, duration, segments, ap)) {
                    upNextStarted = true
                    scope.launch { upNextAtThreshold(session, target, load) }
                }
            } else if (target.video == null && !recommendationLooked && com.fetch.desktop.playback.PostPlayTiming.shouldPrefetch(position, duration, segments, ap)) {
                recommendationLooked = true
                scope.launch { extras.prefetchPostPlay(target) }
            }
        }
    }

    /** Up Next at the threshold in the built-in player: the next episode starts now, as on TV. */
    private suspend fun upNextAtThreshold(session: PlayerSession, target: PlaybackTarget, load: Int) {
        if (playerSession !== session || session.loads != load || session.upNextDismissed) return
        if (!settings.player.autoplayNextEpisode || upNextCancelledFor == target.videoId) return
        val video = target.video ?: return
        if (!continueToNext(target, video, countdown = true)) {
            // Nothing started (no source, a limit): don't try again at the end.
            if (playerSession === session && session.loads == load) upNextCancelledFor = target.videoId
        }
    }

    /** The next episode's sources, looked up ahead ("Preload Next Episode Sources"). */
    private var preloadedEpisode: Pair<String, kotlinx.coroutines.Deferred<List<RankedStream>>>? = null

    private fun preloadEpisode(target: PlaybackTarget, next: Video) {
        if (preloadedEpisode?.first == next.id) return
        preloadedEpisode = next.id to scope.async { rank(streamsFor(target.contentType, next.id, target.name)) }
    }

    /** Sources that failed to open in the built-in player for the title on screen. */
    private val failedSources = mutableSetOf<String>()
    private var failedFor: String? = null

    /** The built-in player couldn't open [failed]: the next eligible source plays, from where it was. */
    private fun tryNextSource(session: PlayerSession, target: PlaybackTarget, failed: Stream): Boolean {
        linkCache.forgetLink(target.videoId)
        if (!settings.autoPlay.nextSourceOnFailure) return false
        if (failedFor != target.videoId) {
            failedFor = target.videoId
            failedSources.clear()
        }
        failedSources += failed.stableKey()
        val candidates = session.sources.ifEmpty { sources?.takeIf { it.videoId == target.videoId }?.ranked.orEmpty() }
        val next = candidates.firstOrNull { it.isEligible && ExternalPlayer.canPlay(it.stream) && it.stream.stableKey() !in failedSources } ?: return false
        message = "That source didn't open. Trying ${next.stream.addonName.ifBlank { "the next one" }}…"
        launchPlayback(next.stream, session.title, target, builtIn = true, alternatives = candidates)
        return true
    }

    // --- Built-in player: switching and panels ---

    /** Plays another source for what's on screen, from where it is now. */
    fun switchSource(ranked: RankedStream) {
        val session = playerSession ?: return
        val target = session.target ?: return
        val at = session.mpv.position.takeIf { it > 5 }?.let { it.toLong().toString() }
        session.panel = null
        com.fetch.tv.core.streams.ranking.PreferNextTime.recordManualPick(
            ranked.stream, session.sources.map { it.stream }.filter(ExternalPlayer::canPlay), settings.rankingRules,
            com.fetch.tv.core.streams.ranking.DeviceDecodeCapabilities.Permissive
        )
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
                session.sources = rank(streamsFor(target.contentType, target.videoId, target.name, complete = true))
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
                withContext(Dispatchers.IO) { client.subtitlesBySource(activeAddons, target.contentType, target.videoId, timeoutMs = 8_000) }
            }.getOrDefault(emptyList())
            session.addonSubtitles = subs.distinctBy { it.url }.map { AddonSubtitle(it.language, it.url, it.addon) }
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

    /**
     * Sources for a video outside the title page (Up Next, the queue, a watch party). As on TV
     * they're taken when the stream selection timeout ends, or at the first answer with sources
     * when it's Instant; [complete] waits for every addon (the player's Sources panel).
     */
    private suspend fun streamsFor(type: String, videoId: String, name: String, complete: Boolean = false): List<Stream> = coroutineScope {
        val own = async { servers.streams(type, videoId, name, null) }
        val wait = settings.addonTimeoutSeconds
        val collected = mutableListOf<Stream>()
        val answers = client.streamsAsAnswered(activeAddons, type, videoId, timeoutMs = addonRequestTimeoutMs)
        when {
            complete || wait == com.fetch.desktop.data.AutoPlaySettings.TIMEOUT_UNLIMITED -> answers.collect { collected += it.streams }
            wait == 0 -> answers.transformWhile { emit(it); it.streams.isEmpty() }.collect { collected += it.streams }
            else -> withTimeoutOrNull(wait * 1000L) { answers.collect { collected += it.streams } }
        }
        own.await() + collected
    }

    /**
     * Finds and plays [next]'s source for [target]'s show; false when there's none. The binge
     * group of what played comes first, as on TV. With [countdown] (Up Next at the threshold) the
     * card counts down three seconds before it switches, and Cancel stops it.
     */
    private suspend fun startEpisode(target: PlaybackTarget, next: Video, viaUpNext: Boolean, countdown: Boolean = false): Boolean {
        val tag = episodeTag(next.season, next.episode)
        val title = listOfNotNull(tag, next.title.takeIf { it.isNotBlank() }).joinToString(" · ")
        val inPlayer = playerSession
        if (countdown && inPlayer != null) extras.upNextStatus = "Finding a source…"
        else message = if (viaUpNext) "Up Next: $title" else "Loading $title…"
        val preloaded = preloadedEpisode?.takeIf { it.first == next.id }?.second
        preloadedEpisode = null
        val ranked = preloaded?.let { runCatching { it.await() }.getOrNull() } ?: rank(streamsFor(target.contentType, next.id, target.name))
        val ap = settings.autoPlay
        val playing = playingStream
        // "Pick every time" still needs a source for Up Next: it takes the best match.
        val pick = com.fetch.desktop.playback.AutoPlayPicker.nextEpisodePick(
            ranked = ranked,
            mode = settings.autoPlayMode,
            regex = settings.autoPlayRegex,
            hint = playing?.let(com.fetch.desktop.playback.BingeHint::of) ?: rememberedBingeGroup(target.contentId),
            playingFilename = playing?.behaviorHints?.filename,
            preferBingeGroup = ap.preferBingeGroup,
            fallback = ap.nextEpisodeFallback,
            current = target.video?.let { it.season to (it.episode ?: 0) },
            next = next.season to (next.episode ?: 0)
        )
        if (pick == null) {
            extras.upNextStatus = null
            if (ranked.none { it.isEligible }) {
                message = "No source to play ${tag ?: "the next episode"} automatically"
                return false
            }
            // "Fallback when binge group fails" is off: the sources for that episode, as the TV's picker.
            message = "No source from the same group for ${tag ?: "the next episode"}. Pick one."
            closePlayer()
            navigate(Screen.Detail(target.toPreview(), videoId = next.id))
            sourcesPanelOpen = true
            return true
        }
        if (countdown && inPlayer != null) {
            val from = pick.stream.addonName.ifBlank { null }
            for (seconds in 3 downTo 1) {
                extras.upNextStatus = listOfNotNull("Starting in $seconds", from).joinToString(" · ")
                delay(1_000)
                if (playerSession !== inPlayer || upNextCancelledFor == target.videoId || inPlayer.upNextDismissed) {
                    extras.upNextStatus = null
                    return false
                }
            }
        }
        extras.upNextStatus = null
        val label = listOfNotNull(target.name, tag, next.title.takeIf { it.isNotBlank() }).joinToString(" · ")
        // Stays in the player that's playing: no "Ask every time" between episodes.
        launchPlayback(pick.stream, label, target.copy(video = next), builtIn = playerSession != null, alternatives = ranked)
        return true
    }

    private fun PlaybackTarget.toPreview(): MetaPreview = MetaPreview(
        id = contentId,
        type = ContentType.fromString(contentType),
        rawType = contentType,
        name = name,
        poster = poster,
        posterShape = com.fetch.tv.domain.model.PosterShape.POSTER,
        background = backdrop,
        logo = logo,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

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
        if (playerSession?.controls?.mayChangeMedia(userInitiated = false) == false) return false
        if (!ending.reachedEnd) return false
        val video = target.video
        val hasNext = video != null && WatchHistory.nextEpisode(target.episodes, video, progress.watched.toSet())?.takeIf { isAired(it) } != null
        // Nothing of this show comes next: the queue plays on, else a movie's recommendation shows.
        if (!hasNext) return queueOrRecommendation(target)
        if (!settings.player.autoplayNextEpisode) return false
        if (upNextCancelledFor == target.videoId) {
            upNextCancelledFor = null
            return false
        }
        return continueToNext(target, video!!, countdown = false)
    }

    /** At the end of something with no next episode: the queue's next item, or the movie's recommendation card. */
    private fun queueOrRecommendation(target: PlaybackTarget): Boolean {
        val head = extras.upcoming(target.videoId).firstOrNull()
        if (head != null && settings.player.autoplayNextEpisode && upNextCancelledFor != target.videoId) {
            if (currentBlock() != null) return false
            playQueueItem(head)
            return true
        }
        upNextCancelledFor = null
        if (target.video != null) return false
        val inPlayer = playerSession?.target?.videoId == target.videoId
        return extras.onMovieEnded(target.videoId, inPlayer) { playRecommendationTrailer(it) }
    }

    /** Starts [video]'s next episode as Up Next does: the autoplay cap, limits and "Are you still watching?" first. */
    private suspend fun continueToNext(target: PlaybackTarget, video: Video, countdown: Boolean): Boolean {
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
        return startEpisode(target, next, viaUpNext = true, countdown = countdown)
    }

    // --- Playback queue ---

    /** Adds [preview] (a movie, or a show's next episode to watch) to the queue. */
    fun addToQueue(preview: MetaPreview) {
        if (preview.type != ContentType.SERIES) {
            extras.add(com.fetch.desktop.playback.QueueItem(
                contentId = preview.id, contentType = preview.apiType, videoId = preview.id, name = preview.name,
                poster = preview.poster, backdrop = preview.background, logo = preview.logo
            ))
            return
        }
        scope.launch {
            // The episode Continue Watching would play, else the first one.
            val meta = detailMeta?.takeIf { it.id == preview.id }
                ?: runCatching { withContext(Dispatchers.IO) { client.meta(activeAddons, preview.rawType, preview.id) } }.getOrNull()
            val videos = meta?.videos.orEmpty()
            val entry = progressFor(preview.id)
            val video = entry?.let { e -> videos.firstOrNull { it.id == e.videoId } } ?: firstEpisode(videos)
            if (video == null) {
                message = "No episodes to queue for ${preview.name}"
                return@launch
            }
            addEpisodeToQueue(preview, video, meta)
        }
    }

    /** Continue Watching's "Add to queue" / "Remove from queue". */
    fun toggleQueued(entry: ProgressEntry) {
        if (extras.isQueued(entry.videoId)) return extras.remove(entry.videoId)
        extras.add(com.fetch.desktop.playback.QueueItem(
            contentId = entry.contentId, contentType = entry.contentType, videoId = entry.videoId, name = entry.name,
            season = entry.season, episode = entry.episode, episodeTitle = entry.episodeTitle,
            poster = entry.poster, backdrop = entry.backdrop, logo = entry.logo
        ))
    }

    /** Adds one episode of [preview] to the queue. */
    fun addEpisodeToQueue(preview: MetaPreview, video: Video, meta: com.fetch.tv.domain.model.Meta? = detailMeta?.takeIf { it.id == preview.id }) {
        extras.add(com.fetch.desktop.playback.QueueItem(
            contentId = preview.id, contentType = preview.apiType, videoId = video.id, name = meta?.name ?: preview.name,
            season = video.season, episode = video.episode, episodeTitle = video.title.takeIf { it.isNotBlank() },
            poster = video.thumbnail ?: meta?.poster ?: preview.poster, backdrop = meta?.background ?: preview.background, logo = meta?.logo ?: preview.logo
        ))
    }

    /** Plays [item]: in place when it's the next episode of what's on, otherwise its best match. */
    fun playQueueItem(item: com.fetch.desktop.playback.QueueItem) {
        extras.queuePanelOpen = false
        val session = playerSession
        val target = session?.target
        if (session != null && !session.controls.mayChangeMedia()) return
        if (session != null && target != null && item.contentId == target.contentId) {
            target.episodes.firstOrNull { it.id == item.videoId }?.let { video ->
                session.upNextDismissed = true
                scope.launch { startEpisode(target, video, viaUpNext = true) }
                return
            }
        }
        scope.launch { playQueued(item) }
    }

    private suspend fun playQueued(item: com.fetch.desktop.playback.QueueItem) {
        message = "Loading ${item.label}…"
        val meta = runCatching { withContext(Dispatchers.IO) { client.meta(activeAddons, item.contentType, item.contentId) } }.getOrNull()
        val videos = meta?.videos.orEmpty()
        val video = videos.firstOrNull { it.id == item.videoId }
            ?: videos.firstOrNull { item.season != null && it.season == item.season && it.episode == item.episode }
            ?: item.videoId.takeIf { it != item.contentId }?.let {
                Video(id = it, title = item.episodeTitle.orEmpty(), released = null, thumbnail = null, season = item.season, episode = item.episode, overview = null)
            }
        val target = PlaybackTarget(
            contentId = item.contentId,
            contentType = item.contentType,
            name = meta?.name ?: item.name,
            poster = meta?.poster ?: item.poster,
            backdrop = meta?.background ?: item.backdrop,
            logo = meta?.logo ?: item.logo,
            video = video,
            episodes = videos
        )
        val preview = target.toPreview()
        if (!allowed(meta, preview)) {
            message = "Not available on ${profile.name}"
            return
        }
        val ranked = rank(streamsFor(item.contentType, target.videoId, target.name))
        val pick = pickAuto(ranked) ?: ranked.firstOrNull { it.isEligible }
        if (pick == null) {
            closePlayer()
            message = "No source to play ${item.label} automatically. Pick one below."
            navigate(Screen.Detail(preview, videoId = video?.id))
            return
        }
        val label = listOfNotNull(target.name, video?.let { episodeTag(it.season, it.episode) }, video?.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
        autoplayRun = 0
        launchPlayback(pick.stream, label, target, builtIn = if (playerSession != null) true else null, alternatives = ranked)
    }

    // --- The movie's recommendation ---

    /** Plays the recommendation's trailer in the built-in player, under the card (as the TV's docked window). */
    private fun playRecommendationTrailer(recommendation: com.fetch.desktop.playback.PostPlayRecommendation) {
        val ytId = recommendation.trailerYtId ?: return
        val session = playerSession ?: return playTrailer(ytId, recommendation.title)
        extras.trailerStarted()
        session.segments = emptyList()
        session.nextEpisode = null
        session.upNextAt = null
        session.title = "${recommendation.title} (trailer)"
        val load = ++session.loads
        session.mpv.load(
            "https://www.youtube.com/watch?v=$ytId",
            session.title,
            null,
            listOf("--ytdl=yes", "--ytdl-format=${com.fetch.desktop.youtube.YouTubePlayback.ytdlFormat(1080)}"),
            emptyMap()
        )
        scope.launch {
            withContext(Dispatchers.IO) { session.mpv.follow { } }
            if (playerSession === session && session.loads == load) extras.trailerEnded()
        }
    }

    /** The card's Play: the recommendation's page, starting its best match (a show opens its page). */
    fun playRecommendation(recommendation: com.fetch.desktop.playback.PostPlayRecommendation, details: Boolean = false) {
        closePlayer()
        extras.clearPostPlay()
        val preview = recommendation.preview
        navigate(Screen.Detail(preview, autoPlay = !details && preview.type != ContentType.SERIES))
    }

    /** The card's trailer button. */
    fun recommendationTrailer(recommendation: com.fetch.desktop.playback.PostPlayRecommendation) {
        val ytId = recommendation.trailerYtId ?: return
        if (playerSession != null && extras.postPlay?.inPlayer == true) playRecommendationTrailer(recommendation)
        else playTrailer(ytId, recommendation.title)
    }

    /** The card's Back: before the end, the movie plays on; after it, the player closes. */
    fun closeRecommendation() {
        val session = playerSession
        val ended = session == null || session.mpv.duration <= 0 || session.mpv.duration - session.mpv.position < 1.0 || extras.postPlay?.trailerPlayed == true
        extras.dismissPostPlay()
        if (ended && extras.postPlay?.inPlayer == true) closePlayer()
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
        profile = {
            val current = profiles.profiles.firstOrNull { it.id == profile.id } ?: profile
            PartyProfile(name = current.name, imageUrl = com.fetch.desktop.data.AvatarCatalog.shareableUrl(current, account?.state?.server?.baseUrl), color = current.colorHex)
        },
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
            val meta = runCatching { withContext(Dispatchers.IO) { client.meta(activeAddons, media.metaType, media.metaId) } }.getOrNull()
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
