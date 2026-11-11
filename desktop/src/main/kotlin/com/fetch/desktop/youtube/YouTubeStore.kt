package com.fetch.desktop.youtube

import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.PrivateFiles
import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.SponsorBlockMode
import com.fetch.tv.data.youtube.YouTubeAccount
import com.fetch.tv.data.youtube.YouTubeFeedFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Settings › YouTube, per profile, as the TV app's `youtube_settings` store keeps them. Kept apart
 * from the sign-in, so signing out keeps them. [sponsorBlockModes] holds only the categories
 * changed from their default, by SponsorBlock's name.
 */
@Serializable
data class YouTubeSettings(
    /** Skip the parts SponsorBlock users marked (each category's mode says how). */
    val sponsorBlockEnabled: Boolean = true,
    val sponsorBlockModes: Map<String, SponsorBlockMode> = emptyMap(),
    /** Add the videos watched in Fetch to the account's YouTube history. */
    val watchHistoryEnabled: Boolean = true,
    /** When a video ends, play YouTube's Up Next pick. */
    val autoplayEnabled: Boolean = true,
    val showShorts: Boolean = true,
    val showLive: Boolean = true,
    val hideWatched: Boolean = false,
    /** The tallest video to play: 720, 1080, 1440 or 2160. */
    val maxVideoHeight: Int = DEFAULT_MAX_VIDEO_HEIGHT,
    /** DeArrow's community titles and thumbnails in place of YouTube's. */
    val deArrowEnabled: Boolean = false,
    /** Like and dislike counts from Return YouTube Dislike while a video plays. */
    val returnDislikeEnabled: Boolean = false,
    /** A row of the newest subscription videos on Home. */
    val homeRowEnabled: Boolean = false,
    /**
     * The browser whose YouTube sign-in yt-dlp borrows for playback (its `--cookies-from-browser`),
     * so members-only, age-restricted and private videos play; null plays signed out. Fetch's own
     * sign-in can't be handed to yt-dlp, which no longer takes OAuth tokens (see docs/desktop.md).
     */
    val playbackCookiesBrowser: String? = null
) {
    val feedFilter: YouTubeFeedFilter get() = YouTubeFeedFilter(showShorts = showShorts, showLive = showLive, hideWatched = hideWatched)

    val videoHeight: Int get() = maxVideoHeight.takeIf { it in VIDEO_HEIGHT_OPTIONS } ?: DEFAULT_MAX_VIDEO_HEIGHT

    fun mode(category: SponsorBlockCategory): SponsorBlockMode = sponsorBlockModes[category.apiName] ?: category.defaultMode

    fun withMode(category: SponsorBlockCategory, mode: SponsorBlockMode): YouTubeSettings = copy(
        sponsorBlockModes = if (mode == category.defaultMode) sponsorBlockModes - category.apiName
        else sponsorBlockModes + (category.apiName to mode)
    )

    /** Every category's mode while SponsorBlock is on; all off when it isn't. */
    val activeModes: Map<SponsorBlockCategory, SponsorBlockMode>
        get() = SponsorBlockCategory.entries.associateWith { if (sponsorBlockEnabled) mode(it) else SponsorBlockMode.OFF }

    companion object {
        /** TV screens are 1080p or 4K; 1080p keeps 4K streams off machines and networks that struggle. */
        const val DEFAULT_MAX_VIDEO_HEIGHT = 1080
        val VIDEO_HEIGHT_OPTIONS = listOf(720, 1080, 1440, 2160)

        /** The browsers yt-dlp can read a sign-in from, by its names for them, with the labels shown. */
        val COOKIE_BROWSERS = listOf(
            "firefox" to "Firefox",
            "chrome" to "Chrome",
            "chromium" to "Chromium",
            "brave" to "Brave",
            "edge" to "Edge",
            "opera" to "Opera",
            "vivaldi" to "Vivaldi"
        )
    }
}

/** A profile's YouTube sign-in. The refresh token signs in to the Google account. */
@Serializable
data class YouTubeAuth(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessTokenExpiresAt: Long? = null,
    val accountName: String? = null,
    val accountHandle: String? = null,
    val accountPhotoUrl: String? = null,
    /** The TV client the sign-in was made with. A refresh token only works with that client. */
    val clientId: String? = null,
    val clientSecret: String? = null,
    /** The brand account requests act as; null for the Google account's own channel. */
    val pageId: String? = null
) {
    val isSignedIn: Boolean get() = !refreshToken.isNullOrBlank()

    fun withAccount(account: YouTubeAccount) =
        copy(accountName = account.name, accountHandle = account.handle, accountPhotoUrl = account.photoUrl, pageId = account.pageId)
}

