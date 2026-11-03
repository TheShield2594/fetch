package com.fetch.desktop.mdblist

import com.fetch.tv.domain.model.MDBListRatings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * MDBList's ratings for titles, by IMDb id, as the TV's `MdbListRatingsClient`: one title is a
 * GET of `/imdb/{movie|show}/{id}`, several a single POST of `/imdb/{type}/` with their ids. The
 * `keyword` extra says whether Rotten Tomatoes marks a title Certified Fresh or Verified Hot.
 */
class MdbListRatingsClient(private val http: MdbListHttp) {

    /**
     * Ratings for [imdbIds] of one [mediaType] (`movie` or `show`), by IMDb id. Titles MDBList
     * doesn't know are left out. Throws [MdbListException] when the request fails.
     */
    suspend fun ratings(mediaType: String, imdbIds: List<String>, auth: MdbListAuth): Map<String, MDBListRatings> {
        if (imdbIds.isEmpty()) return emptyMap()
        val type = normalizeMediaType(mediaType)
        if (imdbIds.size == 1) {
            val id = imdbIds.single()
            val response = http.get("/imdb/$type/$id", auth, mapOf("append_to_response" to "keyword"))
            val media = parse(response.body) as? JsonObject ?: return emptyMap()
            return mapOf(id to mdbListRatingsOf(media))
        }
        val body = buildJsonObject {
            putJsonArray("ids") { imdbIds.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("append_to_response") { add(JsonPrimitive("keyword")) }
        }
        val rows = parse(http.post("/imdb/$type/", body, auth).body) as? JsonArray ?: return emptyMap()
        return rows.mapNotNull { row ->
            val media = row as? JsonObject ?: return@mapNotNull null
            val id = responseImdbId(media) ?: return@mapNotNull null
            id to mdbListRatingsOf(media)
        }.toMap()
    }

    /** Whether [key] is a working API key: MDBList's `/user` answers for it. */
    suspend fun verify(key: String): Boolean = runCatching {
        http.get("/user", MdbListAuth.ApiKey(key.trim())).status in 200..299
    }.getOrDefault(false)

    private fun parse(body: String): JsonElement? = runCatching { Json.parseToJsonElement(body) }.getOrNull()

    companion object {
        /** MDBList's media types: `show` for series, `movie` otherwise. */
        fun normalizeMediaType(type: String): String = when (type.lowercase()) {
            "series", "tv", "show", "tvshow" -> "show"
            else -> "movie"
        }

        internal fun responseImdbId(media: JsonObject): String? {
            fun text(o: JsonObject?, key: String) = (o?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("tt") }
            return text(media, "imdb_id") ?: text(media, "imdbid") ?: text(media["ids"] as? JsonObject, "imdb")
        }
    }
}

/**
 * The TV's `MDBListMediaResponseDto.toRatings`: each known source's value on its own scale (IMDb
 * and MAL out of 10, Letterboxd out of 5, the rest out of 100). Letterboxd's `score` (out of 100)
 * is preferred over its `value`, and out-of-range values are dropped.
 */
fun mdbListRatingsOf(media: JsonObject): MDBListRatings {
    val keywords = (media["keywords"] as? JsonArray).orEmpty().mapNotNull { keyword ->
        val name = when (keyword) {
            is JsonObject -> (keyword["name"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> keyword.contentOrNull
            else -> null
        }
        name?.substringAfterLast('.')
    }.toSet()
    val values = (media["ratings"] as? JsonArray).orEmpty().mapNotNull { element ->
        val rating = element as? JsonObject ?: return@mapNotNull null
        val source = when (val raw = (rating["source"] as? JsonPrimitive)?.contentOrNull) {
            "popcorn", "audience", "tomatoesaudience" -> "audience"
            "myanimelist", "mal" -> "mal"
            "imdb", "trakt", "tmdb", "letterboxd", "tomatoes", "metacritic" -> raw
            else -> return@mapNotNull null
        }
        val maximum = when (source) {
            "imdb", "mal" -> 10.0
            "letterboxd" -> 5.0
            else -> 100.0
        }
        val rawValue = (rating["value"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 } ?: return@mapNotNull null
        val score = (rating["score"] as? JsonPrimitive)?.doubleOrNull
        val value = if (source == "letterboxd" && score != null) score / 20.0 else rawValue
        if (value !in 0.0..maximum) return@mapNotNull null
        source to value
    }.distinctBy { it.first }.toMap()
    return MDBListRatings(
        trakt = values["trakt"],
        imdb = values["imdb"],
        tmdb = values["tmdb"],
        letterboxd = values["letterboxd"],
        tomatoes = values["tomatoes"],
        audience = values["audience"],
        metacritic = values["metacritic"],
        mal = values["mal"],
        tomatoesCertified = values["tomatoes"] != null && "certified-fresh" in keywords,
        audienceCertified = values["audience"] != null && "certified-hot" in keywords
    )
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
