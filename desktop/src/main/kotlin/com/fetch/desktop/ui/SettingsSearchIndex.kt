package com.fetch.desktop.ui

import com.fetch.desktop.SettingsPage
import com.fetch.desktop.SettingsPage.ABOUT
import com.fetch.desktop.SettingsPage.ACCOUNT
import com.fetch.desktop.SettingsPage.ADDONS
import com.fetch.desktop.SettingsPage.ADVANCED
import com.fetch.desktop.SettingsPage.APPEARANCE
import com.fetch.desktop.SettingsPage.AUDIO
import com.fetch.desktop.SettingsPage.COLLECTIONS
import com.fetch.desktop.SettingsPage.CONTENT_DISCOVERY
import com.fetch.desktop.SettingsPage.FILTER_ROWS
import com.fetch.desktop.SettingsPage.HOME_CATALOGS
import com.fetch.desktop.SettingsPage.LAYOUT
import com.fetch.desktop.SettingsPage.MDBLIST
import com.fetch.desktop.SettingsPage.MEDIA_SERVERS
import com.fetch.desktop.SettingsPage.PLAYBACK_BASICS
import com.fetch.desktop.SettingsPage.PLAYER
import com.fetch.desktop.SettingsPage.PROFILES
import com.fetch.desktop.SettingsPage.SKIP_SEGMENTS
import com.fetch.desktop.SettingsPage.STREAM_BADGES
import com.fetch.desktop.SettingsPage.STREAM_SELECTION
import com.fetch.desktop.SettingsPage.SUBTITLES
import com.fetch.desktop.SettingsPage.TMDB
import com.fetch.desktop.SettingsPage.TRACKING
import com.fetch.desktop.SettingsPage.UP_NEXT
import com.fetch.desktop.SettingsPage.WATCH_PARTY
import com.fetch.desktop.SettingsPage.YOUTUBE
import com.fetch.desktop.data.ExperiencePolicy
import com.fetch.tv.domain.model.ExperienceMode

/**
 * One setting search can find: the row's [title] as its page shows it (so the row can be
 * highlighted), the [page] it's on and words people might type instead. [essential] marks the
 * rows Essential mode keeps on the pages it shows only in part ([ExperiencePolicy.reducedPages]).
 */
data class SettingsSearchEntry(
    val title: String,
    val page: SettingsPage,
    val keywords: List<String> = emptyList(),
    val section: String? = null,
    val essential: Boolean = false
)

private fun e(title: String, page: SettingsPage, vararg keywords: String, section: String? = null, essential: Boolean = false) =
    SettingsSearchEntry(title, page, keywords.toList(), section, essential)

/**
 * Every desktop setting by page, as the TV's `settingsSearchIndex`. SettingsSearchIndexTest checks
 * that every page has entries and that each title is a row the app really shows, so a renamed row
 * can't linger here.
 */
