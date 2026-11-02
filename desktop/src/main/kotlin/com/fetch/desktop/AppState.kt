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
    ADDONS("Addons", "Sources", parent = CONTENT_DISCOVERY),
    HOME_CATALOGS("Home catalogs", "Sources", parent = CONTENT_DISCOVERY),
    APPEARANCE("Appearance", "Home"),
    LAYOUT("Layout", "Home"),
    MEDIA_SERVERS("Media servers", "Sources", parent = CONTENT_DISCOVERY),
    STREAM_BADGES("Stream badges", "Playback", parent = STREAM_SELECTION),
    ACCOUNT("Account", "Accounts"),
    TRACKING("Tracking", "Accounts"),
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
}

/** A home row: one addon catalog and what it returned. */
data class CatalogRow(
    val addon: Addon,
    val catalog: CatalogDescriptor,
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
) {
    val key: String get() = "${addon.baseUrl}|${catalog.apiType}|${catalog.id}"

    /** The row's heading, as the TV app writes it: "Popular - Movies", or just "Popular". */
    fun title(showType: Boolean): String {
        val name = catalog.name.replaceFirstChar { it.uppercase() }
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

    /** A one-line message for the snackbar, cleared once shown. */
    var message: String? by mutableStateOf(null)

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

    /** Fusion stream badges (Settings › Stream Selection › Stream badges), per profile. */
    val badges = StreamBadgesState(scope, File(profileDir(profile), "stream-badges.json"))

    /** Trakt and Simkl for this profile. */
    val tracking = TrackingState(scope, TrackingStore(File(profileDir(profile), "tracking.json")), onMessage = { message = it })

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
        scope.launch { reloadAddons() }
        syncAccount()
        scope.launch { syncTracking() }
        servers.refreshRows()
        syncServers()
    }

    /** Pulls Continue Watching, watched history and watchlists from Trakt and Simkl. */
    fun syncTracking() {
        scope.launch {
            val snapshot = tracking.sync() ?: return@launch
            saveProgress(WatchHistory.mergeRemote(progress, snapshot.playback, snapshot.watchedIds))
            remoteLists = snapshot.watchlists
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
        if (to is Screen.Detail) {
            autoPlayPending = to.autoPlay
            loadDetail(to.preview, to.videoId)
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
                message = "${addon.displayName} must be configured on its own page first, then install the link it gives you."
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
            // A rating limit hides what it rules out, as on TV.
            return if (limits.maxRating == null) ordered else ordered.map { row -> row.copy(items = row.items.filter(::allowed)) }
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
                .map { CatalogRow(addon, it) }
        }
        rows.clear()
        rows.addAll(wanted)
        wanted.forEach { row ->
            scope.launch {
                val updated = try {
                    row.copy(items = withContext(Dispatchers.IO) { client.catalog(row.addon, row.catalog) }, loading = false)
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

    // --- Continue Watching ---

    val continueWatching: List<ProgressEntry> get() = WatchHistory.continueWatching(progress)

    /** The saved position for this title, when it is in progress or has a next episode waiting. */
    fun progressFor(contentId: String): ProgressEntry? =
        progress.entries.firstOrNull { it.contentId == contentId && !it.hidden }

    fun resumeEntry(videoId: String): ProgressEntry? =
        progress.entries.firstOrNull { it.videoId == videoId && !it.upNext && WatchHistory.resumeStart(progress, videoId) != null }

    fun openContinueWatching(entry: ProgressEntry, play: Boolean) {
        navigate(Screen.Detail(entry.toPreview(), autoPlay = play, videoId = entry.videoId))
    }

    fun removeFromContinueWatching(entry: ProgressEntry) {
        saveProgress(WatchHistory.hide(progress, entry.contentId))
        message = "Removed ${entry.name} from Continue Watching"
    }

    fun startFromBeginning(entry: ProgressEntry) {
        saveProgress(WatchHistory.clearPosition(progress, entry.contentId))
        navigate(Screen.Detail(entry.toPreview(), autoPlay = true, videoId = entry.videoId))
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

    // --- Detail and sources ---

    private fun loadDetail(preview: MetaPreview, videoId: String?) {
        detailMeta = null
        sources = null
        sourcesJob?.cancel()
        detailLoading = true
        scope.launch {
            // A title only on your media server comes from the server, not the addons.
            val meta = if (ServerItemRef.isServerId(preview.id)) {
                servers.details(preview.id)
            } else {
                withContext(Dispatchers.IO) { client.meta(addons.toList(), preview.rawType, preview.id) }
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
                val fromAddons = async(Dispatchers.IO) { client.streams(addons.toList(), preview.rawType, videoId) }
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
                val best = loaded.best
                if (best != null) play(best) else message = "No source to play automatically. Pick one below."
            }
        }
    }

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
            message = "Starting the best match as soon as your addons answer…"
            return
        }
        val best = current.best
        if (best != null) play(best) else sourcesPanelOpen = true
    }

    fun playTrailer(ytId: String, title: String) {
        when (val result = ExternalPlayer.playUrl("https://www.youtube.com/watch?v=$ytId", "$title (trailer)")) {
            is ExternalPlayer.Result.Started -> message = "Opening the trailer in ${result.player}"
            is ExternalPlayer.Result.Failed -> message = result.message
        }
    }

    /** Plays [ranked] for the title or episode the sources are for, resuming where it was left. */
    fun play(ranked: RankedStream) {
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
        launchPlayback(ranked.stream, context.label, target)
    }

    /**
     * Starts mpv with the player settings and any addon subtitles, resuming from the saved
     * position, then follows it for Continue Watching and Up Next.
     */
    private fun launchPlayback(stream: Stream, label: String, target: PlaybackTarget) {
        currentBlock()?.let {
            screenTimeBlock = it
            return
        }
        scope.launch {
            val player = settings.player
            val serverStream = servers.serverStream(stream)
            // Fetch's own position first; otherwise where the server says you stopped.
            val resume = WatchHistory.resumeStart(progress, target.videoId)
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
            if (player.builtInPlayer && LibMpv.available &&
                playEmbedded(played, label, target, resume, options, serverStream, segments)
            ) return@launch
            val skipper = SkipPlanner(segments, player.autoSkip).takeUnless { it.isEmpty }
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

    /** Saves mpv's position as it plays, for Continue Watching and resume, then handles Up Next. */
    /** IntroDB's intro, recap and credits times for [target]; empty when unknown. */
    private suspend fun segmentsFor(target: PlaybackTarget): List<Segment> {
        val imdb = target.contentId.takeIf { it.startsWith("tt") } ?: return emptyList()
        val video = target.video
        return withTimeoutOrNull(4_000) { introDb.segments(imdb, video?.season, video?.episode) }.orEmpty()
    }

    private val introDb by lazy { IntroDbClient(okhttp3.OkHttpClient()) }

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
        afterEnd: suspend (MpvProgress.Ending) -> Unit = {},
        run: suspend (onPosition: (Double) -> MpvProgress.Seek?, onSample: (MpvProgress.Sample) -> Unit) -> MpvProgress.Ending
    ) {
        scope.launch {
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
            playbackEnded(target, ending)
            afterEnd(ending)
            syncAccount()
        }
    }

    // --- Built-in player ---

    /** The built-in player while it's open; null shows the app. */
    var playerSession: PlayerSession? by mutableStateOf(null)
        private set

    /** Closes the built-in player; the position up to now is saved as it stops. */
    fun closePlayer() {
        val session = playerSession ?: return
        playerSession = null
        session.mpv.stop()
        scope.launch {
            // Let the stop reach Continue Watching, then free libmpv.
            delay(1_500)
            withContext(Dispatchers.IO) { session.mpv.close() }
        }
    }

    private suspend fun playEmbedded(
        stream: Stream,
        label: String,
        target: PlaybackTarget,
        resume: String?,
        options: List<String>,
        serverStream: ServerStream?,
        segments: List<Segment>
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
        session.title = label
        session.segments = segments
        session.autoSkip = settings.player.autoSkip
        val load = ++session.loads
        session.mpv.load(url, label, resume, options, stream.behaviorHints?.proxyHeaders?.request.orEmpty())
        follow(
            target,
            serverStream,
            SkipPlanner(segments, settings.player.autoSkip).takeUnless { it.isEmpty },
            afterEnd = { ending ->
                // Played to the end and nothing came next: close, as the mpv window would.
                if (ending.reachedEnd && playerSession === session && session.loads == load) closePlayer()
            }
        ) { onPosition, onSample -> session.mpv.follow(onPosition = onPosition, onSample = onSample) }
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
                now = System.currentTimeMillis()
            )
        )
    }

    /** Up Next: an episode that played to its end starts the next one's best match. */
    private suspend fun playbackEnded(target: PlaybackTarget, ending: MpvProgress.Ending) {
        val video = target.video ?: return
        if (!ending.reachedEnd || !settings.player.autoplayNextEpisode) return
        limits.autoplayCap?.let { cap ->
            if (autoplayRun >= cap) {
                message = "Up Next stopped after $cap ${if (cap == 1) "episode" else "episodes"} in a row"
                return
            }
        }
        currentBlock()?.let {
            screenTimeBlock = it
            return
        }
        autoplayRun++
        val next = WatchHistory.nextEpisode(target.episodes, video, progress.watched.toSet()) ?: return
        val tag = episodeTag(next.season, next.episode)
        message = "Up Next: ${listOfNotNull(tag, next.title.takeIf { it.isNotBlank() }).joinToString(" · ")}"
        val streams = servers.streams(target.contentType, next.id, target.name, null) +
            withContext(Dispatchers.IO) { client.streams(addons.toList(), target.contentType, next.id) }.flatMap { it.streams }
        val best = rank(streams).firstOrNull { it.isEligible }
        if (best == null) {
            message = "No source to play ${tag ?: "the next episode"} automatically"
            return
        }
        val label = listOfNotNull(target.name, tag, next.title.takeIf { it.isNotBlank() }).joinToString(" · ")
        launchPlayback(best.stream, label, target.copy(video = next))
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

/** The first year in a release line such as "2008–2013". */
fun yearOf(releaseInfo: String?): Int? = releaseInfo?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

/** The first episode of the first real season (season 0 holds specials), or null for a movie. */
fun firstEpisode(videos: List<Video>): Video? {
    val episodes = videos.filter { it.season != null }
    if (episodes.isEmpty()) return null
    val season = episodes.mapNotNull { it.season }.filter { it > 0 }.minOrNull() ?: episodes.mapNotNull { it.season }.min()
    return episodes.filter { it.season == season }.minByOrNull { it.episode ?: Int.MAX_VALUE }
}
