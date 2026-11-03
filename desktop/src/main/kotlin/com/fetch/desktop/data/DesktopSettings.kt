package com.fetch.desktop.data

import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.domain.model.AppTheme
import com.fetch.desktop.skip.SegmentType
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Everything the Settings page changes, saved as `settings.json`. Field names follow the TV
 * app's settings so the two read the same.
 */
@Serializable
data class DesktopSettings(
    /** How a stream is picked when you press Play, shared with the TV app's ranker. */
    val rankingRules: StreamRankingRules = DesktopDefaults.rankingRules,
    /** Accent theme, by [AppTheme] name. */
    val theme: String = AppTheme.FETCH.name,
    val showContinueWatching: Boolean = true,
    /** Layout › Continue Watching: Next Up for episodes that haven't aired yet (TV default on). */
    val showUnairedNextUp: Boolean = true,
    /** Next Up follows the furthest episode watched, not the latest (off for rewatches). */
    val nextUpFromFurthest: Boolean = true,
    /** Default (by recency), streaming style (upcoming last) or a separate Upcoming row. */
    val continueWatchingSort: com.fetch.tv.domain.model.ContinueWatchingSortMode = com.fetch.tv.domain.model.ContinueWatchingSortMode.DEFAULT,
    /** "Show Catalog Type": the " - Movies" suffix on row titles. */
    val showCatalogType: Boolean = true,
    /** "Show Addon Name" beside each row title. */
    val showAddonName: Boolean = true,
    /** "Show Poster Labels": the title and year under posters. */
    val showPosterLabels: Boolean = true,
    /** "Discover Location": inside Search (the TV default) or its own sidebar entry. */
    val discoverInSidebar: Boolean = false,
    /** Home catalogs by row key: pinned ones lead in pin order, disabled ones are hidden. */
    val pinnedCatalogs: List<String> = emptyList(),
    val disabledCatalogs: List<String> = emptyList(),
    /** The catalog Discover last showed, restored on the next visit. */
    val discoverCatalog: String? = null,
    /** Audio, Subtitles, Player and Up Next: how mpv is started. */
    val player: PlayerSettings = PlayerSettings(),
    /** Your own TMDB key (Settings › Sources › TMDB), used before the build's. */
    val tmdbApiKey: String? = null,
    /** Appearance › Ambient Mode: artwork or a clock after the window sits idle, as on TV. */
    val ambient: AmbientSettings = AmbientSettings(),
    /** Settings › TMDB: enrichment from The Movie Database, as on TV (synced with it). */
    val tmdb: TmdbSettings = TmdbSettings(),
    /** Settings › MDBList: ratings from MDBList on title pages and the Home hero, as on TV (synced with it, but the key). */
    val mdblist: com.fetch.desktop.mdblist.MdbListSettings = com.fetch.desktop.mdblist.MdbListSettings(),
    /** Settings › YouTube, as on TV (not synced: the TV keeps them per profile on the device). */
    val youtube: com.fetch.desktop.youtube.YouTubeSettings = com.fetch.desktop.youtube.YouTubeSettings(),
    /** "Stream selection": what Play does once the sources are in, as on TV. */
    val autoPlayMode: AutoPlayMode = AutoPlayMode.BEST_MATCH,
    /** For [AutoPlayMode.REGEX_MATCH]: the first source whose text matches this plays. */
    val autoPlayRegex: String = "",
    /** How long to wait for each addon's sources, in seconds (TV: autoplay timeout). */
    val addonTimeoutSeconds: Int = 10,
    /** Search terms, newest first, as on the TV's Search page. */
    val recentSearches: List<String> = emptyList(),
    /** Hide catalog titles that haven't come out yet (Layout › Home). */
    val hideUnreleased: Boolean = false,
    /** About › Updates: look for a newer Fetch on GitHub at start-up. */
    val checkForUpdates: Boolean = true,
    /** Include beta releases in the update check. */
    val betaUpdates: Boolean = false,
    /** Appearance: Increase contrast, AMOLED, the Custom theme's colours and the font (TV `theme_settings`). */
    val appearance: AppearanceSettings = AppearanceSettings(),
    /** Essential or Advanced (TV `experience_settings`). A file from before it reads as Advanced; a fresh install has none yet. */
    val experience: ExperienceSettings = ExperienceSettings.Advanced,
    /** Up Next threshold, Reuse Last Link, binge groups, post-play and the rest of the TV's auto-play settings. */
    val autoPlay: AutoPlaySettings = AutoPlaySettings(),
    /** The playback queue (the TV's `episode_queue`), synced with it. */
    val episodeQueue: List<com.fetch.desktop.playback.QueueItem> = emptyList(),
    /** Layout's Continue Watching, Home, card, title page and custom poster options, as on TV (synced with it). */
    val layout: LayoutPrefs = LayoutPrefs(),
    /** Bumped when a saved default has to change; see [migrated]. */
    val schema: Int = 1
) {
    val appTheme: AppTheme get() = AppTheme.entries.firstOrNull { it.name == theme } ?: AppTheme.FETCH

    /**
     * Brings an older settings file up to date. Schema 2: desktop used to save "prefer my own
     * library" off because it had no media servers; now it has them, so it starts on, as on TV.
     */
    fun migrated(): DesktopSettings {
        var s = this
        if (s.schema < 2) s = s.copy(rankingRules = s.rankingRules.copy(preferOwnLibrary = true), schema = 2)
        return s
    }
}

