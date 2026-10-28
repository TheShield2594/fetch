package com.fetch.tv.core.sync

import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.data.local.ProfileRestrictions
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A profile's parental limits as carried in its synced settings blob, next to "features".
 * Shaped like the Fetch website's `parental` object, so the two read the same way.
 */
internal object ParentalLimitsBlob {
    const val KEY = "parental"

    fun encode(restrictions: ProfileRestrictions): JsonObject = buildJsonObject {
        put("isKidsProfile", restrictions.isKidsProfile)
        put("maxRating", restrictions.maxLevel?.name)
        put("allowUnrated", restrictions.allowUnrated)
    }

    /**
     * The limits in [blob], or null when it holds none: written by a client that doesn't sync
     * them yet. A rating this build doesn't know is read as the strictest one rather than none,
     * so an unreadable limit can't loosen anything.
     */
    fun decode(blob: JsonObject?): ProfileRestrictions? {
        val parental = blob?.get(KEY) as? JsonObject ?: return null
        val kids = runCatching { parental["isKidsProfile"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
        val rawRating = parental["maxRating"]?.takeUnless { it is JsonNull }
            ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val level = rawRating?.let { CertificationLevel.fromName(it) ?: CertificationLevel.ALL_AGES }
        val allowUnrated = runCatching { parental["allowUnrated"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: true
        return ProfileRestrictions(isKidsProfile = kids, maxLevel = level, allowUnrated = level == null || allowUnrated)
    }
}
