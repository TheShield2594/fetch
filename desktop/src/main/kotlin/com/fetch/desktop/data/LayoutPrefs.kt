package com.fetch.desktop.data

import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.domain.model.DetailImdbRatingsVisibility
import com.fetch.tv.domain.model.EpisodeOptionsOverlayStyle
import com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.fetch.tv.domain.model.HomeImdbRatingsVisibility
import com.fetch.tv.domain.model.THEME_CARD_CORNER_RADIUS
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The TV app's Layout settings beyond the first few (`layout_settings` and `trailer_settings`
 * there): Continue Watching cards, the hero, poster cards, focused posters and trailers, the
 * title page and custom posters. Field defaults are the TV's; [LayoutSync] maps each to its key.
 *
 * Catalog keys ([heroCatalogKeys], [customCatalogTitles]) are the TV's legacy row keys
 * (`addonId_type_catalogId`, see [com.fetch.tv.domain.model.catalogRowLegacyKey]), so they sync
 * as they are; collections are `collection_<id>`.
 */
@Serializable
data class LayoutPrefs(
    // --- Continue Watching ---
    val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    /** The episode's still instead of the show's backdrop on a card. */
    val episodeThumbnailsInContinueWatching: Boolean = true,
    /** Blur the still of a Next Up episode you haven't seen. */
    val blurContinueWatchingNextUp: Boolean = false,

    // --- Home ---
    val heroEnabled: Boolean = true,
    /** Rows whose titles the hero cycles through; empty follows the pointer as before. */
    val heroCatalogKeys: List<String> = emptyList(),
    /** The hero's backdrop fills the whole window instead of its top half. */
    val fullScreenHeroBackdrop: Boolean = true,
    /** Home catalogs keep the addons' order; collections still go where you put them. */
    val followAddonsOrder: Boolean = false,
    /**
     * Home catalogs order set here: desktop row keys and `collection_<id>`s (see
     * [com.fetch.desktop.home.HomeLayout.middle]); empty keeps addon order, collections after.
     * A synced order (`home_catalog_shared`) holds the TV's catalog keys instead; collection
     * places go to the account with the catalog order and come back from it.
     */
    val homeOrder: List<String> = emptyList(),
    /** Renamed rows, by TV row key. */
    val customCatalogTitles: Map<String, String> = emptyMap(),
    /** IMDb and TMDB ratings on Home (hero and cards). */
    val homeRatings: HomeImdbRatingsVisibility = HomeImdbRatingsVisibility.SHOW_ALL,
    /** "Landscape Poster Mode": landscape cards everywhere (the TV's default is on; the desktop keeps posters). */
    val landscapePosters: Boolean = false,
    /** Landscape cards use the backdrop with the title logo over it, not the addon's landscape art. */
    val alwaysShowLandscapeLogo: Boolean = false,

    // --- Poster card (TV dp; the desktop scales them, see [cardScale]) ---
    val posterCardWidthDp: Int = TV_POSTER_WIDTH_DP,
    val posterCardHeightDp: Int = TV_POSTER_HEIGHT_DP,
    /** [THEME_CARD_CORNER_RADIUS] lets the theme decide. */
    val posterCardCornerRadiusDp: Int = THEME_CARD_CORNER_RADIUS,

    // --- Focused (hovered) poster ---
    val expandFocusedPoster: Boolean = true,
    val expandFocusedPosterDelaySeconds: Int = 3,
    val focusedPosterTrailer: Boolean = false,
    val focusedPosterTrailerMuted: Boolean = true,
    val focusedPosterTrailerTarget: FocusedPosterTrailerPlaybackTarget = FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
    val trailerCropToFill: Boolean = true,
    /** `trailer_settings`: trailers on, and how long to wait before one starts. */
    val trailersEnabled: Boolean = true,
    val trailerDelaySeconds: Int = 7,

    // --- Title page ---
    val episodeOptionsOverlay: EpisodeOptionsOverlayStyle = EpisodeOptionsOverlayStyle.BLUR,
    /** "Shuffle": the random-episode button on series. */
    val randomEpisodeEnabled: Boolean = false,
    val blurUnwatchedEpisodes: Boolean = false,
    val detailRatings: DetailImdbRatingsVisibility = DetailImdbRatingsVisibility.SHOW_ALL,
    val detailTrailerButton: Boolean = true,
    /** Read details from the meta addons first, the catalog's addon only if they fail. */
    val preferExternalMeta: Boolean = true,
    /** Movies show their full release date, not just the year. */
    val showFullReleaseDate: Boolean = true,

    /** `player_settings.ready_check_enabled`: ask addons in the background what's ready (Ready tonight). */
    val readyCheckEnabled: Boolean = true,

    // --- Custom posters ---
    val customPosterUrlPattern: String = "",
    /** [com.fetch.desktop.poster.CustomPosterScreen] keys; null means every screen. */
    val customPosterScreens: Set<String>? = null,
) {
    /** How much bigger than the TV's default this card is, for the desktop's own default sizes. */
    val cardWidthScale: Float get() = posterCardWidthDp.coerceIn(60, 400) / TV_POSTER_WIDTH_DP.toFloat()
    val cardHeightScale: Float get() = posterCardHeightDp.coerceIn(90, 600) / TV_POSTER_HEIGHT_DP.toFloat()

    companion object {
        const val TV_POSTER_WIDTH_DP = 126
        const val TV_POSTER_HEIGHT_DP = 189
    }
}

