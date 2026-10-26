package com.fetch.tv.data.website

import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.data.local.BundledAvatars
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.domain.model.PROFILE_AVATAR_COLORS
import com.fetch.tv.domain.model.UserProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Translation between the website's profiles and the TV's, kept pure so it can be tested. */
object FetchWebsiteMappings {
    /** The TV profile a website profile becomes, in the slot the website assigned this TV. */
    fun toUserProfile(remote: WebsiteSlottedProfile, local: UserProfile?): UserProfile = UserProfile(
        id = remote.slot,
        name = remote.name,
        avatarColorHex = remote.avatarColorHex
            ?: local?.avatarColorHex
            ?: PROFILE_AVATAR_COLORS[(remote.slot - 1).mod(PROFILE_AVATAR_COLORS.size)],
        usesPrimaryAddons = remote.usesPrimaryAddons,
        avatarId = remote.avatarId,
        // The website only knows avatar ids, and it decides the picture while this TV is
        // signed in to it.
        avatarUrl = null,
        profileBackgroundId = remote.profileBackgroundId,
        profileBackgroundUrl = local?.profileBackgroundUrl?.takeIf { remote.profileBackgroundId == local.profileBackgroundId }
    )

    /** The website's parental settings as the TV enforces them. */
    fun toRestrictions(parental: WebsiteParental): ProfileRestrictions = ProfileRestrictions(
        isKidsProfile = parental.isKidsProfile,
        maxLevel = CertificationLevel.fromName(parental.maxRating),
        allowUnrated = parental.allowUnrated
    )

    /** What the TV sends for a profile edited here. Never a PIN: those are checked by the website. */
    fun toWebsiteBody(profile: UserProfile, restrictions: ProfileRestrictions): JsonObject = buildJsonObject {
        put("name", profile.name)
        put("avatarColorHex", profile.avatarColorHex.takeIf { COLOR.matches(it) })
        // Only ids the website can draw; a server-catalog avatar has no meaning there.
        put("avatarId", profile.avatarId?.takeIf { it.startsWith(BundledAvatars.ID_PREFIX) })
        put("profileBackgroundId", profile.profileBackgroundId?.takeIf { ASSET_ID.matches(it) })
        if (!profile.isPrimary) put("usesPrimaryAddons", profile.usesPrimaryAddons)
        put(
            "parental",
            buildJsonObject {
                put("isKidsProfile", restrictions.isKidsProfile)
                put("maxRating", restrictions.maxLevel?.name)
                put("allowUnrated", restrictions.allowUnrated)
            }
        )
    }

    private val COLOR = Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")
    private val ASSET_ID = Regex("^[A-Za-z0-9:_.-]{1,100}$")
}
