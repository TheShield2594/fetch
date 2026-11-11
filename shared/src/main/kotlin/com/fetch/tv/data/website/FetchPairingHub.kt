package com.fetch.tv.data.website

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A code from the pairing hub, for the TV to show. */
@Serializable
data class HubPairingStart(
    val code: String,
    val pollSecret: String,
    val expiresAt: String,
    val intervalSec: Int = 3,
    /** The hub's `/link` page with the code filled in, for the QR code. */
    val linkUrl: String? = null
)

sealed interface HubPairingPoll {
    data object Pending : HubPairingPoll
    data object Expired : HubPairingPoll

    /** Someone confirmed the code on their website, which is at [apiUrl] (and [webUrl]). */
    data class Linked(val apiUrl: String, val webUrl: String?) : HubPairingPoll
}

@Serializable
private data class HubPollBody(val status: String, val api: String? = null, val web: String? = null)

/**
 * The pairing hub at watchfetch.tv: how a TV signs in to a Fetch website nobody told it the
 * address of, the way a TV signs in to a hosted service.
 *
 * 1. The TV makes up a handoff secret, sends the hub only its SHA-256, and gets a code.
 * 2. It shows the code and a QR code for the hub's `/link` page. Someone signed in to their
 *    own Fetch website types the code there (or scans the QR code, which takes the phone to
 *    their website with the code filled in) and confirms it.
 * 3. Their website claims the code at the hub, saying where its API is, and stores a
 *    confirmed pairing whose poll secret is the hash the TV sent.
 * 4. The TV's poll here returns that address; the TV then polls the website's own
 *    `/devices/pair/poll` with the handoff secret and gets its device token from it.
 *
 * The hub only ever holds the hash, so the token can't be collected by anyone who reads the
 * hub's traffic or its memory. Shared by the TV and desktop apps; calls block, so run them
 * off the main thread.
 */
class FetchPairingHub(
    private val http: OkHttpClient,
    baseUrl: String = DEFAULT_URL
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Asks the hub for a code. Throws [IOException] when the hub can't be reached or refuses. */
    fun start(deviceName: String, handoffHash: String): HubPairingStart {
        val body = buildJsonObject {
            put("name", deviceName)
            put("handoffHash", handoffHash)
        }.toString()
        val request = Request.Builder()
            .url("$base/hub/pair/start")
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The pairing hub answered ${response.code}")
            return json.decodeFromString(HubPairingStart.serializer(), response.body?.string().orEmpty())
        }
    }

    fun poll(pollSecret: String): HubPairingPoll {
        val request = Request.Builder()
            .url("$base/hub/pair/poll")
            .header("x-pairing-secret", pollSecret)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            return when {
                response.code == 202 -> HubPairingPoll.Pending
                response.code == 404 -> HubPairingPoll.Expired
                !response.isSuccessful -> throw IOException("The pairing hub answered ${response.code}")
                else -> {
                    val parsed = json.decodeFromString(HubPollBody.serializer(), response.body?.string().orEmpty())
                    val api = parsed.api?.let(::httpUrlOrNull)
                    if (parsed.status == "linked" && api != null) {
                        HubPairingPoll.Linked(api, parsed.web?.let(::httpUrlOrNull))
                    } else {
                        HubPairingPoll.Pending
                    }
                }
            }
        }
    }

    companion object {
        /** The public hub. Every Fetch website claims codes here unless its owner turned it off. */
        const val DEFAULT_URL = "https://watchfetch.tv/api"

        /** Where the hub's QR code points when the hub didn't say. */
        const val DEFAULT_LINK_PAGE = "https://watchfetch.tv/link"

        private val JSON = "application/json".toMediaType()
        private val random = SecureRandom()

        /** A fresh handoff secret: 32 random bytes, base64url. */
        fun newHandoffSecret(): String {
            val bytes = ByteArray(32).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** The SHA-256 of [secret] in lowercase hex, which is what the website stores as the poll secret's hash. */
        fun hash(secret: String): String =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** The link page for [code], for when the hub's answer carried none. */
        fun linkUrl(code: String): String = "$DEFAULT_LINK_PAGE?code=${java.net.URLEncoder.encode(code, "UTF-8")}"

        /** [raw] when it is an http(s) URL, without a trailing slash. */
        internal fun httpUrlOrNull(raw: String): String? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                return null
            }
            return FetchServerAddress.normalize(trimmed)
        }
    }
}
