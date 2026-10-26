package com.fetch.tv.data.website

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

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
    @SerialName("code") val code: String? = null
)
