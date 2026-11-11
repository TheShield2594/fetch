package com.fetch.desktop.website

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.PrivateFiles
import com.fetch.desktop.parental.PinGate
import com.fetch.tv.data.website.FetchServerAddress
import com.fetch.tv.data.website.ResolvedFetchServer
import com.fetch.tv.data.website.WebsiteList
import com.fetch.tv.data.website.WebsiteListItem
import com.fetch.tv.data.website.WebsitePlayback
import com.fetch.tv.data.website.WebsiteWatchState
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** This computer signed in to a Fetch website: where its API is and the token it was issued. */
@Serializable
data class WebsiteSession(val serverUrl: String, val deviceToken: String, val deviceName: String) {
    override fun toString(): String = "WebsiteSession(serverUrl=$serverUrl, deviceName=$deviceName)"
}

/** What `website.json` keeps: the session, the last address used, and whether the website signed us out. */
@Serializable
data class WebsiteFile(
    val session: WebsiteSession? = null,
    val lastServerUrl: String? = null,
    val signedOutByWebsite: Boolean = false,
    /**
     * Set by pairing until the first sync has added this computer's profiles to the website,
     * so pairing adds them to the account instead of the pull replacing them. Once per pairing,
     * so a profile deleted on the website later isn't brought back.
     */
    val initialProfilePush: Boolean = false
)

/** The website's answer to `devices/pair/start`. */
@Serializable
data class WebsitePairingStart(
    val code: String,
    val pollSecret: String,
    val expiresAt: String,
    val intervalSec: Int = 3,
    /** The website's confirm page with the code filled in; null when it doesn't know its own address. */
    val confirmUrl: String? = null
)

@Serializable
internal data class WebsiteDevice(val id: String = "", val name: String = "")

@Serializable
internal data class WebsitePairingPollBody(val status: String = "", val token: String? = null, val device: WebsiteDevice? = null)

sealed interface WebsitePairingPoll {
    data object Pending : WebsitePairingPoll
    data object Expired : WebsitePairingPoll
    data class Approved(val token: String, val deviceName: String) : WebsitePairingPoll
}

/** A website profile's parental settings, which are set on the website while this computer is paired. */
@Serializable
data class WebsiteParental(
    val isKidsProfile: Boolean = false,
    val maxRating: String? = null,
    val allowUnrated: Boolean = true
)

