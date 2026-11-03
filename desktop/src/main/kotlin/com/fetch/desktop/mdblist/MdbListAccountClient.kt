package com.fetch.desktop.mdblist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

/**
 * This build's MDBList OAuth client id, as the TV's `MDBLIST_CLIENT_ID`: `mdblistClientId` in
 * `/fetch-desktop.properties`, else the `MDBLIST_CLIENT_ID` environment variable. Blank means the
 * device sign-in is unavailable; an account can still be connected with an API key.
 */
object MdbListCredentials {
    fun clientId(): String = from(
        Properties().apply {
            runCatching { MdbListCredentials::class.java.getResourceAsStream("/fetch-desktop.properties")?.use(::load) }
        },
        System::getenv
    )

    internal fun from(props: Properties, env: (String) -> String?): String =
        props.getProperty("mdblistClientId").usable() ?: env("MDBLIST_CLIENT_ID").usable() ?: ""

    private fun String?.usable(): String? = this?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("$") }
}

// --- Account ---

/**
 * A connected MDBList account: OAuth tokens from the device sign-in (the TV's way), or an API
 * key, which MDBList accepts on the same endpoints.
 */
@Serializable
data class MdbListAccount(
    val userId: Long,
    val userName: String? = null,
    val apiKey: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L
) {
    val usesApiKey: Boolean get() = apiKey != null

    fun auth(): MdbListAuth? = apiKey?.let { MdbListAuth.ApiKey(it) } ?: accessToken?.let { MdbListAuth.Bearer(it) }

    override fun toString(): String = "MdbListAccount(userId=$userId)"
}

/** `mdblist.json` in the profile folder: the account and the lists hidden from the Library. */
@Serializable
data class MdbListAccountFile(
    val account: MdbListAccount? = null,
    /** Keys of lists left out of the Library and Manage lists (TV: MDBList › Library lists). */
    val hiddenLists: Set<String> = emptySet()
)

data class MdbListUser(val id: Long, val name: String?)

/** A device sign-in in progress: the code to enter at [verificationUrl]. */
data class MdbListDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val verificationUrlComplete: String,
    val intervalSec: Int,
    val expiresInSec: Long
) {
    override fun toString(): String = "MdbListDeviceCode()"
}

sealed interface MdbListPoll {
    data object Pending : MdbListPoll
    data object SlowDown : MdbListPoll
    data object Expired : MdbListPoll
    data object Denied : MdbListPoll
    data class Authorized(val accessToken: String, val refreshToken: String, val expiresAt: Long) : MdbListPoll {
        override fun toString(): String = "Authorized()"
    }
}

// --- Library ---

enum class MdbListListKind { WATCHLIST, STATIC, DYNAMIC, EXTERNAL, LIKED }

/** A list in the account; [mediaType] is `movie`, `show` or null for both. */
data class MdbListList(
    val kind: MdbListListKind,
    val id: Long,
    val name: String,
    val private: Boolean = true,
    val description: String? = null,
    val mediaType: String? = null,
    val updatedAt: String? = null
) {
    val key: String
        get() = when (kind) {
            MdbListListKind.WATCHLIST -> WATCHLIST_KEY
            MdbListListKind.STATIC -> "mdblist:list:$id"
            MdbListListKind.DYNAMIC -> "mdblist:dynamic:$id"
            MdbListListKind.EXTERNAL -> "mdblist:external:$id"
            MdbListListKind.LIKED -> "mdblist:liked:$id"
        }

    /** External, dynamic and liked lists are read-only on MDBList. */
    val writable: Boolean get() = kind == MdbListListKind.WATCHLIST || kind == MdbListListKind.STATIC

    val itemsPath: String
        get() = when (kind) {
            MdbListListKind.WATCHLIST -> "/watchlist/items"
            MdbListListKind.EXTERNAL -> "/external/lists/$id/items"
            else -> "/lists/$id/items"
        }

    companion object {
        const val WATCHLIST_KEY = "mdblist:watchlist"
        val WATCHLIST = MdbListList(MdbListListKind.WATCHLIST, 0, "Watchlist")
    }
}