/**
 * Each [LayoutPrefs] field's TV key and stored type, and how the blob's typed entries
 * (`{"type":"boolean","value":true}`) read into them. Keys are `feature/key`.
 */
object LayoutSync {
    const val LAYOUT = "layout_settings"
    const val TRAILER = "trailer_settings"
    const val PLAYER = "player_settings"

    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())
    private val stringMap = MapSerializer(String.serializer(), String.serializer())

    private class Field(
        val feature: String,
        val key: String,
        val type: String,
        val read: (LayoutPrefs) -> Any?,
        val write: (LayoutPrefs, JsonObject) -> LayoutPrefs?
    ) {
        val id get() = "$feature/$key"
    }

    private fun JsonObject.raw() = this["value"] as? JsonPrimitive
    private fun JsonObject.bool(): Boolean? = raw()?.contentOrNull?.toBooleanStrictOrNull()
    private fun JsonObject.int(): Int? = raw()?.intOrNull
    private fun JsonObject.str(): String? = raw()?.takeIf { it.isString }?.contentOrNull

    private fun bool(feature: String, key: String, get: (LayoutPrefs) -> Boolean, set: LayoutPrefs.(Boolean) -> LayoutPrefs) =
        Field(feature, key, "boolean", get) { p, e -> e.bool()?.let { p.set(it) } }

    private fun int(feature: String, key: String, get: (LayoutPrefs) -> Int, set: LayoutPrefs.(Int) -> LayoutPrefs) =
        Field(feature, key, "int", get) { p, e -> e.int()?.let { p.set(it) } }

    private inline fun <reified E : Enum<E>> enum(key: String, noinline get: (LayoutPrefs) -> E, noinline set: LayoutPrefs.(E) -> LayoutPrefs) =
        Field(LAYOUT, key, "string", { get(it).name }) { p, e ->
            e.str()?.let { name -> enumValues<E>().firstOrNull { it.name == name } }?.let { p.set(it) }
        }

    private val fields: List<Field> = listOf(
        enum("continue_watching_card_style", { it.continueWatchingCardStyle }) { copy(continueWatchingCardStyle = it) },
        bool(LAYOUT, "use_episode_thumbnails_in_cw", { it.episodeThumbnailsInContinueWatching }) { copy(episodeThumbnailsInContinueWatching = it) },
        bool(LAYOUT, "blur_continue_watching_next_up", { it.blurContinueWatchingNextUp }) { copy(blurContinueWatchingNextUp = it) },
        bool(LAYOUT, "hero_section_enabled", { it.heroEnabled }) { copy(heroEnabled = it) },
        Field(LAYOUT, "hero_catalog_keys", "string", { json.encodeToString(stringList, it.heroCatalogKeys) }) { p, e ->
            e.str()?.let { raw -> runCatching { json.decodeFromString(stringList, raw) }.getOrNull() }
                ?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                ?.let { p.copy(heroCatalogKeys = it) }
        },
        bool(LAYOUT, "modern_hero_full_screen_backdrop", { it.fullScreenHeroBackdrop }) { copy(fullScreenHeroBackdrop = it) },
        bool(LAYOUT, "follow_addons_order", { it.followAddonsOrder }) { copy(followAddonsOrder = it) },
        Field(LAYOUT, "custom_catalog_titles", "string", { json.encodeToString(stringMap, it.customCatalogTitles) }) { p, e ->
            e.str()?.let { raw -> runCatching { json.decodeFromString(stringMap, raw) }.getOrNull() }
                ?.filterValues { it.isNotBlank() }
                ?.let { p.copy(customCatalogTitles = it) }
        },
        enum("home_imdb_ratings_visibility", { it.homeRatings }) { copy(homeRatings = it) },
        bool(LAYOUT, "modern_landscape_posters_enabled", { it.landscapePosters }) { copy(landscapePosters = it) },
        bool(LAYOUT, "always_show_landscape_clearlogo", { it.alwaysShowLandscapeLogo }) { copy(alwaysShowLandscapeLogo = it) },
        int(LAYOUT, "poster_card_width_dp", { it.posterCardWidthDp }) { v -> if (v > 0) copy(posterCardWidthDp = v) else this },
        int(LAYOUT, "poster_card_height_dp", { it.posterCardHeightDp }) { v -> if (v > 0) copy(posterCardHeightDp = v) else this },
        int(LAYOUT, "poster_card_corner_radius_dp", { it.posterCardCornerRadiusDp }) { copy(posterCardCornerRadiusDp = it) },
        bool(LAYOUT, "focused_poster_backdrop_expand_enabled", { it.expandFocusedPoster }) { copy(expandFocusedPoster = it) },
        int(LAYOUT, "focused_poster_backdrop_expand_delay_seconds", { it.expandFocusedPosterDelaySeconds }) { copy(expandFocusedPosterDelaySeconds = it.coerceAtLeast(0)) },
        bool(LAYOUT, "focused_poster_backdrop_trailer_enabled", { it.focusedPosterTrailer }) { copy(focusedPosterTrailer = it) },
        bool(LAYOUT, "focused_poster_backdrop_trailer_muted", { it.focusedPosterTrailerMuted }) { copy(focusedPosterTrailerMuted = it) },
        enum("focused_poster_backdrop_trailer_playback_target", { it.focusedPosterTrailerTarget }) { copy(focusedPosterTrailerTarget = it) },
        bool(LAYOUT, "trailer_crop_to_fill", { it.trailerCropToFill }) { copy(trailerCropToFill = it) },
        bool(TRAILER, "trailer_enabled", { it.trailersEnabled }) { copy(trailersEnabled = it) },
        int(TRAILER, "trailer_delay_seconds", { it.trailerDelaySeconds }) { copy(trailerDelaySeconds = it.coerceAtLeast(0)) },
        enum("episode_options_overlay_style", { it.episodeOptionsOverlay }) { copy(episodeOptionsOverlay = it) },
        bool(LAYOUT, "random_episode_enabled", { it.randomEpisodeEnabled }) { copy(randomEpisodeEnabled = it) },
        bool(LAYOUT, "blur_unwatched_episodes", { it.blurUnwatchedEpisodes }) { copy(blurUnwatchedEpisodes = it) },
        // The TV reads HIDE_ALL here as HIDE_EPISODES (overall ratings are the home setting).
        enum<DetailImdbRatingsVisibility>("detail_imdb_ratings_visibility", { it.detailRatings }) { copy(detailRatings = it.asEpisodeVisibility()) },
        bool(LAYOUT, "detail_page_trailer_button_enabled", { it.detailTrailerButton }) { copy(detailTrailerButton = it) },
        bool(LAYOUT, "prefer_external_meta_addon_detail", { it.preferExternalMeta }) { copy(preferExternalMeta = it) },
        bool(LAYOUT, "show_full_release_date", { it.showFullReleaseDate }) { copy(showFullReleaseDate = it) },
        bool(PLAYER, "ready_check_enabled", { it.readyCheckEnabled }) { copy(readyCheckEnabled = it) },
        Field(LAYOUT, "custom_poster_url_pattern", "string", { it.customPosterUrlPattern }) { p, e -> e.str()?.let { p.copy(customPosterUrlPattern = it.trim()) } },
        // "none" for none, else keys joined by commas (the TV also reads every key as "all").
        Field(LAYOUT, "custom_poster_enabled_screens", "string", { p ->
            val keys = p.customPosterScreens ?: com.fetch.desktop.poster.CustomPosterScreen.entries.map { it.key }.toSet()
            if (keys.isEmpty()) "none" else keys.sorted().joinToString(",")
        }) { p, e ->
            e.str()?.let { raw ->
                val keys = if (raw.isBlank() || raw == "none") emptySet() else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val all = com.fetch.desktop.poster.CustomPosterScreen.entries.map { it.key }.toSet()
                p.copy(customPosterScreens = keys.takeUnless { it.containsAll(all) })
            }
        },
    )

    /** Every `feature/key` this table syncs. */
    val ids: List<String> get() = fields.map { it.id }

    /** The blob's (feature, key) for [id]. */
    fun split(id: String): Pair<String, String> = id.substringBefore('/') to id.substringAfter('/')

    /** [prefs] as the TV's typed entries, by `feature/key`. Values the TV stores as absent (all custom-poster screens) are left out. */
    fun encode(prefs: LayoutPrefs): Map<String, JsonObject> = fields.mapNotNull { f ->
        val value = f.read(prefs) ?: return@mapNotNull null
        f.id to buildJsonObject {
            put("type", f.type)
            when (value) {
                is Boolean -> put("value", value)
                is Int -> put("value", value)
                else -> put("value", value.toString())
            }
        }
    }.toMap()

    /** [prefs] with every entry in [values] the table knows applied; anything unreadable is skipped. */
    fun apply(values: Map<String, JsonObject>, prefs: LayoutPrefs): LayoutPrefs {
        var out = prefs
        for (f in fields) {
            val entry = values[f.id] ?: continue
            if ((entry["type"] as? JsonPrimitive)?.contentOrNull != f.type) continue
            f.write(out, entry)?.let { out = it }
        }
        return out
    }

    /** For tests: an entry as the TV writes it. */
    internal fun entry(type: String, value: Any): JsonObject = buildJsonObject {
        put("type", type)
        when (value) {
            is Boolean -> put("value", value)
            is Int -> put("value", value)
            is List<*> -> put("value", JsonArray(value.map { JsonPrimitive(it.toString()) }))
            else -> put("value", value.toString())
        }
    }
}