/** A website profile in the slot it fills on this device (`devices/me/profiles`). */
@Serializable
data class WebsiteProfile(
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
internal data class WebsiteProfiles(val profiles: List<WebsiteProfile> = emptyList())

@Serializable
internal data class WebsiteListsBody(val lists: List<WebsiteList> = emptyList())

@Serializable
internal data class WebsiteCreatedListBody(val list: WebsiteList)

@Serializable
internal data class WebsiteListItemsBody(val items: List<WebsiteListItem> = emptyList(), val nextCursor: String? = null)

@Serializable
internal data class WebsitePlaybackBody(val playback: List<WebsitePlayback> = emptyList())

/** One title of a website list, in the Stremio meta-preview shape the API answers with. */
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
internal data class WebsiteErrorBody(val error: String? = null, val code: String? = null, val retryAfterSec: Int? = null)

@Serializable
internal data class WebsiteVerifyBody(val profileToken: String? = null)

@Serializable
internal data class WebsiteSavedProfile(val profile: WebsiteProfile)

@Serializable
internal data class WebsiteCollectionsBody(val collections: JsonArray = JsonArray(emptyList()), val updatedAt: String? = null)

/** An addon in a profile's list on the website, as much of it as the apps read. */
@Serializable
data class WebsiteAddon(val manifestUrl: String, val enabled: Boolean = true, val name: String? = null)

@Serializable
data class WebsiteAddonsBody(
    val addons: List<WebsiteAddon> = emptyList(),
    /** Null when nobody has ever set this profile's addons on the website. */
    val updatedAt: String? = null
)

/** One Home row on the website. [source] is only read, never sent back. */
@Serializable
data class WebsiteHomeRow(val key: String, val hidden: Boolean = false, val title: String? = null, val source: String? = null)

/** A profile's Home layout; [version] 0 means the website was never given one. */
@Serializable
data class WebsiteHomeLayout(val rows: List<WebsiteHomeRow> = emptyList(), val version: Int = 0)

/** The TV's settings blob as the website keeps it, with its version for `If-Match`. */
@Serializable
data class WebsiteTvSettings(val settings: JsonObject = JsonObject(emptyMap()), val version: Int = 0)

/** The website answered with an error; [code] is its machine-readable reason when it gave one. */
class FetchWebsiteException(
    message: String,
    val status: Int,
    val code: String? = null,
    /** For a lockout (429): how long until the website takes another PIN. */
    val retryAfterSec: Int? = null
) : Exception(message) {
    /** A PIN-protected profile this computer hasn't unlocked, or whose profile token ran out. */
    val isProfileLocked: Boolean get() = code == PROFILE_LOCKED

    companion object {
        const val PROFILE_LOCKED = "profile_verification_required"
    }
}

/** A list of a PIN-protected website profile, asked for before its PIN was entered here. */
class WebsiteProfileLockedException(val slot: Int, val profileName: String) :
    Exception("$profileName is locked on the Fetch website. Enter its PIN to show this list.")

/** The website no longer takes this computer's token: it was revoked there. */
class FetchWebsiteSignedOutException : Exception("This computer was signed out from the Fetch website")

/**
 * A Fetch website's API, as the TV's `FetchWebsiteClient` calls it: pairing by a code confirmed
 * on the website, then the device token as a bearer. A bearer 401 on any call means the website
 * revoked this computer, and [onSignedOut] ends the session.
 */
class FetchWebsiteClient(
    private val http: OkHttpClient,
    private val session: () -> WebsiteSession?,
    private val onSignedOut: () -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun startPairing(serverUrl: String, deviceName: String): WebsitePairingStart = io {
        val body = buildJsonObject { put("name", deviceName) }.toString()
        val request = Request.Builder().url(endpoint(serverUrl, "devices/pair/start")).post(body.toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            ensureOk(response, session = false)
            json.decodeFromString(WebsitePairingStart.serializer(), response.body?.string().orEmpty())
        }
    }

    suspend fun pollPairing(serverUrl: String, pollSecret: String): WebsitePairingPoll = io {
        val request = Request.Builder().url(endpoint(serverUrl, "devices/pair/poll")).header("x-pairing-secret", pollSecret).get().build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                202 -> WebsitePairingPoll.Pending
                404 -> WebsitePairingPoll.Expired
                else -> {
                    ensureOk(response, session = false)
                    val body = json.decodeFromString(WebsitePairingPollBody.serializer(), response.body?.string().orEmpty())
                    val token = body.token
                    if (body.status == "approved" && token != null) WebsitePairingPoll.Approved(token, body.device?.name.orEmpty())
                    else WebsitePairingPoll.Pending
                }
            }
        }
    }

    /**
     * The API behind whatever someone typed: the API's own address, or the address they open
     * the website at (see [FetchServerAddress]). Null when nothing there answers as a Fetch API.
     */
    suspend fun resolveServer(address: String): ResolvedFetchServer? = io {
        val probe = http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        FetchServerAddress.resolve(address) { url ->
            runCatching {
                probe.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrNull()
        }
    }

    /** Whether this computer is still signed in; a revoked token ends the session. */
    suspend fun checkSession(): Boolean = try {
        authedText("devices/me")
        true
    } catch (_: FetchWebsiteSignedOutException) {
        false
    }

    suspend fun profiles(): List<WebsiteProfile> =
        json.decodeFromString(WebsiteProfiles.serializer(), authedText("devices/me/profiles")).profiles

    /** Creates or updates the website profile in [slot] (`PUT devices/me/profiles/:slot`). Never a PIN. */
    suspend fun putProfile(slot: Int, body: JsonObject): WebsiteProfile =
        json.decodeFromString(WebsiteSavedProfile.serializer(), authedText("devices/me/profiles/$slot", "PUT", body.toString())).profile

    suspend fun deleteProfile(profileId: String, profileToken: String?) {
        authedText("profiles/$profileId", "DELETE", profileId = profileId, profileToken = profileToken)
    }

    /** Checks a PIN with the website: the profile token on success, null for a wrong PIN. */
    suspend fun verifyPin(profileId: String, pin: String): String? = try {
        val body = buildJsonObject { put("pin", pin) }.toString()
        json.decodeFromString(WebsiteVerifyBody.serializer(), authedText("profiles/$profileId/verify", "POST", body)).profileToken
    } catch (e: FetchWebsiteException) {
        if (e.status == 401) null else throw e
    }

    /** Sets (or with null, removes) a profile's PIN; a 401 means the website wants [currentPin]. */
    suspend fun setPin(profileId: String, pin: String?, currentPin: String?, profileToken: String?) {
        val body = buildJsonObject {
            if (pin == null) put("pin", JsonNull) else put("pin", pin)
            if (!currentPin.isNullOrBlank()) put("currentPin", currentPin)
        }.toString()
        authedText("profiles/$profileId", "PATCH", body, profileId = profileId, profileToken = profileToken)
    }

    suspend fun lists(profileId: String, profileToken: String? = null): List<WebsiteList> =
        json.decodeFromString(WebsiteListsBody.serializer(), authedText("lists", profileId = profileId, profileToken = profileToken)).lists

    /** One of the profile's website lists of [type] (`movie` or `series`), for a collection source. */
    suspend fun listMetas(profileId: String, listId: String, type: String, profileToken: String? = null): List<MetaPreview> =
        json.decodeFromString(
            WebsiteMetasBody.serializer(),
            authedText(
                "stremio/list/${encode(listId)}?type=$type&limit=100&profileId=${encode(profileId)}",
                profileId = profileId,
                profileToken = profileToken
            )
        ).metas.map { it.toPreview() }

    /** The profile's collections in the TV's format, and when they were last saved (null: never). */
    suspend fun collections(profileId: String, profileToken: String?): Pair<JsonArray, String?> {
        val body = json.decodeFromString(
            WebsiteCollectionsBody.serializer(),
            authedText("collections", profileId = profileId, profileToken = profileToken)
        )
        return body.collections to body.updatedAt
    }

    suspend fun putCollections(profileId: String, profileToken: String?, collections: JsonArray) {
        val body = buildJsonObject { put("collections", collections) }.toString()
        authedText("collections", "PUT", body, profileId = profileId, profileToken = profileToken)
    }

    // --- What the apps run, per profile (the TV's FetchWebsiteClient) ---

    suspend fun addons(profileId: String, profileToken: String?): WebsiteAddonsBody =
        json.decodeFromString(WebsiteAddonsBody.serializer(), authedText("profiles/$profileId/addons", profileToken = profileToken))

    /** Replaces the profile's whole addon list, in order. */
    suspend fun putAddons(profileId: String, profileToken: String?, addons: List<WebsiteAddon>) {
        val body = buildJsonObject {
            put("addons", json.encodeToJsonElement(ListSerializer(WebsiteAddon.serializer()), addons))
        }.toString()
        authedText("profiles/$profileId/addons", "PUT", body, profileToken = profileToken)
    }

    suspend fun homeLayout(profileId: String, profileToken: String?): WebsiteHomeLayout =
        json.decodeFromString(WebsiteHomeLayout.serializer(), authedText("profiles/$profileId/home-layout", profileToken = profileToken))

    /** Saves the layout as the next version after [version]; a 412 means it changed since. */
    suspend fun putHomeLayout(profileId: String, profileToken: String?, rows: List<WebsiteHomeRow>, version: Int): WebsiteHomeLayout {
        val body = buildJsonObject {
            put("rows", json.encodeToJsonElement(ListSerializer(WebsiteHomeRow.serializer()), rows))
        }.toString()
        return json.decodeFromString(
            WebsiteHomeLayout.serializer(),
            authedText("profiles/$profileId/home-layout", "PUT", body, profileToken = profileToken, ifMatch = version)
        )
    }

    suspend fun tvSettings(profileId: String, profileToken: String?): WebsiteTvSettings =
        json.decodeFromString(WebsiteTvSettings.serializer(), authedText("profiles/$profileId/settings/tv", profileToken = profileToken))

    /** Saves the settings blob as the next version after [version]; a 412 means it changed since. */
    suspend fun putTvSettings(profileId: String, profileToken: String?, settings: JsonObject, version: Int): WebsiteTvSettings {
        val body = buildJsonObject { put("settings", settings) }.toString()
        return json.decodeFromString(
            WebsiteTvSettings.serializer(),
            authedText("profiles/$profileId/settings/tv", "PUT", body, profileToken = profileToken, ifMatch = version)
        )
    }

    /** Tells the website which build this is and how the last sync went, for its overview. */
    suspend fun reportSync(appVersion: String, flavor: String, ok: Boolean, error: String?) {
        val body = buildJsonObject {
            put("appVersion", appVersion)
            put("flavor", flavor)
            put("ok", ok)
            if (!ok && !error.isNullOrBlank()) put("error", error.take(MAX_REPORTED_ERROR_LENGTH))
        }.toString()
        authedText("devices/me/report", "PUT", body)
    }

    // --- Watch state and lists (the website as a tracking provider, as the TV calls it) ---
    //
    // Every call names the website profile by `x-profile-id`, as its watch and list routes expect.

    /** The profile's resume points, newest first. */
    suspend fun playback(profileId: String, profileToken: String?): List<WebsitePlayback> =
        json.decodeFromString(WebsitePlaybackBody.serializer(), authedText("playback?limit=200", profileId = profileId, profileToken = profileToken)).playback

    /** Saves where playback is now; a resume point, never a watch. */
    suspend fun putPlayback(profileId: String, profileToken: String?, body: JsonObject) {
        authedText("playback", "PUT", body.toString(), profileId = profileId, profileToken = profileToken)
    }

    /** Drops a resume point; one that is already gone (404) counts as dropped. */
    suspend fun deletePlayback(profileId: String, profileToken: String?, playbackId: String) {
        try {
            authedText("playback/${encode(playbackId)}", "DELETE", profileId = profileId, profileToken = profileToken)
        } catch (e: FetchWebsiteException) {
            if (e.status != 404) throw e
        }
    }

    /** [action] is `start`, `pause` or `stop`; a stop at 80% or more records the watch on the website. */
    suspend fun scrobble(profileId: String, profileToken: String?, action: String, body: JsonObject) {
        authedText("scrobble/$action", "POST", body.toString(), profileId = profileId, profileToken = profileToken)
    }

    suspend fun watchState(profileId: String, profileToken: String?): WebsiteWatchState =
        json.decodeFromString(WebsiteWatchState.serializer(), authedText("watch/state", profileId = profileId, profileToken = profileToken))

    suspend fun recordWatch(profileId: String, profileToken: String?, body: JsonObject) {
        authedText("watch", "POST", body.toString(), profileId = profileId, profileToken = profileToken)
    }

    suspend fun unwatchMovie(profileId: String, profileToken: String?, imdbId: String) {
        authedText("watch/movie/${encode(imdbId)}", "DELETE", profileId = profileId, profileToken = profileToken)
    }

    suspend fun unwatchEpisode(profileId: String, profileToken: String?, imdbId: String, season: Int, episode: Int) {
        authedText("series/${encode(imdbId)}/season/$season/episode/$episode/watch", "DELETE", profileId = profileId, profileToken = profileToken)
    }

    /** Every item of one list, a page at a time, in the website's own order. */
    suspend fun listItems(profileId: String, profileToken: String?, listId: String): List<WebsiteListItem> {
        val items = mutableListOf<WebsiteListItem>()
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val path = "lists/${encode(listId)}/items?limit=$LIST_PAGE_SIZE" + (cursor?.let { "&cursor=${encode(it)}" } ?: "")
            val page = json.decodeFromString(WebsiteListItemsBody.serializer(), authedText(path, profileId = profileId, profileToken = profileToken))
            items += page.items
            cursor = page.nextCursor ?: return items
        }
        return items
    }

    suspend fun createList(profileId: String, profileToken: String?, name: String): WebsiteList {
        val body = buildJsonObject {
            put("name", name)
            put("kind", "custom")
        }.toString()
        return json.decodeFromString(
            WebsiteCreatedListBody.serializer(),
            authedText("lists", "POST", body, profileId = profileId, profileToken = profileToken)
        ).list
    }

    suspend fun renameList(profileId: String, profileToken: String?, listId: String, name: String) {
        val body = buildJsonObject { put("name", name) }.toString()
        authedText("lists/${encode(listId)}", "PATCH", body, profileId = profileId, profileToken = profileToken)
    }

    suspend fun deleteList(profileId: String, profileToken: String?, listId: String) {
        authedText("lists/${encode(listId)}", "DELETE", profileId = profileId, profileToken = profileToken)
    }

    /** Adds a title to a list; one that is already there (409) counts as added. */
    suspend fun addListItem(profileId: String, profileToken: String?, listId: String, type: String, imdbId: String, title: String?) {
        val body = buildJsonObject {
            put("type", type)
            put("imdbId", imdbId)
            if (!title.isNullOrBlank()) put("title", title)
        }.toString()
        try {
            authedText("lists/${encode(listId)}/items", "POST", body, profileId = profileId, profileToken = profileToken)
        } catch (e: FetchWebsiteException) {
            if (e.status != 409) throw e
        }
    }

    /** Removes a title from a list; one that is already gone (404) counts as removed. */
    suspend fun removeListItem(profileId: String, profileToken: String?, listId: String, type: String, imdbId: String) {
        try {
            authedText("lists/${encode(listId)}/items/$type/${encode(imdbId)}", "DELETE", profileId = profileId, profileToken = profileToken)
        } catch (e: FetchWebsiteException) {
            if (e.status != 404) throw e
        }
    }

    private suspend fun authedText(
        path: String,
        method: String = "GET",
        body: String? = null,
        profileId: String? = null,
        profileToken: String? = null,
        ifMatch: Int? = null
    ): String = io {
        val current = session() ?: throw FetchWebsiteSignedOutException()
        val builder = Request.Builder()
            .url(endpoint(current.serverUrl, path))
            .header("Authorization", "Bearer ${current.deviceToken}")
            .method(method, body?.toRequestBody(JSON))
        if (profileId != null) builder.header("x-profile-id", profileId)
        if (!profileToken.isNullOrBlank()) builder.header("x-profile-token", profileToken)
        if (ifMatch != null) builder.header("If-Match", "\"$ifMatch\"")
        http.newCall(builder.build()).execute().use { response ->
            ensureOk(response, session = true)
            response.body?.string().orEmpty()
        }
    }

    /** Throws for anything but a 2xx; a bearer 401 ends the session, as on TV. */
    private fun ensureOk(response: Response, session: Boolean) {
        if (response.isSuccessful) return
        if (session && response.code == 401 && response.header("WWW-Authenticate")?.startsWith("Bearer") == true) {
            onSignedOut()
            throw FetchWebsiteSignedOutException()
        }
        val parsed = runCatching { json.decodeFromString(WebsiteErrorBody.serializer(), response.body?.string().orEmpty()) }.getOrNull()
        throw FetchWebsiteException(
            parsed?.error ?: "The Fetch website answered ${response.code}",
            response.code,
            parsed?.code,
            parsed?.retryAfterSec
        )
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun WebsiteMeta.toPreview(): MetaPreview {
        val rawType = if (type == "series") "series" else "movie"
        return MetaPreview(
            id = id,
            type = ContentType.fromString(rawType),
            rawType = rawType,
            name = name,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = description,
            releaseInfo = year?.toString(),
            imdbRating = rating?.toFloat(),
            genres = genres,
            imdbId = id.takeIf { it.startsWith("tt") }
        )
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** The website keeps 500 characters of a sync error; this only bounds the request. */
        private const val MAX_REPORTED_ERROR_LENGTH = 2_000

        /** The website's largest list page; a list longer than [MAX_LIST_PAGES] of them is cut off, as on TV. */
        private const val LIST_PAGE_SIZE = 500
        private const val MAX_LIST_PAGES = 20

        /**
         * An address someone typed, tidied: a scheme added when missing (`http://` for a host
         * on the local network, `https://` for any other, as on TV), trailing slashes dropped.
         */
        fun normalizeServerUrl(raw: String): String? = FetchServerAddress.normalize(raw)

        internal fun endpoint(serverUrl: String, path: String): String = "${serverUrl.trimEnd('/')}/$path"
    }
}