object DesktopDefaults {
    /**
     * The TV defaults, except that the desktop can't tell what a stream will decode to (mpv
     * plays nearly anything), so it never drops a stream as undecodable.
     */
    val rankingRules = StreamRankingRules(dropUndecodable = false)
}

fun settingsFile(dir: File = AppDirs.config) =
    JsonFile(File(dir, "settings.json"), DesktopSettings.serializer()) { DesktopSettings(experience = ExperienceSettings.Fresh) }

/**
 * The TV app's TMDB settings (`tmdb_settings`): off by default; when on, each part of a title
 * page TMDB may fill in. [language] is an ISO 639-1 code or a tag such as `pt-BR`.
 */
@Serializable
data class TmdbSettings(
    val enabled: Boolean = false,
    /** Also enrich Home's hero and cards (the TV's modern Home). */
    val modernHomeEnabled: Boolean = false,
    /** Enrich Continue Watching cards: artwork, logo and title from TMDB. */
    val enrichContinueWatching: Boolean = true,
    val language: String = "en",
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useTrailers: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useCollections: Boolean = true
)

/** The TV's Ambient Mode settings (on by default, five minutes, artwork, the hero's catalogs). */
@Serializable
data class AmbientSettings(
    val enabled: Boolean = true,
    val timeoutMinutes: Int = 5,
    /** Up to three Home row keys to take artwork from; empty uses the first Home row. */
    val catalogKeys: List<String> = emptyList(),
    val face: com.fetch.tv.domain.model.AmbientFace = com.fetch.tv.domain.model.AmbientFace.ARTWORK
)

/** What Play does with the ranked sources (the TV's StreamAutoPlayMode). */
enum class AutoPlayMode(val label: String) {
    BEST_MATCH("Best match"),
    FIRST_STREAM("First source"),
    REGEX_MATCH("First match for a pattern"),
    /** Play opens the source list instead of starting anything. */
    MANUAL("Pick every time")
}

enum class SubtitleMode(val label: String) {
    /** mpv picks a track in the preferred language, or none. */
    AUTO("When available"),
    /** Only when the audio isn't in the subtitle language (foreign films, anime). */
    FOREIGN_AUDIO("Only for other languages"),
    OFF("Off")
}

/** How the picture fills the player (the TV's resize modes). */
enum class VideoFit(val label: String) {
    FIT("Fit"),
    CROP("Crop to fill"),
    STRETCH("Stretch"),
    ZOOM("Slight zoom")
}

/** mpv's --audio-channels: what the sound is mixed down to. */
enum class AudioChannels(val mpvValue: String, val label: String) {
    AUTO("auto-safe", "Automatic"),
    STEREO("stereo", "Stereo"),
    SURROUND_51("5.1", "5.1"),
    SURROUND_71("7.1", "7.1")
}

/** Whether the subtitle style also applies to ASS/SSA subtitles, which carry their own. */
enum class AssOverride(val mpvValue: String, val label: String) {
    SCALE("scale", "Keep their style (size only)"),
    FORCE("force", "Use my style"),
    NO("no", "Leave them as they are")
}

