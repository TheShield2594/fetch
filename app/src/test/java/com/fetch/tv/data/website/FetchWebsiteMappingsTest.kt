package com.fetch.tv.data.website

import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.domain.model.UserProfile
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchWebsiteMappingsTest {
    private fun remote(slot: Int = 2, parental: WebsiteParental = WebsiteParental()) = WebsiteSlottedProfile(
        slot = slot,
        id = "11111111-1111-4111-8111-111111111111",
        name = "Kid",
        hasPin = true,
        avatarColorHex = "#43A047",
        avatarId = "fetch:panda",
        usesPrimaryAddons = true,
        parental = parental
    )

    @Test
    fun `a website profile lands in its slot with its colour and picture`() {
        val profile = FetchWebsiteMappings.toUserProfile(remote(), local = null)

        assertEquals(2, profile.id)
        assertEquals("Kid", profile.name)
        assertEquals("#43A047", profile.avatarColorHex)
        assertEquals("fetch:panda", profile.avatarId)
        assertTrue(profile.usesPrimaryAddons)
        assertNull(profile.avatarUrl)
    }

    @Test
    fun `a profile with no colour keeps the one it had on the TV`() {
        val local = UserProfile(id = 2, name = "Old", avatarColorHex = "#8E24AA")

        val profile = FetchWebsiteMappings.toUserProfile(remote().copy(avatarColorHex = null), local)

        assertEquals("#8E24AA", profile.avatarColorHex)
    }

    @Test
    fun `parental settings become the restrictions the TV enforces`() {
        val restrictions = FetchWebsiteMappings.toRestrictions(
            WebsiteParental(isKidsProfile = true, maxRating = "PARENTAL_GUIDANCE", allowUnrated = false)
        )

        assertTrue(restrictions.isKidsProfile)
        assertEquals(CertificationLevel.PARENTAL_GUIDANCE, restrictions.maxLevel)
        assertFalse(restrictions.allowUnrated)
        assertEquals(ProfileRestrictions.None, FetchWebsiteMappings.toRestrictions(WebsiteParental()))
    }

    @Test
    fun `an edit on the TV sends appearance and limits, never a PIN`() {
        val body = FetchWebsiteMappings.toWebsiteBody(
            UserProfile(id = 3, name = "Sam", avatarColorHex = "#1E88E5", usesPrimaryAddons = true, avatarId = "fetch:fox"),
            ProfileRestrictions(maxLevel = CertificationLevel.TEENS)
        )

        assertEquals(JsonPrimitive("Sam"), body["name"])
        assertEquals(JsonPrimitive("fetch:fox"), body["avatarId"])
        assertEquals(JsonPrimitive(true), body["usesPrimaryAddons"])
        assertEquals(JsonPrimitive("TEENS"), (body["parental"] as JsonObject)["maxRating"])
        assertFalse(body.containsKey("pin"))
    }

    @Test
    fun `an avatar from another sync server isn't sent, and the primary never shares addons`() {
        val body = FetchWebsiteMappings.toWebsiteBody(
            UserProfile(id = 1, name = "Me", avatarColorHex = "#1E88E5", avatarId = "catalog-42"),
            ProfileRestrictions.None
        )

        assertEquals(JsonNull, body["avatarId"])
        assertFalse(body.containsKey("usesPrimaryAddons"))
        assertEquals(JsonNull, (body["parental"] as JsonObject)["maxRating"])
    }
}
