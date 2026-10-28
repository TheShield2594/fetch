package com.fetch.tv.core.sync

import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.data.local.ProfileRestrictions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParentalLimitsBlobTest {
    private fun blobWith(parental: JsonObject) = buildJsonObject {
        put("version", 1)
        put("features", JsonObject(emptyMap()))
        put(ParentalLimitsBlob.KEY, parental)
    }

    @Test
    fun `round trips limits`() {
        val teen = ProfileRestrictions(maxLevel = CertificationLevel.TEENS, allowUnrated = false)
        listOf(ProfileRestrictions.KidsPreset, teen, ProfileRestrictions.None).forEach { restrictions ->
            assertEquals(restrictions, ParentalLimitsBlob.decode(blobWith(ParentalLimitsBlob.encode(restrictions))))
        }
    }

    @Test
    fun `a blob without limits says nothing about them`() {
        assertNull(ParentalLimitsBlob.decode(buildJsonObject { put("version", 1) }))
        assertNull(ParentalLimitsBlob.decode(null))
    }

    @Test
    fun `an unknown rating reads as the strictest`() {
        val parental = Json.parseToJsonElement(
            """{"isKidsProfile":false,"maxRating":"SOMETHING_NEW","allowUnrated":true}"""
        ).jsonObject

        assertEquals(
            ProfileRestrictions(maxLevel = CertificationLevel.ALL_AGES, allowUnrated = true),
            ParentalLimitsBlob.decode(blobWith(parental))
        )
    }

    @Test
    fun `reads the website's shape`() {
        val parental = Json.parseToJsonElement(
            """{"isKidsProfile":true,"maxRating":"PARENTAL_GUIDANCE","allowUnrated":false}"""
        ).jsonObject

        assertEquals(ProfileRestrictions.KidsPreset, ParentalLimitsBlob.decode(blobWith(parental)))
    }
}