/** Where [YouTubeClient] keeps the sign-in. */
interface YouTubeAuthStore {
    fun load(): YouTubeAuth
    fun save(auth: YouTubeAuth)
}

/** Keeps the sign-in in memory only (tests, or before a profile has a folder). */
class InMemoryYouTubeAuthStore(private var auth: YouTubeAuth = YouTubeAuth()) : YouTubeAuthStore {
    override fun load(): YouTubeAuth = auth
    @Synchronized override fun save(auth: YouTubeAuth) { this.auth = auth }
}

/**
 * Keeps the sign-in in the profile's `youtube-auth.json`, written atomically and readable by its
 * owner only, like `tracking.json`. The TV keeps the tokens in the Android Keystore; the desktop
 * has no keystore, so file permissions guard them.
 */
class YouTubeAuthFile(private val file: File) : YouTubeAuthStore {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    @Volatile private var cached: YouTubeAuth? = null

    override fun load(): YouTubeAuth {
        cached?.let { return it }
        val loaded = if (!file.exists()) YouTubeAuth() else try {
            json.decodeFromString(YouTubeAuth.serializer(), file.readText())
        } catch (_: Exception) {
            YouTubeAuth()
        }
        cached = loaded
        return loaded
    }

    @Synchronized
    override fun save(auth: YouTubeAuth) {
        cached = auth
        runCatching {
            // Owner-only (600), written then renamed so a crash never leaves half a file.
            PrivateFiles.writeText(file, json.encodeToString(YouTubeAuth.serializer(), auth))
        }
    }
}

/** Where a YouTube video stopped in Fetch, to the millisecond. */
@Serializable
data class YouTubeResumePoint(
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long
)

/** A profile's YouTube searches and resume points, in `youtube.json`. */
@Serializable
data class YouTubeLocalData(
    val recentSearches: List<String> = emptyList(),
    val resume: List<YouTubeResumePoint> = emptyList(),
    /** Captions on or off, and their language, carried from one video to the next (the TV's). */
    val captions: YouTubeCaptionPreference = YouTubeCaptionPreference()
)

fun youTubeDataFile(dir: File) = JsonFile(File(dir, "youtube.json"), YouTubeLocalData.serializer()) { YouTubeLocalData() }

fun youTubeAuthFile(dir: File) = YouTubeAuthFile(File(dir, "youtube-auth.json"))

/** The TV's numbers: the most resume points kept, and the most recent searches. */
const val YOUTUBE_MAX_RESUME_POINTS = 300
const val YOUTUBE_MAX_RECENT_SEARCHES = 10

/** [query] first, with any earlier copy of it (ignoring case) dropped, keeping [max]. */
fun addRecentSearch(current: List<String>, query: String, max: Int = YOUTUBE_MAX_RECENT_SEARCHES): List<String> {
    val trimmed = query.trim().replace('\n', ' ').takeIf { it.isNotEmpty() } ?: return current
    return (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) }).take(max)
}

/**
 * [entries] with [videoId]'s point moved to the front, or dropped when the video was barely started
 * or nearly finished: those start over, as on YouTube (the TV's YouTubeResumeStore).
 */
fun updateResumePoints(
    entries: List<YouTubeResumePoint>,
    videoId: String,
    positionMs: Long,
    durationMs: Long,
    nowMs: Long,
    max: Int = YOUTUBE_MAX_RESUME_POINTS
): List<YouTubeResumePoint> {
    val others = entries.filterNot { it.videoId == videoId }
    val point = YouTubeResumePoint(videoId, positionMs, durationMs, nowMs)
    return if (resumableMs(positionMs, durationMs) != null) (listOf(point) + others).take(max) else others
}

/** [positionMs] when it's worth resuming from: past the first few seconds and short of the end. */
fun resumableMs(positionMs: Long, durationMs: Long): Long? {
    if (durationMs <= 0L || positionMs <= 0L) return null
    if (positionMs < MIN_RESUME_MS || positionMs < durationMs * 3 / 100) return null
    if (positionMs > durationMs * 95 / 100) return null
    return positionMs
}

private const val MIN_RESUME_MS = 10_000L

/**
 * Where mpv starts [video]: the exact place Fetch saved, else how far YouTube's own progress bar
 * says (as a percentage, since the length isn't known before mpv opens it). Nothing when barely
 * started or nearly finished, as on TV.
 */
fun youTubeResumeStart(saved: YouTubeResumePoint?, watchedPercent: Int?): String? {
    saved?.let { point -> resumableMs(point.positionMs, point.durationMs)?.let { return "%.1f".format(java.util.Locale.ROOT, it / 1000.0) } }
    val percent = watchedPercent ?: return null
    if (percent < 3 || percent > 95) return null
    return "$percent%"
}
