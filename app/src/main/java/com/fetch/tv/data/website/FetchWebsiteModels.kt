package com.fetch.tv.data.website

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class WebsitePairingStart(
    val code: String,
    val pollSecret: String,
    val expiresAt: String,
    val intervalSec: Int = 3,
    /** The website's confirm page with the code filled in; null when the server doesn't know its own address. */
    val confirmUrl: String? = null
)

@Serializable
internal data class WebsitePairingPollBody(
    val status: String,
    val token: String? = null,
    val device: WebsiteDevice? = null
)

@Serializable
data class WebsiteDevice(
    val id: String,
    val name: String
)

sealed interface WebsitePairingPoll {
    data object Pending : WebsitePairingPoll
    data object Expired : WebsitePairingPoll
    data class Approved(val token: String, val deviceName: String) : WebsitePairingPoll
}

@Serializable
data class WebsiteParental(
    val isKidsProfile: Boolean = false,
    val maxRating: String? = null,
    val allowUnrated: Boolean = true
)

/** A website profile as `/devices/me/profiles` reports it: in the slot it occupies on this TV. */
@Serializable
data class WebsiteSlottedProfile(
    val slot: Int,
    val id: String,
    val name: String,
    val hasPin: Boolean = false,
    val avatarColorHex: String? = null,
    val avatarId: String? = null,
    val profileBackgroundId: String? = null,
    val usesPrimaryAddons: Boolean = false,
    val parental: WebsiteParental = WebsiteParental()
)

@Serializable
internal data class WebsiteSlottedProfiles(val profiles: List<WebsiteSlottedProfile> = emptyList())

@Serializable
internal data class WebsiteVerifyBody(val profileToken: String? = null)

@Serializable
internal data class WebsiteCollectionsBody(
    val collections: JsonArray = JsonArray(emptyList()),
    val updatedAt: String? = null
)

/** One title from a website list, in the Stremio meta-preview shape the API answers with. */
@Serializable
internal data class WebsiteMeta(
    val id: String,
    val type: String = "movie",
    val name: String,
    val poster: String? = null,
    val year: Int? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null
)

@Serializable
internal data class WebsiteMetasBody(val metas: List<WebsiteMeta> = emptyList())

@Serializable
internal data class WebsiteErrorBody(
    val error: String? = null,
    @SerialName("code") val code: String? = null,
    val retryAfterSec: Int? = null
)

/** An addon in a profile's list on the website, as much of it as the TV reads. */
@Serializable
data class WebsiteAddon(
    val manifestUrl: String,
    val enabled: Boolean = true,
    val name: String? = null
)

@Serializable
data class WebsiteAddonsBody(
    val addons: List<WebsiteAddon> = emptyList(),
    /** Null when nobody has ever set this profile's addons on the website. */
    val updatedAt: String? = null
)

/** One Home row on the website. [source] is only read, never sent back. */
@Serializable
data class WebsiteHomeRow(
    val key: String,
    val hidden: Boolean = false,
    val title: String? = null,
    val source: String? = null
)

@Serializable
data class WebsiteHomeLayout(
    val rows: List<WebsiteHomeRow> = emptyList(),
    val version: Int = 0
)

@Serializable
data class WebsiteTvSettings(
    val settings: JsonObject = JsonObject(emptyMap()),
    val version: Int = 0
)

// ─── Watch state and lists, for the website as a tracking provider ───

/** A resume point from `/playback`. Episodes carry their series id as [imdbId]. */
@Serializable
data class WebsitePlayback(
    val id: String,
    val type: String,
    val imdbId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val progress: Double = 0.0,
    val positionSeconds: Long? = null,
    val durationSeconds: Long? = null,
    val pausedAt: String,
    val name: String? = null,
    val poster: String? = null
)

@Serializable
internal data class WebsitePlaybackBody(val playback: List<WebsitePlayback> = emptyList())

@Serializable
data class WebsiteWatchedMovie(
    val imdbId: String,
    val watchedAt: String,
    val name: String? = null,
    val poster: String? = null
)

/** One watched episode from `/watch/state`, under its series id, with the series' name and poster. */
@Serializable
data class WebsiteWatchedEpisode(
    val imdbId: String,
    val season: Int,
    val episode: Int,
    val watchedAt: String,
    val name: String? = null,
    val poster: String? = null
)

/** Everything a profile has watched, once per title, and the shows it dropped. */
@Serializable
data class WebsiteWatchState(
    val movies: List<WebsiteWatchedMovie> = emptyList(),
    val episodes: List<WebsiteWatchedEpisode> = emptyList(),
    val dropped: List<String> = emptyList()
)

/** A list on the website: `watchlist` and `collection` are the profile's defaults, `custom` its own. */
@Serializable
data class WebsiteList(
    val id: String,
    val name: String,
    val kind: String
)

@Serializable
internal data class WebsiteListsBody(val lists: List<WebsiteList> = emptyList())

@Serializable
internal data class WebsiteCreatedListBody(val list: WebsiteList)

@Serializable
data class WebsiteListItemMetadata(
    val name: String? = null,
    val poster: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null
)

@Serializable
data class WebsiteListItem(
    val type: String,
    val imdbId: String,
    val addedAt: String? = null,
    val title: String? = null,
    val metadata: WebsiteListItemMetadata? = null
)

@Serializable
internal data class WebsiteListItemsBody(
    val items: List<WebsiteListItem> = emptyList(),
    val nextCursor: String? = null
)