val settingsSearchIndex: List<SettingsSearchEntry> = listOf(
    // People
    e("Profiles", PROFILES, "users", "people", "pin", "kids", "limits", "screen time", "bedtime"),
    e("Remember Last Profile", PROFILES, "start-up", "profile picker", "who's watching", section = "At Start-up"),

    // Playback › Player
    e("Default Player", PLAYER, "mpv", "built-in", "external", "ask every time", "libmpv"),
    e("Draw with the graphics card", PLAYER, "gpu", "opengl", "software", "hdr"),
    e("Start in fullscreen", PLAYER, "full screen", "window"),
    e("Hardware Decoding", PLAYER, "hwdec", "gpu", "vaapi", "decoder"),
    e("Picture", PLAYER, "aspect", "zoom", "crop", "stretch", "fit"),
    e("Pause overlay", PLAYER, "paused", "synopsis", section = "Player interface"),
    e("Cache size", PLAYER, "buffer", "memory", "read ahead", section = "Buffer"),
    e("Extra mpv options", PLAYER, "arguments", "flags", "command line", section = "Advanced"),

    // Playback › Stream Selection
    e("Play", STREAM_SELECTION, "autoplay", "best match", "first source", "pattern", "manual", "pick every time", section = "When you press Play"),
    e("Pattern", STREAM_SELECTION, "regex", "regular expression", section = "When you press Play"),
    e("Addon timeout", STREAM_SELECTION, "wait", "slow addons", "seconds", section = "When you press Play"),
    e("Stream badges", STREAM_SELECTION, "fusion", "labels", "images"),
    e("Preferred quality", STREAM_SELECTION, "resolution", "4k", "1080p", "2160p", section = "Quality"),
    e("Maximum quality", STREAM_SELECTION, "resolution", "limit", section = "Quality"),
    e("Preferred video codec", STREAM_SELECTION, "hevc", "av1", "h264", "x265", section = "Quality"),
    e("Preferred file size", STREAM_SELECTION, "gb", "size", section = "File size"),
    e("Maximum file size", STREAM_SELECTION, "gb", "size", section = "File size"),
    e("Minimum file size", STREAM_SELECTION, "gb", "size", section = "File size"),
    e("Preferred audio languages", STREAM_SELECTION, "dub", "language", section = "Languages"),
    e("Skip audio languages", STREAM_SELECTION, "exclude", "language", section = "Languages"),
    e("Skip release types", STREAM_SELECTION, "cam", "web", "bluray", "remux", section = "Skip"),
    e("Skip audio formats", STREAM_SELECTION, "atmos", "dts", "truehd", section = "Skip"),
    e("Never auto-play CAM or telesync", STREAM_SELECTION, "cam", "ts", section = "Skip"),
    e("Skip Dolby Vision profile 5", STREAM_SELECTION, "dv", "hdr", "purple", "green", section = "Skip"),
    e("Preferred addons", STREAM_SELECTION, "addon", "provider", section = "Addons"),
    e("Preferred release groups", STREAM_SELECTION, "group", "release", section = "Release groups"),
    e("Skip release groups", STREAM_SELECTION, "group", "release", "exclude", section = "Release groups"),
    e("Prefer my own library", STREAM_SELECTION, "jellyfin", "emby", "plex", "media server", section = "Results"),
    e("Prefer cached debrid results", STREAM_SELECTION, "real-debrid", "premiumize", "torbox", "cached", section = "Results"),
    e("Results to keep", STREAM_SELECTION, "limit", "max results", section = "Results"),
    e("Results per resolution", STREAM_SELECTION, "limit", section = "Results"),
    e("Size badges", STREAM_BADGES, "fusion", section = "Fusion Style"),
    e("Badge position", STREAM_BADGES, "fusion", section = "Fusion Style"),
    e("Addon logo", STREAM_BADGES, "fusion", section = "Fusion Style"),
    e("Fusion badge URLs", STREAM_BADGES, "import", "badges"),

    // Playback › Up Next, Audio, Subtitles, Skip Segments
    e("Autoplay next episode", UP_NEXT, "binge", "next episode", "auto play"),
    e("Up Next prompt", UP_NEXT, "countdown", "card", "seconds before the end"),
    e("Are you still watching?", UP_NEXT, "still watching", "idle", "sleep"),
    e("Ask after", UP_NEXT, "episodes", "still watching"),
    e("Preferred Audio Language", AUDIO, "dub", "language", "track"),
    e("Secondary Audio Language", AUDIO, "fallback", "language"),
    e("Audio passthrough", AUDIO, "receiver", "dolby", "dts", "truehd", "spdif", "hdmi"),
    e("Channels", AUDIO, "downmix", "stereo", "5.1", "7.1", "surround", section = "Mixing"),
    e("Normalize downmix", AUDIO, "volume", "dialogue", "loudness", section = "Mixing"),
    e("Volume boost", AUDIO, "louder", "db", "quiet", "amplify", section = "Mixing"),
    e("Subtitle language", SUBTITLES, "captions", "language", section = "Languages"),
    e("Secondary subtitle language", SUBTITLES, "captions", "fallback", section = "Languages"),
    e("Show subtitles", SUBTITLES, "captions", "off", "foreign", section = "Languages"),
    e("Forward subtitles to external player", SUBTITLES, "opensubtitles", "addon subtitles", section = "Languages"),
    e("Use forced subtitles", SUBTITLES, "forced", "signs", section = "Languages"),
    e("Remove hearing-impaired text", SUBTITLES, "sdh", "cc", "deaf", section = "Languages"),
    e("Size", SUBTITLES, "subtitle size", "font size", "bigger", "smaller", section = "Style"),
    e("Bold", SUBTITLES, "subtitle bold", "weight", section = "Style"),
    e("Text color", SUBTITLES, "subtitle color", "yellow", section = "Style"),
    e("Background box", SUBTITLES, "subtitle background", "box", section = "Style"),
    e("Outline", SUBTITLES, "subtitle outline", "border", "shadow", section = "Style"),
    e("Outline color", SUBTITLES, "subtitle outline", section = "Style"),
    e("Outline width", SUBTITLES, "subtitle outline", "border size", section = "Style"),
    e("Vertical position", SUBTITLES, "subtitle position", "offset", "move up", section = "Style"),
    e("Styled subtitles (ASS/SSA)", SUBTITLES, "ass", "ssa", "anime", "fansub", "override", section = "Style"),
    e("Skip intro", SKIP_SEGMENTS, "intro", "opening", "auto skip", "introdb", section = "Automatic Skipping"),
    e("Skip recap", SKIP_SEGMENTS, "recap", "previously", "auto skip", section = "Automatic Skipping"),
    e("Skip credits", SKIP_SEGMENTS, "outro", "credits", "ending", "auto skip", section = "Automatic Skipping"),
    e("Show Skip button", SKIP_SEGMENTS, "skip intro", "button"),
    e("IntroDB server", SKIP_SEGMENTS, "introdb", "self-hosted", section = "Advanced"),
    e("IntroDB API key", SKIP_SEGMENTS, "introdb", "mark", "submit", "markers", section = "Advanced"),

    // Playback basics (Essential)
    e("Default Player", PLAYBACK_BASICS, "mpv", "built-in", "ask every time", section = "Playback basics"),
    e("Stream selection", PLAYBACK_BASICS, "autoplay", "manual", "best match", "first source", section = "Playback basics"),
    e("Auto-play next episode", PLAYBACK_BASICS, "binge", "next episode", section = "Playback basics"),
    e("Subtitle language", PLAYBACK_BASICS, "captions", section = "Subtitles and audio"),
    e("Use forced subtitles", PLAYBACK_BASICS, "forced", "signs", section = "Subtitles and audio"),
    e("Subtitle size", PLAYBACK_BASICS, "captions", "bigger", "smaller", section = "Subtitles and audio"),
    e("Audio language", PLAYBACK_BASICS, "dub", "track", section = "Subtitles and audio"),

    // Sources
    e("Addons", CONTENT_DISCOVERY, "stremio", "install", "manifest", "plugins", essential = true),
    e("Media servers", CONTENT_DISCOVERY, "jellyfin", "emby", "plex", essential = true),
    e("Home catalogs", CONTENT_DISCOVERY, "rows", "pin", "hide", "order", essential = true),
    e("Collections", CONTENT_DISCOVERY, "folders", "rows", essential = true),
    e("Filter rows", CONTENT_DISCOVERY, "genre", "library rows", essential = true),
    e("Install", ADDONS, "addon", "manifest", "url", "link", "debrid"),
    e("Add Jellyfin", MEDIA_SERVERS, "jellyfin", "server", "sign in"),
    e("Add Emby", MEDIA_SERVERS, "emby", "server", "sign in"),
    e("Add Plex", MEDIA_SERVERS, "plex", "server", "link"),
    e("Media server quality", MEDIA_SERVERS, "transcode", "bitrate", "mbps", section = "Playback"),
    e("Home catalogs", HOME_CATALOGS, "pin to top", "disable", "rows"),
    e("Collections", COLLECTIONS, "import", "export", "json", "folders"),
    e("Filter rows", FILTER_ROWS, "genre", "years", "watched", "rating"),
    e("Status", TMDB, "tmdb key", "api"),
    e("Your TMDB key", TMDB, "api key", "token", "themoviedb", essential = true),
    e("Use TMDB", TMDB, "enrichment", "themoviedb", "metadata", "artwork", section = "Enrichment", essential = true),
    e("Language", TMDB, "tmdb language", "translation", section = "Enrichment"),
    e("Artwork", TMDB, "backdrops", "logos", section = "What TMDB fills in"),
    e("Title, description and genres", TMDB, "basic info", "rating", section = "What TMDB fills in"),
    e("Details", TMDB, "runtime", "status", "country", section = "What TMDB fills in"),
    e("Cast and crew", TMDB, "credits", "actors", "people", section = "What TMDB fills in"),
    e("Production companies", TMDB, "studios", section = "What TMDB fills in"),
    e("Networks", TMDB, "channels", section = "What TMDB fills in"),
    e("Episodes", TMDB, "episode names", "stills", section = "What TMDB fills in"),
    e("Trailers", TMDB, "videos", section = "What TMDB fills in"),
    e("More like this", TMDB, "recommendations", "because you watched", section = "What TMDB fills in"),
    e("Collections", TMDB, "trilogy", "series of films", section = "What TMDB fills in"),
    e("Enable MDBList ratings", MDBLIST, "ratings", "imdb", "rotten tomatoes", "metacritic", "letterboxd", section = "Ratings", essential = true),
    e("API key", MDBLIST, "mdblist key", section = "Ratings", essential = true),
    e("Use ratings on Home's hero", MDBLIST, "hero", "home", section = "Ratings"),
    e("Which ratings, in order", MDBLIST, "order", "providers", "rotten tomatoes", "imdb"),
    e("MDBList", MDBLIST, "account", "connect", "watchlist", "lists", section = "Account"),
    e("Connect with an API key", MDBLIST, "account", section = "Account"),
    e("Which lists to show", MDBLIST, "library lists", section = "Library lists"),

    // Home
    e("Color Theme", APPEARANCE, "accent", "colour", "theme", "red", "blue", "custom", essential = true),
    e("Custom", APPEARANCE, "custom theme", "own color", "hex", "gradient", essential = true),
    e("Increase contrast", APPEARANCE, "high contrast", "accessibility", "brighter text", essential = true),
    e("AMOLED Mode", APPEARANCE, "pure black", "oled", "dark"),
    e("Pure Black Surfaces", APPEARANCE, "amoled", "oled", "cards", "black"),
    e("App Font", APPEARANCE, "font", "typeface", "inter", "dm sans", "open sans"),
    e("Ambient Mode", APPEARANCE, "screensaver", "idle", "artwork", "clock", section = "Ambient Mode", essential = true),
    e("Start After", APPEARANCE, "ambient", "idle", "minutes", section = "Ambient Mode", essential = true),
    e("Show", APPEARANCE, "ambient", "artwork", "clock", section = "Ambient Mode", essential = true),
    e("Catalogs", APPEARANCE, "ambient", "rows", section = "Ambient Mode", essential = true),
    e("Show Continue Watching", LAYOUT, "resume", "continue watching", section = "Home", essential = true),
    e("Show Unaired Next Up Episodes", LAYOUT, "upcoming", "next up", section = "Home"),
    e("Up Next From Furthest Episode", LAYOUT, "rewatch", "next up", section = "Home"),
    e("Sort Order", LAYOUT, "continue watching", "upcoming row", "streaming style", section = "Home", essential = true),
    e("Show Catalog Type", LAYOUT, "movies", "series", "row titles", section = "Home"),
    e("Show Addon Name", LAYOUT, "row titles", section = "Home"),
    e("Hide unreleased content", LAYOUT, "upcoming", "not out", section = "Home", essential = true),
    e("Show Poster Labels", LAYOUT, "title", "year", "posters", section = "Posters"),
    e("Discover Location", LAYOUT, "discover", "sidebar", "side panel", "search", section = "Navigation", essential = true),

    // Accounts
    e("Account", ACCOUNT, "sync", "server", "sign in", "fetch server", "self-hosted"),
    e("Server address", ACCOUNT, "sync", "url", section = "Server"),
    e("Trakt", TRACKING, "scrobble", "watchlist", "connect", "history"),
    e("Simkl", TRACKING, "scrobble", "watchlist", "connect", "anime"),
    e("Sync now", TRACKING, "tracking", "refresh", section = "Sync"),
    e("Sign in to YouTube", YOUTUBE, "youtube", "google", "account", section = "Account"),
    e("Skip sponsors", YOUTUBE, "sponsorblock", section = "Playback"),
    e("Video quality", YOUTUBE, "youtube quality", "resolution", section = "Playback"),
    e("Autoplay next video", YOUTUBE, "youtube", "up next", section = "Playback"),
    e("Add to YouTube history", YOUTUBE, "history", "watch time", section = "Playback"),
    e("Show dislike counts", YOUTUBE, "return youtube dislike", section = "Playback"),
    e("Show Shorts", YOUTUBE, "shorts", section = "Feeds"),
    e("Show live streams", YOUTUBE, "live", section = "Feeds"),
    e("Hide watched videos", YOUTUBE, "watched", section = "Feeds"),
    e("YouTube row on Home", YOUTUBE, "subscriptions row", "home", section = "Feeds"),
    e("Community titles and thumbnails", YOUTUBE, "dearrow", "clickbait", section = "Feeds"),
    e("Watch party", WATCH_PARTY, "party", "watch together", "friends", "party code"),

    // System
    e("Experience mode", ADVANCED, "essential", "advanced", "simple", "all settings", "mode"),
    e("First-run setup", ADVANCED, "wizard", "setup", "onboarding", "welcome"),
    e("Check for updates", ABOUT, "update", "new version", "github", section = "Updates", essential = true),
    e("Include beta releases", ABOUT, "beta", "pre-release", section = "Updates"),
    e("Check now", ABOUT, "update", section = "Updates", essential = true),
    e("Settings folder", ABOUT, "config", "files", "~/.config"),
    e("fetch:// links", ABOUT, "deep links", "xdg", "url handler", section = "Links"),
    e("stremio:// links", ABOUT, "deep links", "addon install", "xdg", section = "Links"),
    e("Privacy Policy", ABOUT, "privacy", "telemetry", section = "Legal", essential = true),
    e("Licenses & Attribution", ABOUT, "licenses", "open source", section = "Legal", essential = true),
    e("Source code", ABOUT, "github", "repository", section = "Legal")
)