enum class HwDec(val mpvValue: String, val label: String) {
    AUTO_SAFE("auto-safe", "Auto (auto-safe)"),
    AUTO_COPY("auto-copy", "Hardware (copy)"),
    OFF("no", "Off (software)")
}

/**
 * The TV app's Audio, Subtitles, Player and Up Next settings that make sense for mpv. Languages
 * are ISO 639-1 codes; null means the file's default.
 */
@Serializable
data class PlayerSettings(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    /** "Forward subtitles to external player": addon subtitles in the preferred language. */
    val forwardAddonSubtitles: Boolean = true,
    /** mpv's --sub-scale. */
    val subtitleScale: Double = 1.0,
    /** A dark box behind subtitles instead of an outline. */
    val subtitleBackground: Boolean = false,
    val hwdec: HwDec = HwDec.AUTO_SAFE,
    /** Pass AC-3, E-AC-3, DTS, DTS-HD and TrueHD to the receiver undecoded. */
    val audioPassthrough: Boolean = false,
    val fullscreen: Boolean = true,
    /** "Default Player": the built-in player (libmpv in Fetch's window) instead of mpv's own window. */
    val builtInPlayer: Boolean = false,
    /** The built-in player draws with the graphics card (OpenGL); off, it draws in software. */
    val builtInGpu: Boolean = true,
    /** Up Next: when an episode plays to its end, start the next one's best match. */
    val autoplayNextEpisode: Boolean = true,
    /** Extra mpv options, space-separated, for anything not covered here. */
    val extraMpvArgs: String = "",
    /** Media server quality: null plays the file as it is; a cap transcodes on the server. */
    val serverMaxBitrateKbps: Int? = null,
    /** Skip Segments: what mpv jumps over automatically, from introdb.app's times. */
    val autoSkip: Set<SegmentType> = setOf(SegmentType.INTRO, SegmentType.RECAP),
    /** Anime skip sources, subtitle extras, audio extras, stats overlay and trailers (data/PlayerExtras.kt). */
    val extras: PlayerExtras = PlayerExtras(),

    // --- Added for TV parity ---
    /** "Default Player: Ask every time": choose the built-in player or mpv's window on Play. */
    val askPlayer: Boolean = false,
    /** A second audio language, used when the first isn't in the file. */
    val secondaryAudioLanguage: String? = null,
    val secondarySubtitleLanguage: String? = null,
    /** Pick a forced subtitle track (signs, foreign lines) when the audio is in your language. */
    val useForcedSubtitles: Boolean = true,
    /** Remove [MUSIC], (laughs) and speaker names from hearing-impaired subtitles. */
    val stripSdh: Boolean = true,
    val subtitleBold: Boolean = false,
    /** "#RRGGBB". */
    val subtitleColor: String = "#FFFFFF",
    val subtitleOutline: Boolean = true,
    val subtitleOutlineColor: String = "#000000",
    /** mpv's --sub-border-size; its default is 3. */
    val subtitleOutlineSize: Double = 3.0,
    /** mpv's --sub-pos: 100 is the bottom of the picture, lower moves subtitles up. */
    val subtitlePosition: Int = 100,
    val assOverride: AssOverride = AssOverride.SCALE,
    val audioChannels: AudioChannels = AudioChannels.AUTO,
    /** Keep the volume of a downmix level with the original. */
    val normalizeDownmix: Boolean = false,
    /** Amplify the sound by this many dB (0 to 12), for quiet files. */
    val volumeBoostDb: Int = 0,
    val videoFit: VideoFit = VideoFit.FIT,
    /** Forward cache, in MiB; null keeps mpv's default. */
    val cacheMb: Int? = null,
    /** Show the Skip button for intros and credits that aren't skipped automatically. */
    val skipButton: Boolean = true,
    /** "Up next" prompt: how many seconds before the end of an episode it appears. */
    val upNextPromptSeconds: Int = 30,
    /** "Are you still watching?" after this many episodes in a row ([stillWatchingEpisodes]). */
    val stillWatching: Boolean = false,
    val stillWatchingEpisodes: Int = 3,
    /** The title, episode and synopsis while paused, as on TV. */
    val pauseOverlay: Boolean = true,
    /** Skip Segments › IntroDB server: a server of your own instead of api.introdb.app. */
    val introDbUrl: String? = null
)
