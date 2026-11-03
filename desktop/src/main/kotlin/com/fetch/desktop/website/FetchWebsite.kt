package com.fetch.desktop.website

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AppDirs
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
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
    val signedOutByWebsite: Boolean = false
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

/** A website profile in the slot it fills on this device (`devices/me/profiles`). */
@Serializable
data class WebsiteProfile(val slot: Int, val id: String, val name: String, val hasPin: Boolean = false)

@Serializable
internal data class WebsiteProfiles(val profiles: List<WebsiteProfile> = emptyList())

/** A list on the website: `watchlist`, `collection` or the profile's own (`custom`). */
@Serializable
data class WebsiteList(val id: String, val name: String, val kind: String = "custom")

@Serializable
internal data class WebsiteListsBody(val lists: List<WebsiteList> = emptyList())

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
internal data class WebsiteErrorBody(val error: String? = null, val code: String? = null)

class FetchWebsiteException(message: String, val status: Int) : Exception(message)

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

    /** Whether this computer is still signed in; a revoked token ends the session. */
    suspend fun checkSession(): Boolean = try {
        authedText("devices/me")
        true
    } catch (_: FetchWebsiteSignedOutException) {
        false
    }

    suspend fun profiles(): List<WebsiteProfile> =
        json.decodeFromString(WebsiteProfiles.serializer(), authedText("devices/me/profiles")).profiles

    suspend fun lists(profileId: String): List<WebsiteList> =
        json.decodeFromString(WebsiteListsBody.serializer(), authedText("lists", profileId = profileId)).lists

    /** One of the profile's website lists of [type] (`movie` or `series`), for a collection source. */
    suspend fun listMetas(profileId: String, listId: String, type: String): List<MetaPreview> =
        json.decodeFromString(
            WebsiteMetasBody.serializer(),
            authedText("stremio/list/${encode(listId)}?type=$type&limit=100&profileId=${encode(profileId)}", profileId = profileId)
        ).metas.map { it.toPreview() }

    private suspend fun authedText(path: String, profileId: String? = null): String = io {
        val current = session() ?: throw FetchWebsiteSignedOutException()
        val builder = Request.Builder()
            .url(endpoint(current.serverUrl, path))
            .header("Authorization", "Bearer ${current.deviceToken}")
            .get()
        if (profileId != null) builder.header("x-profile-id", profileId)
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
        throw FetchWebsiteException(parsed?.error ?: "The Fetch website answered ${response.code}", response.code)
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

        /** The API address someone typed, tidied: a scheme added when missing, trailing slashes dropped. */
        fun normalizeServerUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            return url.toString().trimEnd('/')
        }

        internal fun endpoint(serverUrl: String, path: String): String = "${serverUrl.trimEnd('/')}/$path"
    }
}

/** `website.json` in the config folder, readable by its owner only (it holds the device token). */
class WebsiteStore(private val file: File = File(AppDirs.config, "website.json")) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): WebsiteFile = runCatching { json.decodeFromString(WebsiteFile.serializer(), file.readText()) }.getOrDefault(WebsiteFile())

    fun save(value: WebsiteFile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(WebsiteFile.serializer(), value))
        ownerOnly(tmp)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        ownerOnly(file)
    }

    private fun ownerOnly(f: File) {
        try {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
        } catch (_: IOException) {
        }
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
 * the website profile in its slot. Lists made on the website load as collection sources.
 */
class FetchWebsiteState(
    private val store: WebsiteStore = WebsiteStore(),
    http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
) {
    var file: WebsiteFile by mutableStateOf(store.load())
        private set
    var pairing: WebsitePairing by mutableStateOf(WebsitePairing.EnterAddress)
        private set

    val session: WebsiteSession? get() = file.session

    val client = FetchWebsiteClient(http, session = { file.session }, onSignedOut = ::markSignedOutByWebsite)

    private var profiles: List<WebsiteProfile> = emptyList()

    /** Pairs with the website at [address]: shows its code and polls until it's confirmed there. */
    suspend fun pair(address: String, deviceName: String = defaultDeviceName()) {
        val serverUrl = FetchWebsiteClient.normalizeServerUrl(address) ?: run {
            pairing = WebsitePairing.Failed("That doesn’t look like a web address.")
            return
        }
        pairing = WebsitePairing.Starting
        try {
            val start = client.startPairing(serverUrl, deviceName)
            val expiresAt = runCatching { Instant.parse(start.expiresAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 5 * 60_000L)
            pairing = WebsitePairing.ShowingCode(start.code, start.confirmUrl, serverUrl, expiresAt)
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
                        save(WebsiteFile(WebsiteSession(serverUrl, poll.token, name), serverUrl, signedOutByWebsite = false))
                        profiles = emptyList()
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
        profiles = emptyList()
        resetPairing()
    }

    fun dismissSignedOutNotice() = save(file.copy(signedOutByWebsite = false))

    /** Asks the website whether this computer is still signed in (when Settings › Account opens). */
    suspend fun checkSession() {
        if (session != null) runCatching { client.checkSession() }
    }

    private fun markSignedOutByWebsite() {
        save(file.copy(session = null, signedOutByWebsite = true))
        profiles = emptyList()
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
        return try {
            types.flatMap { client.listMetas(profile.id, listId, it) }
        } catch (e: FetchWebsiteException) {
            when (e.status) {
                404 -> error("This list no longer exists on the Fetch website.")
                401, 403 -> error("This website profile is locked. Unlock it on the website to show this list here.")
                else -> throw e
            }
        }
    }

    private fun save(updated: WebsiteFile) {
        file = updated
        runCatching { store.save(updated) }
    }

    companion object {
        /** One website sign-in for the whole app, as on TV. */
        val shared: FetchWebsiteState by lazy { FetchWebsiteState() }

        fun defaultDeviceName(): String {
            val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() && it != "localhost" }
            return listOfNotNull("Fetch Desktop", host).joinToString(" · ")
        }
    }
}