/** Settings search, as on TV: every word must appear in the title, its page, section or keywords. */
object SettingsSearch {
    /**
     * The entries [query] finds among those [visible], best first: titles that start with the
     * query, then titles that contain it, then keyword and page matches.
     */
    fun search(
        query: String,
        mode: ExperienceMode?,
        hidden: Set<SettingsPage> = emptySet(),
        index: List<SettingsSearchEntry> = settingsSearchIndex
    ): List<SettingsSearchEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        return visibleEntries(mode, hidden, index)
            .mapNotNull { entry ->
                val title = entry.title.lowercase()
                val haystack = (listOf(title, entry.page.label.lowercase(), entry.page.railPage.label.lowercase(), entry.section.orEmpty().lowercase()) +
                    entry.keywords.map { it.lowercase() }).joinToString(" | ")
                if (!words.all { it in haystack }) return@mapNotNull null
                val rank = when {
                    title.startsWith(q) -> 0
                    title.contains(q) -> 1
                    words.all { it in title } -> 2
                    entry.keywords.any { it.lowercase().contains(q) } -> 3
                    else -> 4
                }
                rank to entry
            }
            .sortedWith(compareBy<Pair<Int, SettingsSearchEntry>> { it.first }.thenBy { it.second.page.ordinal })
            .map { it.second }
    }

    /**
     * What search looks through in [mode]: entries on pages the rail shows (not [hidden] ones, such
     * as Watch party without a server). In Essential, pages it shows only in part keep their
     * [SettingsSearchEntry.essential] rows, as the TV's `essentialSettingsSearchIndex`.
     */
    fun visibleEntries(
        mode: ExperienceMode?,
        hidden: Set<SettingsPage> = emptySet(),
        index: List<SettingsSearchEntry> = settingsSearchIndex
    ): List<SettingsSearchEntry> = index.filter { entry ->
        ExperiencePolicy.isVisible(entry.page, mode) && entry.page !in hidden && entry.page.railPage !in hidden &&
            (mode != ExperienceMode.ESSENTIAL || entry.page.railPage !in ExperiencePolicy.reducedPages || entry.essential)
    }
}
