package com.fetch.desktop.data

import com.fetch.tv.core.parental.CertificationLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- Avatars ---

/** One avatar a profile can pick: bundled with Fetch, or from the sync server's catalog. */
data class AvatarItem(
    val id: String,
    val name: String,
    val category: String,
    /** A URL for the server's; null for bundled ones, read from [BundledAvatars.resource]. */
    val imageUrl: String?,
    val bgColor: String?
)

/**
 * The TV's bundled avatars (`BundledAvatars.kt`, the artwork in `app/src/main/assets/avatars`),
 * shipped with the desktop app too so a profile has a picture without a sync server. Their ids
 * carry the TV's `fetch:` prefix, so the same id shows the same picture on every device.
 */
object BundledAvatars {
    const val ID_PREFIX = "fetch:"

    private val entries = listOf(
        Triple("fox", "animals", "#F4511E"), Triple("cat", "animals", "#8E24AA"), Triple("dog", "animals", "#8D6E63"),
        Triple("panda", "animals", "#43A047"), Triple("koala", "animals", "#78909C"), Triple("lion", "animals", "#00897B"),
        Triple("tiger", "animals", "#00838F"), Triple("frog", "animals", "#00897B"), Triple("owl", "animals", "#5C6BC0"),
        Triple("penguin", "animals", "#1E88E5"), Triple("unicorn", "animals", "#D81B60"), Triple("dragon", "animals", "#C62828"),
        Triple("octopus", "animals", "#8E24AA"), Triple("monkey", "animals", "#26A69A"), Triple("rabbit", "animals", "#EC407A"),
        Triple("bear", "animals", "#FFB300"), Triple("hamster", "animals", "#5C6BC0"), Triple("sloth", "animals", "#7CB342"),
        Triple("dinosaur", "animals", "#F9A825"), Triple("butterfly", "animals", "#00ACC1"),
        Triple("robot", "characters", "#546E7A"), Triple("alien", "characters", "#7B1FA2"), Triple("ghost", "characters", "#5E35B1"),
        Triple("space_invader", "characters", "#00ACC1"), Triple("cool", "characters", "#1E88E5"), Triple("pumpkin", "characters", "#4527A0"),
        Triple("rocket", "fun", "#F4511E"), Triple("star", "fun", "#3949AB"), Triple("pizza", "fun", "#00897B"),
        Triple("popcorn", "fun", "#1E88E5"), Triple("controller", "fun", "#455A64"), Triple("guitar", "fun", "#6A1B9A"),
        Triple("soccer", "fun", "#43A047"), Triple("basketball", "fun", "#3949AB"), Triple("rainbow", "fun", "#26A69A"),
        Triple("cactus", "fun", "#689F38"), Triple("sun", "fun", "#1565C0"), Triple("crystal_ball", "fun", "#263238"),
        Triple("clapper", "fun", "#37474F"), Triple("donut", "fun", "#D81B60")
    )

    val catalog: List<AvatarItem> = entries.map { (key, category, color) ->
        AvatarItem(ID_PREFIX + key, key.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }, category, null, color)
    }

    private val keys = entries.map { it.first }.toSet()

    /** The classpath resource of a bundled [avatarId], or null when it isn't one. */
    fun resource(avatarId: String): String? {
        if (!avatarId.startsWith(ID_PREFIX)) return null
        val key = avatarId.removePrefix(ID_PREFIX)
        return if (key in keys) "/avatars/$key.webp" else null
    }

    fun isBundled(avatarId: String?): Boolean = avatarId != null && resource(avatarId) != null
}

/** The TV's avatar category names, in its order. */
val AvatarCategoryLabels = linkedMapOf(
    "supporter" to "Supporter", "anime" to "Anime", "animation" to "Animation", "movie" to "Movie", "tv" to "TV",
    "gaming" to "Gaming", "animals" to "Animals", "characters" to "Characters", "fun" to "Fun"
)

/** A row of the sync server's `get_avatar_catalog`, as the TV reads it. */
@Serializable
internal data class RemoteAvatar(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("storage_path") val storagePath: String,
    val category: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("bg_color") val bgColor: String? = null
)

private val avatarJson = Json { ignoreUnknownKeys = true }

/**
 * The server's avatar catalog from `get_avatar_catalog`'s body, with each image under the
 * server's public `avatars` bucket (the TV's `avatarPublicBaseUrl`) unless it's a full URL.
 */
fun parseAvatarCatalog(body: String, serverBaseUrl: String): List<AvatarItem> {
    val rows = runCatching { avatarJson.decodeFromString<List<RemoteAvatar>>(body) }.getOrDefault(emptyList())
    val base = "${serverBaseUrl.trimEnd('/')}/storage/v1/object/public/avatars"
    return rows.sortedBy { it.sortOrder }.map { row ->
        val url = if (row.storagePath.startsWith("http://") || row.storagePath.startsWith("https://")) row.storagePath
        else "$base/${row.storagePath.trimStart('/')}"
        AvatarItem(row.id, row.displayName, row.category.lowercase(), url, row.bgColor)
    }
}

// --- New profiles: kids limits, copied settings ---

