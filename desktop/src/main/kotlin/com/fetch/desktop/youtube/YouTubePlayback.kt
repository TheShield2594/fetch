package com.fetch.desktop.youtube

import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleMode
import com.fetch.desktop.player.MpvArgs
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SegmentType
import com.fetch.tv.core.streams.ranking.StreamLanguage
import java.util.Locale

/**
 * How the desktop plays YouTube: mpv opens the watch page and resolves it through yt-dlp (mpv's
 * `ytdl` hook), as trailers already do. The TV app extracts the streams itself; on desktop yt-dlp
 * does that job, so playback needs no sign-in and plays without ads. Signed in, it borrows a
 * browser's YouTube sign-in ([cookiesBrowser]); Fetch's own token can't be used (docs/desktop.md).
 */
object YouTubePlayback {

    /** yt-dlp's format for the quality cap: the best video up to [maxHeight] with the best audio. */
    fun ytdlFormat(maxHeight: Int): String =
        "bestvideo[height<=?$maxHeight]+bestaudio/best[height<=?$maxHeight]/best"

    /**
     * mpv options for a YouTube video: the player settings' own ([MpvArgs.build]), then yt-dlp
     * with the quality cap, and captions in the subtitle languages loaded as tracks but left off,
     * as on TV (YouTube's auto-captions would otherwise show on nearly every video).
     */
    fun mpvOptions(
        player: PlayerSettings,
        settings: YouTubeSettings,
        locale: Locale = Locale.getDefault(),
        /** Captions on for the last video: they start on again, in its language first (the TV's caption preference). */
        captions: YouTubeCaptionPreference = YouTubeCaptionPreference()
    ): List<String> = buildList {
        addAll(MpvArgs.build(player))
        add("--ytdl=yes")
        add("--ytdl-format=${ytdlFormat(settings.videoHeight)}")
        val raw = buildList {
            if (player.subtitleMode != SubtitleMode.OFF) {
                add("write-subs=")
                add("write-auto-subs=")
                add("sub-langs=${captionLanguages(player, locale, captions.languageCode)}")
            }
            cookiesBrowser(settings)?.let { add("cookies-from-browser=$it") }
        }
        if (raw.isNotEmpty()) add("--ytdl-raw-options=${raw.joinToString(",")}")
        if (player.subtitleMode != SubtitleMode.OFF) {
            val language = captions.languageCode?.takeIf { captions.enabled }
            if (language != null) add("--slang=${(listOf(language) + listOfNotNull(player.subtitleLanguage, player.secondarySubtitleLanguage)).distinct().joinToString(",")}")
            else add("--sid=no")
        }
    }

    /** yt-dlp's name for the browser to borrow a sign-in from; null for one it doesn't know. */
    fun cookiesBrowser(settings: YouTubeSettings): String? =
        settings.playbackCookiesBrowser?.lowercase()?.takeIf { name -> YouTubeSettings.COOKIE_BROWSERS.any { it.first == name } }

