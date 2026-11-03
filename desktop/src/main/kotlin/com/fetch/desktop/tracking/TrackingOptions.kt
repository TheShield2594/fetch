package com.fetch.desktop.tracking

import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.ProgressFile
import kotlinx.serialization.Serializable

/** Where the Library opens (the TV's `LibrarySourceMode`, less the Fetch website). */
enum class LibrarySourceOption(val label: String) { LOCAL("Fetch (local)"), TRAKT("Trakt"), SIMKL("Simkl"), MDBLIST("MDBList") }

/** Whose resume points fill Continue Watching (the TV's `WatchProgressSource`). */
enum class WatchProgressSourceOption(val label: String) {
    TRAKT("Trakt"), SIMKL("Simkl"), MDBLIST("MDBList"), FETCH_SYNC("Fetch sync server")
}

/** Where "More like this" comes from (the TV's `MoreLikeThisSourcePreference`). */
enum class MoreLikeThisSourceOption(val label: String) { TRAKT("Trakt"), TMDB("TMDB"), SIMKL("Simkl") }

/** Which id a Simkl anime title opens with (the TV's `SimklAnimeIdPreference`). */
enum class SimklAnimeIdOption(val label: String, val description: String) {
    IMDB("IMDb", "Groups seasons under one id, for Cinemeta and most addons."),
    MAL("MyAnimeList", "Each MyAnimeList entry on its own, for anime addons."),
    KITSU("Kitsu", "Each Kitsu entry on its own, for the Kitsu addon."),
    TVDB("TVDB", "Stable across seasons, without IMDb's per-season splits.")
}

/**
 * The TV's Settings › Tracking options (its `trakt_settings`, synced with it): where the Library
 * and Continue Watching come from, how far back Continue Watching goes, where More like this
 * comes from, whether title pages show comments, and the id Simkl anime opens with. Stored by
 * the TV's enum names, so a value this build doesn't know falls back to the default.
 */
@Serializable
data class TrackingOptions(
    val librarySource: String = "TRAKT",
    val watchProgressSource: String = "TRAKT",
    /** Days of Continue Watching to show; 0 shows everything (TV default 60). */
    val continueWatchingDays: Int = DEFAULT_CW_DAYS,
    val moreLikeThisSource: String = "TRAKT",
    val showComments: Boolean = true,
    val simklAnimeId: String = "IMDB"
) {
    val library: LibrarySourceOption get() = LibrarySourceOption.entries.firstOrNull { it.name == librarySource } ?: LibrarySourceOption.TRAKT
    val progress: WatchProgressSourceOption get() = WatchProgressSourceOption.entries.firstOrNull { it.name == watchProgressSource } ?: WatchProgressSourceOption.TRAKT
    val moreLikeThis: MoreLikeThisSourceOption get() = MoreLikeThisSourceOption.entries.firstOrNull { it.name == moreLikeThisSource } ?: MoreLikeThisSourceOption.TRAKT
    val animeId: SimklAnimeIdOption get() = SimklAnimeIdOption.entries.firstOrNull { it.name == simklAnimeId } ?: SimklAnimeIdOption.IMDB

    companion object {
        const val DEFAULT_CW_DAYS = 60
        /** The TV's choices for the window; 0 is "All". */
        val CW_DAY_CHOICES = listOf(14, 30, 60, 90, 180, 365, 0)

        /** The TV's bounds: 0 (all), else 7 to 365 days. */
        fun normalizeDays(days: Int): Int = if (days == 0) 0 else days.coerceIn(7, 365)
    }
}

/**
 * Continue Watching's entries within the last [days] days (0: all), as the TV's
 * `continue_watching_days_cap` keeps it. Next Up entries for shows count from their last watch.
 */
fun withinWindow(file: ProgressFile, days: Int, nowMs: Long): ProgressFile {
    if (days <= 0) return file
    val cutoff = nowMs - days * 86_400_000L
    val kept = file.entries.filter { it.lastWatched >= cutoff }
    return if (kept.size == file.entries.size) file else file.copy(entries = kept)
}

/**
 * The resume points from Trakt, Simkl or MDBList that Continue Watching takes, by the chosen
 * [source]: only that service's when it's connected, none of theirs for the sync server. When
 * the chosen service isn't connected, everything connected counts, as before there was a choice.
 */
fun playbackForSource(playback: List<ProgressEntry>, source: WatchProgressSourceOption, connected: Set<WatchProgressSourceOption>): List<ProgressEntry> {
    if (source !in connected) return playback
    if (source == WatchProgressSourceOption.FETCH_SYNC) return emptyList()
    val key = source.name.lowercase()
    return playback.filter { it.source == key }
}

/**
 * The id a Simkl title opens with, as the TV picks it: for anime, the preferred anime id first
 * (falling back between MyAnimeList, Kitsu and AniDB), then IMDb, TMDB, TVDB and the rest.
 * [ids] are Simkl's `ids` object as strings.
 */
fun simklContentId(ids: Map<String, String>, isAnime: Boolean, preference: SimklAnimeIdOption): String? {
    fun id(key: String) = ids[key]?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    val hasAnimeIds = id("mal") != null || id("kitsu") != null || id("anidb") != null
    if (isAnime && hasAnimeIds) {
        when (preference) {
            SimklAnimeIdOption.MAL -> (id("mal")?.let { "mal:$it" } ?: id("kitsu")?.let { "kitsu:$it" } ?: id("anidb")?.let { "anidb:$it" })?.let { return it }
            SimklAnimeIdOption.KITSU -> (id("kitsu")?.let { "kitsu:$it" } ?: id("mal")?.let { "mal:$it" } ?: id("anidb")?.let { "anidb:$it" })?.let { return it }
            SimklAnimeIdOption.TVDB -> id("tvdb")?.let { return "tvdb:$it" }
            SimklAnimeIdOption.IMDB -> Unit
        }
    }
    return id("imdb")?.takeIf { it.startsWith("tt") }
        ?: id("tmdb")?.let { "tmdb:$it" }
        ?: id("tvdb")?.let { "tvdb:$it" }
        ?: id("kitsu")?.let { "kitsu:$it" }
        ?: id("mal")?.let { "mal:$it" }
        ?: id("anidb")?.let { "anidb:$it" }
        ?: id("anilist")?.let { "anilist:$it" }
        ?: (id("simkl") ?: id("simkl_id"))?.let { "simkl:$it" }
}
