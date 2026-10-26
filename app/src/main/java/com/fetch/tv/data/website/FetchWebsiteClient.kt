package com.fetch.tv.data.website

import com.fetch.tv.core.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The website answered with an error; [code] is its machine-readable reason when it gave one. */
class FetchWebsiteException(message: String, val status: Int, val code: String? = null) : Exception(message)

/** The website no longer accepts this TV's token: it was revoked. */
class FetchWebsiteSignedOutException : Exception("This TV was signed out from the Fetch website")

/**
 * HTTP to a Fetch website's API. Pairing calls take the server address explicitly (there is
 * no session yet); everything else uses the stored session, and a bearer 401 on any of them
 * ends that session, which is how the TV notices it was revoked.
 */
@Singleton
class FetchWebsiteClient internal constructor(
    private val store: FetchWebsiteStore,
    private val http: OkHttpClient
) {
    @Inject
    constructor(store: FetchWebsiteStore) : this(
        store,
        SharedHttpClient.root.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // ─── Pairing ───

    suspend fun startPairing(serverUrl: String, deviceName: String): WebsitePairingStart = call {
        val body = buildJsonObject { put("name", deviceName) }.toString()
        val request = Request.Builder()
            .url(endpoint(serverUrl, "devices/pair/start"))
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            ensureOk(response, session = false)
            json.decodeFromString(WebsitePairingStart.serializer(), response.body?.string().orEmpty())
        }
    }

    suspend fun pollPairing(serverUrl: String, pollSecret: String): WebsitePairingPoll = call {
        val request = Request.Builder()
            .url(endpoint(serverUrl, "devices/pair/poll"))
            .header("x-pairing-secret", pollSecret)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                202 -> WebsitePairingPoll.Pending
                404 -> WebsitePairingPoll.Expired
                else -> {
                    ensureOk(response, session = false)
                    val body = json.decodeFromString(WebsitePairingPollBody.serializer(), response.body?.string().orEmpty())
                    val token = body.token
                    if (body.status == "approved" && token != null) {
                        WebsitePairingPoll.Approved(token, body.device?.name.orEmpty())
                    } else {
                        WebsitePairingPoll.Pending
                    }
                }
            }
        }
    }

    // ─── Signed in ───

    /** Asks the website whether this TV is still signed in. Ends the session if it isn't. */
    suspend fun checkSession(): Boolean = try {
        authed("devices/me").use { true }
    } catch (error: FetchWebsiteSignedOutException) {
        false
    }

    suspend fun profiles(): List<WebsiteSlottedProfile> =
        authed("devices/me/profiles").use { response ->
            json.decodeFromString(WebsiteSlottedProfiles.serializer(), response.body?.string().orEmpty()).profiles
        }

    suspend fun putProfile(slot: Int, body: JsonObject): WebsiteSlottedProfile =
        authed("devices/me/profiles/$slot", method = "PUT", body = body.toString()).use { response ->
            val wrapper = json.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject
            json.decodeFromJsonElement(WebsiteSlottedProfile.serializer(), wrapper.getValue("profile"))
        }

    suspend fun deleteProfile(profileId: String, profileToken: String?) {
        authed("profiles/$profileId", method = "DELETE", profileId = profileId, profileToken = profileToken).close()
    }

    /** Checks a PIN with the website; returns the profile token on success, null on a wrong PIN. */
    suspend fun verifyPin(profileId: String, pin: String): String? = try {
        val body = buildJsonObject { put("pin", pin) }.toString()
        authed("profiles/$profileId/verify", method = "POST", body = body).use { response ->
            json.decodeFromString(WebsiteVerifyBody.serializer(), response.body?.string().orEmpty()).profileToken
        }
    } catch (error: FetchWebsiteException) {
        if (error.status == 401) null else throw error
    }

    suspend fun setPin(profileId: String, pin: String?, currentPin: String?, profileToken: String?) {
        val body = buildJsonObject {
            if (pin == null) put("pin", JsonNull) else put("pin", pin)
            if (!currentPin.isNullOrBlank()) put("currentPin", currentPin)
        }.toString()
        authed(
            "profiles/$profileId",
            method = "PATCH",
            body = body,
            profileId = profileId,
            profileToken = profileToken
        ).close()
    }

    suspend fun collections(profileId: String, profileToken: String?): Pair<JsonArray, String?> =
        authed("collections", profileId = profileId, profileToken = profileToken).use { response ->
            val body = json.decodeFromString(WebsiteCollectionsBody.serializer(), response.body?.string().orEmpty())
            body.collections to body.updatedAt
        }

    suspend fun putCollections(profileId: String, profileToken: String?, collections: JsonArray): String? {
        val body = buildJsonObject { put("collections", collections) }.toString()
        return authed(
            "collections",
            method = "PUT",
            body = body,
            profileId = profileId,
            profileToken = profileToken
        ).use { response ->
            json.decodeFromString(WebsiteCollectionsBody.serializer(), response.body?.string().orEmpty()).updatedAt
        }
    }

    /** A page of one of the profile's website lists, for a collection row. */
    internal suspend fun listMetas(
        profileId: String,
        profileToken: String?,
        listId: String,
        type: String
    ): List<WebsiteMeta> = authed(
        "stremio/list/$listId?type=$type&limit=100&profileId=$profileId",
        profileId = profileId,
        profileToken = profileToken
    ).use { response ->
        json.decodeFromString(WebsiteMetasBody.serializer(), response.body?.string().orEmpty()).metas
    }

    // ─── Plumbing ───

    private suspend fun authed(
        path: String,
        method: String = "GET",
        body: String? = null,
        profileId: String? = null,
        profileToken: String? = null
    ): Response = call {
        val session = store.session.value ?: throw FetchWebsiteSignedOutException()
        val builder = Request.Builder()
            .url(endpoint(session.serverUrl, path))
            .header("Authorization", "Bearer ${session.deviceToken}")
            .method(method, body?.toRequestBody(JSON))
        if (profileId != null) builder.header("x-profile-id", profileId)
        if (!profileToken.isNullOrBlank()) builder.header("x-profile-token", profileToken)
        val response = http.newCall(builder.build()).execute()
        try {
            ensureOk(response, session = true)
        } catch (error: Exception) {
            response.close()
            throw error
        }
        response
    }

    /**
     * Throws for anything but a 2xx. A 401 carrying `WWW-Authenticate: Bearer` is the token
     * itself being refused, as opposed to a profile PIN check, and ends the session.
     */
    private fun ensureOk(response: Response, session: Boolean) {
        if (response.isSuccessful) return
        if (session && response.code == 401 && response.header("WWW-Authenticate")?.startsWith("Bearer") == true) {
            store.markSignedOutByWebsite()
            throw FetchWebsiteSignedOutException()
        }
        val parsed = runCatching {
            json.decodeFromString(WebsiteErrorBody.serializer(), response.body?.string().orEmpty())
        }.getOrNull()
        throw FetchWebsiteException(
            parsed?.error ?: "The Fetch website answered ${response.code}",
            response.code,
            parsed?.code
        )
    }

    private suspend fun <T> call(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private val JSON = "application/json".toMediaType()

        /**
         * The API base a person typed, tidied: a scheme added when missing, trailing slashes
         * dropped. Null when it can't be a URL at all.
         */
        fun normalizeServerUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            return url.toString().trimEnd('/')
        }

        internal fun endpoint(serverUrl: String, path: String): String = "${serverUrl.trimEnd('/')}/$path"
    }
}