/** `website.json` in the config folder, readable by its owner only (it holds the device token). */
class WebsiteStore(private val file: File = File(AppDirs.config, "website.json")) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): WebsiteFile = runCatching { json.decodeFromString(WebsiteFile.serializer(), file.readText()) }.getOrDefault(WebsiteFile())

    fun save(value: WebsiteFile) {
        // Owner-only (600), written then renamed so a crash never leaves half a file.
        PrivateFiles.writeText(file, json.encodeToString(WebsiteFile.serializer(), value))
    }
}

/** Where pairing with a website has got to (the TV's `WebsitePairingState`). */
sealed interface WebsitePairing {
    data object EnterAddress : WebsitePairing
    data object Starting : WebsitePairing
    /** Showing the code (and a QR code of [confirmUrl]) until someone confirms it on the website. */
    data class ShowingCode(val code: String, val confirmUrl: String?, val serverUrl: String, val expiresAtMs: Long) : WebsitePairing
    data object Expired : WebsitePairing
    data class Paired(val deviceName: String) : WebsitePairing
    data class Failed(val message: String?) : WebsitePairing
}

/**
 * Settings › Account › Fetch website, as on TV: pairs this computer with a Fetch website by a
 * code confirmed there, and signs it out again. One sign-in for the whole app; each profile reads
 * the website profile in its slot. While paired, profiles, addons, settings, Home layout and
 * collections sync with it ([sync]), and lists made on the website load as collection sources;
 * a PIN-protected profile's need the profile token its PIN brings ([verifyPin]).
 */
