package com.fetch.tv.core.parental

/**
 * One rung of the age ladder that profile limits are expressed in. Movie (MPA) and TV
 * (US TV Parental Guidelines) ratings share a ladder so a single limit covers both.
 */
enum class CertificationLevel(val rank: Int) {
    ALL_AGES(0),
    OLDER_KIDS(1),
    PARENTAL_GUIDANCE(2),
    TEENS(3),
    MATURE(4),
    ADULTS_ONLY(5);

    companion object {
        fun fromName(raw: String?): CertificationLevel? =
            raw?.let { name -> entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    }
}

/**
 * Parental limit for a profile. [maxLevel] null means unrestricted.
 */
data class ContentRestriction(
    val maxLevel: CertificationLevel? = null,
    val allowUnrated: Boolean = true
) {
    val isRestricted: Boolean get() = maxLevel != null

    /** True when a title with the given (possibly unknown) level may be shown. */
    fun allows(level: CertificationLevel?): Boolean {
        val limit = maxLevel ?: return true
        if (level == null) return allowUnrated
        return level.rank <= limit.rank
    }

    fun allowsCertification(certification: String?, country: String? = null): Boolean =
        allows(ContentCertifications.levelOf(certification, country))

    companion object {
        val Unrestricted = ContentRestriction()
    }
}

object ContentCertifications {
    /** Countries checked, in order, when picking a certification from TMDB data. */
    val PREFERRED_COUNTRIES = listOf("US", "GB", "CA", "AU", "DE", "NL", "FR")

    private val US = mapOf(
        "G" to CertificationLevel.ALL_AGES,
        "TV-Y" to CertificationLevel.ALL_AGES,
        "TV-G" to CertificationLevel.ALL_AGES,
        "TV-Y7" to CertificationLevel.OLDER_KIDS,
        "TV-Y7-FV" to CertificationLevel.OLDER_KIDS,
        "PG" to CertificationLevel.PARENTAL_GUIDANCE,
        "TV-PG" to CertificationLevel.PARENTAL_GUIDANCE,
        "PG-13" to CertificationLevel.TEENS,
        "TV-14" to CertificationLevel.TEENS,
        "R" to CertificationLevel.MATURE,
        "TV-MA" to CertificationLevel.MATURE,
        "NC-17" to CertificationLevel.ADULTS_ONLY,
        "X" to CertificationLevel.ADULTS_ONLY
    )

    private val GB = mapOf(
        "U" to CertificationLevel.ALL_AGES,
        "UC" to CertificationLevel.ALL_AGES,
        "PG" to CertificationLevel.PARENTAL_GUIDANCE,
        "12" to CertificationLevel.TEENS,
        "12A" to CertificationLevel.TEENS,
        "15" to CertificationLevel.MATURE,
        "18" to CertificationLevel.ADULTS_ONLY,
        "R18" to CertificationLevel.ADULTS_ONLY
    )

    private val CA = mapOf(
        "G" to CertificationLevel.ALL_AGES,
        "C" to CertificationLevel.ALL_AGES,
        "C8" to CertificationLevel.OLDER_KIDS,
        "PG" to CertificationLevel.PARENTAL_GUIDANCE,
        "14+" to CertificationLevel.TEENS,
        "14A" to CertificationLevel.TEENS,
        "18A" to CertificationLevel.MATURE,
        "18+" to CertificationLevel.ADULTS_ONLY,
        "R" to CertificationLevel.ADULTS_ONLY
    )

    private val AU = mapOf(
        "G" to CertificationLevel.ALL_AGES,
        "P" to CertificationLevel.ALL_AGES,
        "C" to CertificationLevel.ALL_AGES,
        "PG" to CertificationLevel.PARENTAL_GUIDANCE,
        "M" to CertificationLevel.TEENS,
        "MA15+" to CertificationLevel.MATURE,
        "MA 15+" to CertificationLevel.MATURE,
        "AV15+" to CertificationLevel.MATURE,
        "R18+" to CertificationLevel.ADULTS_ONLY,
        "R 18+" to CertificationLevel.ADULTS_ONLY,
        "X18+" to CertificationLevel.ADULTS_ONLY
    )

    // Numeric age systems (DE FSK, NL Kijkwijzer, FR CSA and similar).
    private fun numericAgeLevel(age: Int): CertificationLevel = when {
        age <= 0 -> CertificationLevel.ALL_AGES
        age <= 7 -> CertificationLevel.OLDER_KIDS
        age <= 10 -> CertificationLevel.PARENTAL_GUIDANCE
        age <= 14 -> CertificationLevel.TEENS
        age <= 17 -> CertificationLevel.MATURE
        else -> CertificationLevel.ADULTS_ONLY
    }

    private val UNRATED = setOf("", "NR", "NOT RATED", "UNRATED", "N/A", "NA", "TBC", "TBA", "E", "EXEMPT", "-")

    /**
     * Maps a certification string to the shared ladder. [country] is an ISO 3166-1 code;
     * without one, US labels are tried first, then other known systems and plain ages.
     * Returns null for unrated or unknown labels.
     */
    fun levelOf(certification: String?, country: String? = null): CertificationLevel? {
        val normalized = certification
            ?.trim()
            ?.uppercase()
            ?.removePrefix("RATED ")
            ?.removePrefix("FSK ")
            ?.removePrefix("FSK")
            ?.trim()
            ?: return null
        if (normalized in UNRATED) return null

        val table = when (country?.uppercase()) {
            "US" -> US
            "GB", "IE" -> GB
            "CA" -> CA
            "AU", "NZ" -> AU
            null -> null
            else -> emptyMap()
        }
        if (table != null) {
            table[normalized]?.let { return it }
            return parseAge(normalized)?.let(::numericAgeLevel)
        }

        US[normalized]?.let { return it }
        GB[normalized]?.let { return it }
        AU[normalized]?.let { return it }
        CA[normalized]?.let { return it }
        return parseAge(normalized)?.let(::numericAgeLevel)
    }

    /**
     * Picks the most relevant certification from per-country values, preferring
     * [PREFERRED_COUNTRIES] order and skipping blank entries.
     */
    fun pickLevel(byCountry: Map<String, List<String>>): CertificationLevel? {
        val normalized = byCountry.mapKeys { it.key.uppercase() }
        val ordered = PREFERRED_COUNTRIES.mapNotNull { country -> normalized[country]?.let { country to it } } +
            normalized.filterKeys { it !in PREFERRED_COUNTRIES }.toList()
        for ((country, values) in ordered) {
            val levels = values.mapNotNull { levelOf(it, country) }
            // A title can carry several US ratings (theatrical, unrated cut); the strictest wins.
            levels.maxByOrNull { it.rank }?.let { return it }
        }
        return null
    }

    private fun parseAge(raw: String): Int? {
        val digits = raw.trimEnd('+').trim()
        return digits.toIntOrNull()?.takeIf { it in 0..21 }
    }
}
