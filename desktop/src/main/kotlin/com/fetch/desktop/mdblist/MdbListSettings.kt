package com.fetch.desktop.mdblist

import com.fetch.tv.domain.model.MDBListRatings
import com.fetch.tv.domain.model.MDBListSettings
import kotlinx.serialization.Serializable

/**
 * The TV's MDBList settings (`mdblist_settings`, shared [MDBListSettings]), saved with this
 * profile's settings. Off by default; [apiKey] is this profile's own key for ratings (a connected
 * MDBList account is used when it's empty). Everything but the key syncs with the TV.
 */
@Serializable
data class MdbListSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val showTrakt: Boolean = true,
    val showImdb: Boolean = true,
    val showTmdb: Boolean = true,
    val showLetterboxd: Boolean = true,
    val showTomatoes: Boolean = true,
    val showAudience: Boolean = true,
    val showMetacritic: Boolean = true,
    val showMal: Boolean = true,
    /** "Use Ratings on Hero": the badges on Home's hero too, not only on title pages. */
    val showOnHero: Boolean = false,
    val ratingOrder: List<String> = MDBListSettings.DEFAULT_RATING_ORDER
) {
    fun toShared() = MDBListSettings(
        enabled, apiKey, showTrakt, showImdb, showTmdb, showLetterboxd, showTomatoes, showAudience,
        showMetacritic, showMal, showOnHero, ratingOrder
    )

    /** Every provider, in the saved order, including any the saved order is missing. */
    val fullOrder: List<String>
        get() = ratingOrder.filter { it in PROVIDERS } .distinct() + PROVIDERS.filter { it !in ratingOrder }

    /** The enabled providers in order: what the badges show. */
    fun enabledOrder(): List<String> = toShared().copy(ratingOrder = fullOrder).enabledRatingOrder()

    fun shows(provider: String): Boolean = provider in toShared().copy(ratingOrder = PROVIDERS).enabledRatingOrder()

    fun withShown(provider: String, on: Boolean): MdbListSettings = when (provider) {
        "trakt" -> copy(showTrakt = on)
        "imdb" -> copy(showImdb = on)
        "tmdb" -> copy(showTmdb = on)
        "letterboxd" -> copy(showLetterboxd = on)
        "tomatoes" -> copy(showTomatoes = on)
        "audience" -> copy(showAudience = on)
        "metacritic" -> copy(showMetacritic = on)
        "mal" -> copy(showMal = on)
        else -> this
    }

    /** Moves [provider] up (-1) or down (+1) in the order. */
    fun moved(provider: String, delta: Int): MdbListSettings {
        val order = fullOrder.toMutableList()
        val i = order.indexOf(provider)
        val j = i + delta
        if (i < 0 || j !in order.indices) return this
        order.add(j, order.removeAt(i))
        return copy(ratingOrder = order)
    }

    /** [ratings] with the providers this profile hides removed, as the TV's `getRatingsForMeta`. */
    fun filter(ratings: MDBListRatings): MDBListRatings = MDBListRatings(
        trakt = ratings.trakt.takeIf { showTrakt },
        imdb = ratings.imdb.takeIf { showImdb },
        tmdb = ratings.tmdb.takeIf { showTmdb },
        letterboxd = ratings.letterboxd.takeIf { showLetterboxd },
        tomatoes = ratings.tomatoes.takeIf { showTomatoes },
        audience = ratings.audience.takeIf { showAudience },
        metacritic = ratings.metacritic.takeIf { showMetacritic },
        mal = ratings.mal.takeIf { showMal },
        tomatoesCertified = showTomatoes && ratings.tomatoesCertified,
        audienceCertified = showAudience && ratings.audienceCertified
    )

    companion object {
        val PROVIDERS = MDBListSettings.DEFAULT_RATING_ORDER

        fun label(provider: String): String = when (provider) {
            "imdb" -> "IMDb"
            "trakt" -> "Trakt"
            "tmdb" -> "TMDB"
            "tomatoes" -> "Rotten Tomatoes"
            "audience" -> "Audience score"
            "letterboxd" -> "Letterboxd"
            "metacritic" -> "Metacritic"
            "mal" -> "MyAnimeList"
            else -> provider
        }
    }
}

/** [provider]'s value in [ratings], or null when it has none. */
fun MDBListRatings.valueOf(provider: String): Double? = when (provider) {
    "trakt" -> trakt
    "imdb" -> imdb
    "tmdb" -> tmdb
    "letterboxd" -> letterboxd
    "tomatoes" -> tomatoes
    "audience" -> audience
    "metacritic" -> metacritic
    "mal" -> mal
    else -> null
}

/**
 * The value as the TV writes it: one decimal for IMDb and Letterboxd, whole numbers kept whole.
 * Rotten Tomatoes' scores are percentages, so they get a `%`.
 */
fun formatMdbListRating(provider: String, value: Double): String = when (provider) {
    "imdb", "letterboxd" -> "%.1f".format(java.util.Locale.ROOT, value)
    else -> if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, value)
} + if (provider == "tomatoes" || provider == "audience") "%" else ""