class FetchWebsiteState(
    private val store: WebsiteStore = WebsiteStore(),
    // No following an https:// redirect to http://: the pairing secret and token would go along in the clear.
    http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followSslRedirects(false).build(),
    private val now: () -> Long = System::currentTimeMillis
) {
    var file: WebsiteFile by mutableStateOf(store.load())
        private set
    var pairing: WebsitePairing by mutableStateOf(WebsitePairing.EnterAddress)
        private set

    val session: WebsiteSession? get() = file.session

    /** Signed in to a website: profiles, addons, settings, Home layout and collections sync with it, as on TV. */
    val isPaired: Boolean get() = file.session != null

    val client = FetchWebsiteClient(http, session = { file.session }, onSignedOut = ::markSignedOutByWebsite)

    /** Profiles, addons, settings, Home layout and collections with the website; see [FetchWebsiteSync]. */
    val sync: FetchWebsiteSync by lazy { FetchWebsiteSync(this) }

    @Volatile
    internal var profiles: List<WebsiteProfile> = emptyList()

    /** Profile tokens by website profile id, with when each stops being trusted here. */
    private val profileTokens = ConcurrentHashMap<String, Pair<String, Long>>()

    // Pairing outlives the Settings page it starts on: leaving the page mid-code must not cancel it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pairingJob: Job? = null

    /**
     * Starts [pair] in the background, replacing one already running; [onPaired] runs once the
     * code is confirmed.
     */
    fun startPairing(address: String, onPaired: () -> Unit = {}) {
        pairingJob?.cancel()
        pairingJob = scope.launch {
            pair(address)
            if (isPaired) onPaired()
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        resetPairing()
    }

    /**
     * Pairs with the website at [address] (the address it opens at in a browser, or its API's):
     * shows its code and polls until it's confirmed there.
     */
    suspend fun pair(address: String, deviceName: String = defaultDeviceName()) {
        if (FetchWebsiteClient.normalizeServerUrl(address) == null) {
            pairing = WebsitePairing.Failed("That doesn’t look like a web address.")
            return
        }
        pairing = WebsitePairing.Starting
        try {
            val server = client.resolveServer(address) ?: run {
                pairing = WebsitePairing.Failed("No Fetch website answered at that address. Check it opens in a browser.")
                return
            }
            val serverUrl = server.apiUrl
            val start = client.startPairing(serverUrl, deviceName)
            val expiresAt = runCatching { Instant.parse(start.expiresAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 5 * 60_000L)
            // The website address that just worked beats the server's own idea of it, which
            // compose leaves at an example IP until someone sets FETCH_WEB_PUBLIC.
            val confirmUrl = server.webUrl?.let { FetchServerAddress.confirmUrl(it, start.code) } ?: start.confirmUrl
            pairing = WebsitePairing.ShowingCode(start.code, confirmUrl, serverUrl, expiresAt)
            val interval = start.intervalSec.coerceIn(1, 30) * 1000L
            while (true) {
                delay(interval)
                when (val poll = client.pollPairing(serverUrl, start.pollSecret)) {
                    WebsitePairingPoll.Pending -> if (System.currentTimeMillis() > expiresAt) {
                        pairing = WebsitePairing.Expired
                        return
                    }
                    WebsitePairingPoll.Expired -> {
                        pairing = WebsitePairing.Expired
                        return
                    }
                    is WebsitePairingPoll.Approved -> {
                        val name = poll.deviceName.ifBlank { deviceName }
                        save(WebsiteFile(WebsiteSession(serverUrl, poll.token, name), serverUrl, signedOutByWebsite = false, initialProfilePush = true))
                        forgetProfiles()
                        pairing = WebsitePairing.Paired(name)
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            if (pairing !is WebsitePairing.Paired) pairing = WebsitePairing.EnterAddress
            throw e
        } catch (e: Exception) {
            pairing = WebsitePairing.Failed(e.message)
        }
    }

    fun resetPairing() {
        pairing = WebsitePairing.EnterAddress
    }

    /** Signing out here: forget the token, keep the address for next time. */
    fun signOut() {
        save(file.copy(session = null, signedOutByWebsite = false))
        forgetProfiles()
        resetPairing()
    }

    fun dismissSignedOutNotice() = save(file.copy(signedOutByWebsite = false))

    fun markInitialProfilePushDone() {
        if (file.initialProfilePush) save(file.copy(initialProfilePush = false))
    }

    /** Asks the website whether this computer is still signed in (when Settings › Account opens). */
    suspend fun checkSession() {
        if (session != null) runCatching { client.checkSession() }
    }

    private fun markSignedOutByWebsite() {
        save(file.copy(session = null, signedOutByWebsite = true))
        forgetProfiles()
    }

    private fun forgetProfiles() {
        profiles = emptyList()
        profileTokens.clear()
    }

    /** The website profile in [slot] (this computer's profile id), or null when not signed in. */
    suspend fun profileFor(slot: Int): WebsiteProfile? {
        if (session == null) return null
        profiles.firstOrNull { it.slot == slot }?.let { return it }
        profiles = runCatching { client.profiles() }.getOrDefault(profiles)
        return profiles.firstOrNull { it.slot == slot }
    }

    /** The titles of a website list, for a collection source of profile [slot] ([types]: `movie`, `series`). */
    suspend fun listItems(slot: Int, listId: String, types: List<String>): List<MetaPreview> {
        val profile = profileFor(slot) ?: error("Sign in to the Fetch website in Settings › Account to show this list.")
        val token = profileToken(profile.id)
        return try {
            types.flatMap { client.listMetas(profile.id, listId, it, token) }
        } catch (e: FetchWebsiteException) {
            when {
                e.status == 404 -> error("This list no longer exists on the Fetch website.")
                e.isProfileLocked || e.status == 401 || e.status == 403 -> {
                    // A token that stopped working is no use for the next try either.
                    profileTokens.remove(profile.id)
                    throw WebsiteProfileLockedException(slot, profile.name)
                }
                else -> throw e
            }
        }
    }

    // --- PINs ---
    //
    // The website never hands out a PIN or its hash: this computer sends the PIN it was given and
    // gets back yes or no, plus a profile token that opens that profile's data for a few hours.

    /** The profile token for website profile [profileId], while it is still trusted. */
    fun profileToken(profileId: String): String? {
        val (token, expiresAt) = profileTokens[profileId] ?: return null
        return token.takeIf { now() < expiresAt }
    }

    fun rememberProfileToken(profileId: String, token: String) {
        profileTokens[profileId] = token to now() + PROFILE_TOKEN_TTL_MS
    }

    /**
     * Checks [pin] for the website profile in [slot] and keeps the profile token it brings. Null
     * when there is no website to ask, or its profile in that slot has no PIN. A lockout after too many wrong PINs is an answer, not an outage.
     */
    suspend fun verifyPin(slot: Int, pin: String): PinGate.Result? {
        val profile = profileFor(slot)?.takeIf { it.hasPin } ?: return null
        val token = try {
            client.verifyPin(profile.id, pin)
        } catch (e: FetchWebsiteException) {
            if (e.status == 429) return PinGate.Result.Locked(e.retryAfterSec ?: 60)
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (token == null) return PinGate.Result.Wrong("Incorrect PIN")
        rememberProfileToken(profile.id, token)
        return PinGate.Result.Ok
    }

    /**
     * An unlock this computer's own copy of the PIN answered still needs the website's profile
     * token, as on TV. Best effort: an unreachable website never slows an unlock down.
     */
    suspend fun onVerifiedLocally(slot: Int, pin: String) {
        runCatching {
            val profile = profileFor(slot)?.takeIf { it.hasPin } ?: return
            if (profileToken(profile.id) != null) return
            client.verifyPin(profile.id, pin)?.let { rememberProfileToken(profile.id, it) }
        }
    }

    private fun save(updated: WebsiteFile) {
        file = updated
        runCatching { store.save(updated) }
    }

    companion object {
        /** How long a profile token from the website is trusted here, as on TV; the website issues them for 12 hours. */
        const val PROFILE_TOKEN_TTL_MS = 11L * 60 * 60 * 1000

        /** One website sign-in for the whole app, as on TV. */
        val shared: FetchWebsiteState by lazy { FetchWebsiteState() }

        fun defaultDeviceName(): String {
            val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() && it != "localhost" }
            return listOfNotNull("Fetch Desktop", host).joinToString(" · ")
        }
    }
}