/** A movie or show on a list; [type] is `movie` or `show`. */
data class MdbListItem(
    val type: String,
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
    val trakt: Long? = null,
    val title: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val listedAt: Long = 0L,
    val rank: Int? = null
) {
    /** The id the rest of the app knows the title by: its IMDb id, else `tmdb:<id>`. */
    val contentId: String? get() = imdb ?: tmdb?.let { "tmdb:$it" }

    fun matches(other: MdbListItem): Boolean = type == other.type && (
        (imdb != null && imdb == other.imdb) || (tmdb != null && tmdb == other.tmdb) ||
            (tvdb != null && tvdb == other.tvdb) || (trakt != null && trakt == other.trakt)
        )

    /** The ids MDBList takes to add or remove a title. */
    fun requestIds(): JsonObject = buildJsonObject {
        imdb?.let { put("imdb", it) }
        tmdb?.let { put("tmdb", it) }
        tvdb?.let { put("tvdb", it) }
        trakt?.let { put("trakt", it) }
    }
}

/**
 * MDBList's account endpoints, as the TV's `MdbListAuthRepository`, `MdbListLibraryRemote` and
 * `MdbListLibraryWriter` use them: the OAuth device sign-in, the user, the watchlist and lists,
 * their items, adding and removing titles, and creating, renaming and deleting static lists.
 */
class MdbListAccountClient(private val http: MdbListHttp, private val clientId: String = MdbListCredentials.clientId()) {
    val deviceSignInAvailable: Boolean get() = clientId.isNotBlank()

    // --- OAuth ---

    suspend fun startDeviceAuth(): MdbListDeviceCode {
        val response = http.postForm("/oauth/device-authorization/", mapOf("scope" to "write", "client_id" to clientId))
        if (response.status !in 200..299) throw MdbListException(response.status, response.errorCode())
        val o = response.json().obj()
        val userCode = o.text("user_code") ?: invalid()
        val uri = o.text("verification_uri")?.takeIf(::isMdbListUrl) ?: invalid()
        return MdbListDeviceCode(
            deviceCode = o.text("device_code") ?: invalid(),
            userCode = userCode,
            verificationUrl = uri,
            verificationUrlComplete = o.text("verification_uri_complete")?.takeIf(::isMdbListUrl) ?: "$uri?user_code=$userCode",
            intervalSec = o.number("interval")?.toInt()?.takeIf { it in 1..3600 } ?: 5,
            expiresInSec = o.number("expires_in")?.takeIf { it > 0 } ?: invalid()
        )
    }