    /**
     * yt-dlp's `--sub-langs` for the subtitle languages, as one pattern ("^(en|de)(-.*)?$"), so the
     * option holds no comma: mpv splits its `ytdl-raw-options` list on commas.
     */
    fun captionLanguages(player: PlayerSettings, locale: Locale = Locale.getDefault(), extra: String? = null): String {
        val codes = listOfNotNull(extra, player.subtitleLanguage, player.secondarySubtitleLanguage)
            .ifEmpty { listOf(locale.language.ifBlank { "en" }) }
            .map { StreamLanguage.fromCode(it)?.code ?: it }
            .map { it.lowercase().filter { c -> c.isLetter() || c == '-' } }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf("en") }
        return "^(${codes.joinToString("|")})(-.*)?$"
    }

    /**
     * SponsorBlock's segments as the skip system's: a category set to Skip jumps by itself, Ask
     * offers the built-in player's Skip button. Show (a seek bar mark on TV) and Off play through.
     */
    fun skipSegments(segments: List<YouTubeSkipSegment>, modes: Map<SponsorBlockCategory, SponsorBlockMode>): List<Segment> =
        segments.mapNotNull { segment ->
            val category = segment.sponsorBlockCategory ?: return@mapNotNull null
            val auto = when (modes[category] ?: category.defaultMode) {
                SponsorBlockMode.SKIP -> true
                SponsorBlockMode.ASK -> false
                SponsorBlockMode.SHOW, SponsorBlockMode.OFF -> return@mapNotNull null
            }
            Segment(SegmentType.INTRO, segment.startMs / 1000.0, segment.endMs / 1000.0, label = category.label, autoSkip = auto)
        }

    /**
     * The segments the built-in player's seek bar marks, as the TV's does: every one of a known
     * category that isn't Off (Skip and Ask as well as Show), in time order.
     */
    fun seekBarMarks(segments: List<YouTubeSkipSegment>, modes: Map<SponsorBlockCategory, SponsorBlockMode>): List<YouTubeSkipSegment> =
        segments.filter { segment ->
            val category = segment.sponsorBlockCategory ?: return@filter false
            (modes[category] ?: category.defaultMode) != SponsorBlockMode.OFF && segment.endMs > segment.startMs
        }.sortedBy { it.startMs }

    /** SponsorBlock's names of the categories to ask for: every one not Off (Show is fetched too, as on TV). */
    fun categoriesToFetch(modes: Map<SponsorBlockCategory, SponsorBlockMode>): List<String> =
        modes.filterValues { it != SponsorBlockMode.OFF }.keys.map { it.apiName }

    /** "1.2K likes · 34 dislikes", the TV's vote line. */
    fun votesLine(votes: YouTubeVoteCounts): String = "${compact(votes.likes)} likes · ${compact(votes.dislikes)} dislikes"

    internal fun compact(n: Long): String = when {
        n >= 1_000_000_000 -> trim(n / 1_000_000_000.0) + "B"
        n >= 1_000_000 -> trim(n / 1_000_000.0) + "M"
        n >= 1_000 -> trim(n / 1_000.0) + "K"
        else -> n.toString()
    }

    private fun trim(v: Double): String =
        if (v >= 100) v.toLong().toString() else "%.1f".format(Locale.ROOT, v).removeSuffix(".0")
}

/**
 * Whether Home may show the YouTube row at all (the TV's youTubeHomeRowAllowed). Restricted
 * profiles don't get it, as they don't get the YouTube page it opens.
 */
fun youTubeHomeRowAllowed(rowEnabled: Boolean, signedIn: Boolean, restrictedProfile: Boolean): Boolean =
    rowEnabled && signedIn && !restrictedProfile

/** Most videos the Home row shows, and how long its subscriptions feed is reused (the TV's). */
const val YOUTUBE_HOME_ROW_MAX_VIDEOS = 20
const val YOUTUBE_HOME_ROW_CACHE_TTL_MS = 10L * 60L * 1000L

/** The videos the Home row shows from [page]: what [filter] leaves, each once, at most [max]. */
fun youTubeHomeRowVideos(page: YouTubePage, filter: YouTubeFeedFilter, max: Int = YOUTUBE_HOME_ROW_MAX_VIDEOS): List<YouTubeVideo> =
    filter.apply(page, YouTubeFeed.SUBSCRIPTIONS).shelves
        .asSequence()
        .flatMap { it.videos }
        .distinctBy { it.videoId }
        .take(max.coerceAtLeast(0))
        .toList()

/**
 * Adds the next page to a feed, as the TV does. Titled rows are new rows; untitled videos continue
 * the feed's untitled row (subscriptions and history are one long untitled row), or the only row.
 */
fun appendPage(shelves: List<YouTubeShelf>, page: YouTubePage): List<YouTubeShelf> {
    val result = shelves.toMutableList()
    page.shelves.forEach { shelf ->
        val target = if (shelf.title == null) {
            result.indexOfFirst { it.title == null && it.isShorts == shelf.isShorts }.takeIf { it >= 0 }
                ?: result.singleOrNull()?.let { 0 }
        } else {
            null
        }
        if (target != null) {
            val existing = result[target]
            result[target] = existing.copy(
                videos = (existing.videos + shelf.videos).distinctBy { it.videoId },
                links = (existing.links + shelf.links).distinctBy { it.target }
            )
        } else {
            result += shelf
        }
    }
    return result
}