/** The TV's kids preset (`ProfileRestrictions.KidsPreset`): PG or lower, unrated hidden. */
val KidsLimits = ProfileLimits(maxRating = CertificationLevel.PARENTAL_GUIDANCE.name, allowUnrated = false)

/**
 * [target]'s settings after copying [source]'s, once, as the TV's "Copy settings" does: what
 * the profile sees and how it plays, but not its Home layout (pinned and hidden rows), what it
 * searched or browsed, or its own API keys. With [copyMissingKeys], a key [target] has none of
 * is taken from [source] ("API Keys: Copy Missing Keys"); existing keys are always kept.
 */
fun copiedSettings(source: DesktopSettings, target: DesktopSettings, copyMissingKeys: Boolean): DesktopSettings {
    val tmdbKey = target.tmdbApiKey?.takeIf { it.isNotBlank() } ?: source.tmdbApiKey.takeIf { copyMissingKeys }
    val mdbKey = target.mdblist.apiKey.ifBlank { if (copyMissingKeys) source.mdblist.apiKey else "" }
    return source.copy(
        pinnedCatalogs = target.pinnedCatalogs,
        disabledCatalogs = target.disabledCatalogs,
        discoverCatalog = target.discoverCatalog,
        recentSearches = target.recentSearches,
        tmdbApiKey = tmdbKey,
        mdblist = source.mdblist.copy(apiKey = mdbKey),
        // Device-specific: the update check and mpv's own options stay as this profile had them.
        checkForUpdates = target.checkForUpdates,
        betaUpdates = target.betaUpdates,
        // The IntroDB key is this profile's own, like the API keys above.
        player = source.player.copy(
            extraMpvArgs = target.player.extraMpvArgs,
            builtInGpu = target.player.builtInGpu,
            introDbApiKey = target.player.introDbApiKey?.takeIf { it.isNotBlank() } ?: source.player.introDbApiKey.takeIf { copyMissingKeys }
        ),
        schema = target.schema
    )
}

// --- The profile editor's Preferences tab ---

/**
 * The settings people most often want to differ between profiles, as the TV's profile editor
 * shows them (`ProfileEditorPreferences`). Each lives in that profile's own `settings.json`.
 */
data class ProfilePreferences(
    /** Null: the file's default track. */
    val audioLanguage: String?,
    /** Null: the device language. */
    val subtitleLanguage: String?,
    val subtitlesOff: Boolean,
    val autoplayNextEpisode: Boolean
) {
    fun applyTo(settings: DesktopSettings): DesktopSettings {
        val p = settings.player
        val mode = when {
            subtitlesOff -> SubtitleMode.OFF
            p.subtitleMode == SubtitleMode.OFF -> SubtitleMode.AUTO
            else -> p.subtitleMode
        }
        return settings.copy(
            player = p.copy(
                audioLanguage = audioLanguage,
                subtitleLanguage = subtitleLanguage,
                subtitleMode = mode,
                autoplayNextEpisode = autoplayNextEpisode
            )
        )
    }

    companion object {
        fun from(settings: DesktopSettings) = ProfilePreferences(
            audioLanguage = settings.player.audioLanguage,
            subtitleLanguage = settings.player.subtitleLanguage,
            subtitlesOff = settings.player.subtitleMode == SubtitleMode.OFF,
            autoplayNextEpisode = settings.player.autoplayNextEpisode
        )
    }
}

// --- Viewing activity ---

/** One line of a profile's viewing activity (the TV's `ViewingActivityEntry`). */
data class ViewingActivityEntry(
    val contentId: String,
    val contentType: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val watchedAt: Long,
    /** Null when it was finished rather than stopped part way. */
    val percentWatched: Int?
) {
    val episodeLine: String?
        get() = if (season == null || episode == null) null
        else listOfNotNull("S$season E$episode", episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

const val VIEWING_ACTIVITY_LIMIT = 300

/**
 * What a profile watched, newest first, as the TV lists it: titles it finished and what it
 * stopped part way through. A title in both appears once, as whichever happened last. Next Up
 * entries (not started yet) and removed ones say nothing about watching, so they're left out.
 */
fun viewingActivity(file: ProgressFile, limit: Int = VIEWING_ACTIVITY_LIMIT): List<ViewingActivityEntry> {
    val byKey = HashMap<Triple<String, Int?, Int?>, ViewingActivityEntry>()
    fun offer(entry: ViewingActivityEntry) {
        if (entry.watchedAt <= 0L) return
        val key = Triple(entry.contentId, entry.season, entry.episode)
        val existing = byKey[key]
        if (existing == null || entry.watchedAt > existing.watchedAt) byKey[key] = entry
    }
    file.finished.forEach { offer(ViewingActivityEntry(it.contentId, it.contentType, it.name, null, null, null, it.at, null)) }
    file.entries.filter { !it.upNext }.forEach { e ->
        val percent = when {
            e.durationMs > 0 -> (e.positionMs * 100 / e.durationMs).toInt()
            e.remotePercent != null -> e.remotePercent.toInt()
            else -> null
        }
        offer(ViewingActivityEntry(e.contentId, e.contentType, e.name, e.season, e.episode, e.episodeTitle, e.lastWatched, percent?.coerceIn(0, 100)))
    }
    return byKey.values.sortedByDescending { it.watchedAt }.take(limit)
}