    suspend fun pollDeviceAuth(deviceCode: String, now: Long = System.currentTimeMillis()): MdbListPoll {
        val response = http.postForm(
            "/oauth/token/",
            mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to deviceCode,
                "scope" to "write",
                "client_id" to clientId
            )
        )
        if (response.status in 200..299) return tokens(response, null, now)
        return when (response.errorCode()) {
            "authorization_pending" -> MdbListPoll.Pending
            "slow_down" -> MdbListPoll.SlowDown
            "access_denied" -> MdbListPoll.Denied
            "expired_token" -> MdbListPoll.Expired
            else -> throw MdbListException(response.status, response.errorCode())
        }
    }

    /** New tokens for [account]; throws [MdbListException] with status 401 when MDBList refuses the refresh token. */
    suspend fun refresh(account: MdbListAccount, now: Long = System.currentTimeMillis()): MdbListAccount {
        val refreshToken = account.refreshToken ?: throw MdbListException(401, "invalid_grant")
        val response = http.postForm(
            "/oauth/token/",
            mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to clientId)
        )
        if (response.status !in 200..299) {
            val code = response.errorCode()
            throw MdbListException(if (code == "invalid_grant" || code == "invalid_token") 401 else response.status, code)
        }
        val t = tokens(response, refreshToken, now)
        return account.copy(accessToken = t.accessToken, refreshToken = t.refreshToken, expiresAt = t.expiresAt)
    }

    suspend fun revoke(account: MdbListAccount) {
        val token = account.refreshToken ?: return
        runCatching {
            http.postForm("/oauth/revoke_token/", mapOf("client_id" to clientId, "token" to token, "token_type_hint" to "refresh_token"))
        }
    }

    private fun tokens(response: MdbListResponse, previousRefresh: String?, now: Long): MdbListPoll.Authorized {
        val o = response.json().obj()
        if (!o.text("token_type").equals("Bearer", ignoreCase = true)) invalid()
        o.text("scope")?.let { if ("write" !in it.split(Regex("\\s+"))) throw MdbListException(code = "insufficient_scope", message = "MDBList didn't allow Fetch to update your lists") }
        val expiresIn = o.number("expires_in")?.takeIf { it > 0 } ?: invalid()
        return MdbListPoll.Authorized(
            accessToken = o.text("access_token") ?: invalid(),
            refreshToken = o.text("refresh_token") ?: previousRefresh ?: invalid(),
            expiresAt = now + expiresIn * 1000
        )
    }

    // --- User ---

    suspend fun user(auth: MdbListAuth): MdbListUser {
        val o = http.get("/user", auth).json().obj()
        val id = o.number("user_id")?.takeIf { it > 0 } ?: invalid()
        return MdbListUser(id, o.text("username"))
    }

    // --- Lists ---

    /** The account's own static and dynamic lists (`/lists/user`). */
    suspend fun userLists(auth: MdbListAuth, accountId: Long?): List<MdbListList> {
        val rows = http.get("/lists/user", auth, mapOf("unified" to "false", "sort" to "ranked")).json() as? JsonArray ?: invalid()
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            if (accountId != null && row.number("user_id")?.let { it != accountId } == true) return@mapNotNull null
            val dynamic = row.flag("dynamic") == true || row.text("type") == "dynamic"
            if (!dynamic && row.text("type")?.let { it != "static" } == true) return@mapNotNull null
            row.toList(if (dynamic) MdbListListKind.DYNAMIC else MdbListListKind.STATIC)
        }.distinctBy { it.key }
    }

    /** Lists the account mirrors from other services (`/external/lists/user`). */
    suspend fun externalLists(auth: MdbListAuth): List<MdbListList> {
        val rows = http.get("/external/lists/user", auth).json() as? JsonArray ?: invalid()
        return rows.mapNotNull { (it as? JsonObject)?.toList(MdbListListKind.EXTERNAL, descriptionKey = "source") }.distinctBy { it.key }
    }

    /** Other users' lists the account liked (`/lists/liked`), every page. */
    suspend fun likedLists(auth: MdbListAuth, accountId: Long?): List<MdbListList> {
        val lists = mutableListOf<MdbListList>()
        var offset = 0
        repeat(MAX_PAGES) {
            val o = http.get("/lists/liked", auth, mapOf("limit" to "100", "offset" to offset.toString())).json().obj()
            (o["lists"] as? JsonArray ?: invalid()).forEach { element ->
                val row = element as? JsonObject ?: return@forEach
                if (accountId != null && row.number("user_id") == accountId) return@forEach
                row.toList(MdbListListKind.LIKED)?.let(lists::add)
            }
            offset = nextOffset(o) ?: return lists.distinctBy { it.key }
        }
        return lists.distinctBy { it.key }
    }

    /** Every item of [list], following MDBList's cursor or offset pages; in the order its owner saved. */
    suspend fun items(list: MdbListList, auth: MdbListAuth): List<MdbListItem> {
        val initial = mapOf("limit" to "1000", "unified" to "true", "append_to_response" to "poster,description,genres")
        var query = initial
        val items = mutableListOf<MdbListItem>()
        val seen = mutableSetOf<Map<String, String>>()
        repeat(MAX_PAGES) {
            if (!seen.add(query)) return items
            val response = http.get(list.itemsPath, auth, query)
            val root = response.json()
            items += decodeItems(root)
            val o = root as? JsonObject
            val cursor = o?.let { (it["pagination"] as? JsonObject ?: it).text("next_cursor") }
                ?: response.header("X-Next-Cursor")?.takeIf { it.isNotBlank() }
            val offset = o?.let(::nextOffset)
            query = when {
                cursor != null -> initial + ("cursor" to cursor)
                offset != null -> initial + ("offset" to offset.toString())
                else -> return items.distinctBy { "${it.type}:" + (it.contentId ?: "tvdb:${it.tvdb}:trakt:${it.trakt}") }
            }
        }
        return items
    }

    /** Puts [item] on (or takes it off) [list]; throws when MDBList didn't make the change. */
    suspend fun setMember(list: MdbListList, item: MdbListItem, on: Boolean, auth: MdbListAuth) {
        require(list.writable) { "${list.name} can't be changed from Fetch" }
        val ids = item.requestIds()
        require(ids.isNotEmpty()) { "MDBList needs an IMDb or TMDB id" }
        val bucket = if (item.type == "movie") "movies" else "shows"
        val body = buildJsonObject {
            put(bucket, JsonArray(listOf(if (list.kind == MdbListListKind.WATCHLIST) buildJsonObject { put("ids", ids) } else ids)))
        }
        val response = http.post("${list.itemsPath}/${if (on) "add" else "remove"}", body, auth)
        verifyMembership(response.body, bucket, on)
    }

    suspend fun createList(name: String, private: Boolean, auth: MdbListAuth): MdbListList {
        val body = buildJsonObject { put("name", name.trim()); put("private", private) }
        val o = http.post("/lists/user/add", body, auth).json().obj()
        val id = o.number("id")?.takeIf { it > 0 } ?: invalid()
        return MdbListList(MdbListListKind.STATIC, id, o.text("name") ?: name.trim(), private)
    }

    suspend fun updateList(id: Long, name: String, private: Boolean, auth: MdbListAuth) {
        val body = buildJsonObject { put("name", name.trim()); put("private", private) }
        val o = http.put("/lists/$id", body, auth).json().obj()
        if (o.flag("success") == false) invalid()
    }

    suspend fun deleteList(id: Long, auth: MdbListAuth) {
        val response = http.delete("/lists/$id", auth)
        if (response.status != 204 && response.body.isNotBlank()) {
            val o = response.json() as? JsonObject
            if (o?.flag("success") == false) invalid()
        }
    }

    companion object {
        private const val MAX_PAGES = 100

        private fun isMdbListUrl(url: String): Boolean = runCatching {
            val u = java.net.URI(url)
            u.scheme == "https" && u.host == "mdblist.com" && u.userInfo == null
        }.getOrDefault(false)

        /**
         * A page of items: a plain array, an object with `items`, or `movies` and `shows` arrays.
         * Seasons and episodes, and rows without an id, are skipped.
         */
        internal fun decodeItems(root: JsonElement): List<MdbListItem> = when (root) {
            is JsonArray -> root.mapNotNull { (it as? JsonObject)?.let { row -> decodeItem(row, null) } }
            is JsonObject -> {
                val items = root["items"] as? JsonArray
                if (items != null) items.mapNotNull { (it as? JsonObject)?.let { row -> decodeItem(row, null) } }
                else listOf("movies" to "movie", "shows" to "show").flatMap { (bucket, type) ->
                    (root[bucket] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { row -> decodeItem(row, type) } }
                }
            }
            else -> emptyList()
        }

        private fun decodeItem(row: JsonObject, bucket: String?): MdbListItem? {
            val declared = row.text("mediatype", "type")?.lowercase()
            if (declared == "season" || declared == "episode") return null
            val type = declared?.let(::mediaTypeOf) ?: bucket ?: return null
            val ids = row["ids"] as? JsonObject
            val imdb = (ids?.text("imdb", "imdbid") ?: row.text("imdb_id"))?.takeIf { it.matches(Regex("tt\\d+")) }
            val tmdb = (ids?.number("tmdb", "tmdbid") ?: row.number("id"))?.takeIf { it > 0 }
            val item = MdbListItem(
                type = type,
                imdb = imdb,
                tmdb = tmdb,
                tvdb = (ids?.number("tvdb", "tvdbid") ?: row.number("tvdb_id"))?.takeIf { it > 0 },
                trakt = ids?.number("trakt", "traktid")?.takeIf { it > 0 },
                title = row.text("title", "name"),
                year = row.number("release_year", "year")?.toInt(),
                poster = imageUrl(row.text("poster"), "w500"),
                backdrop = imageUrl(row.text("backdrop", "background"), "w1280"),
                description = row.text("description", "overview"),
                genres = (row["genres"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text("name", "slug")
                }.distinct(),
                listedAt = row.text("listed_at", "added_at", "watchlist_at")?.let(::timestamp) ?: 0L,
                rank = row.number("rank")?.toInt()?.takeIf { it >= 0 }
            )
            return item.takeIf { it.contentId != null || it.tvdb != null || it.trakt != null }
        }

        internal fun mediaTypeOf(type: String): String? = when (type.lowercase()) {
            "movie" -> "movie"
            "show", "series", "tv", "anime" -> "show"
            else -> null
        }

        /** MDBList gives TMDB paths for some images; full URLs are kept. */
        internal fun imageUrl(value: String?, size: String): String? = when {
            value == null -> null
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith('/') && !value.startsWith("//") -> "https://image.tmdb.org/t/p/$size$value"
            else -> null
        }

        private val LOCAL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** MDBList's dates: ISO instants, offsets, `yyyy-MM-dd HH:mm:ss` (UTC) or a plain date. */
        internal fun timestamp(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(value.take(19), LOCAL_TIMESTAMP).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .recoverCatching { LocalDate.parse(value.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrNull()

        private fun nextOffset(o: JsonObject): Int? {
            val p = o["pagination"] as? JsonObject ?: o
            val offset = p.number("offset")
            val limit = p.number("limit")
            val total = p.number("total")
            val hasMore = p.flag("has_more") ?: (offset != null && limit != null && total != null && offset + limit < total)
            if (!hasMore || offset == null || limit == null || limit < 1 || offset + limit > Int.MAX_VALUE) return null
            return (offset + limit).toInt()
        }

        /** The TV's check: exactly one title added (or already there), or removed (or not found). */
        internal fun verifyMembership(body: String, bucket: String, adding: Boolean) {
            val o = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: invalid()
            fun count(key: String): Long? = (o[key] as? JsonObject)?.number(bucket) ?: o.number(key)
            val changed = count(if (adding) "added" else "removed") ?: invalid()
            val unchanged = count(if (adding) "existing" else "not_found") ?: 0
            if (changed < 0 || unchanged < 0 || changed + unchanged != 1L || adding && (count("not_found") ?: 0) != 0L) {
                throw MdbListException(code = "not_changed", message = "MDBList didn't take this title")
            }
        }

        private fun JsonObject.toList(kind: MdbListListKind, descriptionKey: String = "description"): MdbListList? {
            val id = (number("id") ?: (this["ids"] as? JsonArray)?.singleOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() })
                ?.takeIf { it > 0 } ?: return null
            val declared = text("mediatype")
            val mediaType = declared?.let(::mediaTypeOf)
            if (declared != null && mediaType == null) return null
            return MdbListList(
                kind = kind,
                id = id,
                name = text("name") ?: return null,
                private = flag("private") ?: false,
                description = text(descriptionKey),
                mediaType = mediaType,
                updatedAt = text("last_updated_at", "updated_at", "updated")
            )
        }
    }
}

// --- JSON helpers, as the TV's MdbListResponseValues ---

internal fun MdbListResponse.json(): JsonElement = runCatching { Json.parseToJsonElement(body) }.getOrElse { invalid() }
internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: invalid()
internal fun JsonObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    (get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
}
internal fun JsonObject.number(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
    (get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.takeIf { d -> d % 1.0 == 0.0 }?.toLong() }
}
internal fun JsonObject.flag(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
internal fun invalid(): Nothing = throw MdbListException(code = "invalid_response", message = "MDBList returned an incomplete response")
