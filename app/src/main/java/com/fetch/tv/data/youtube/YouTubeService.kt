package com.fetch.tv.data.youtube

import android.util.Log
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.data.local.YouTubeAuthDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YouTubeService"

private val VISITOR_DATA_REGEX = Regex(""""visitorData":"(.*?)"""")
private val BASE_JS_REGEXES = listOf(
    Regex("""id="base-js" src="(.*?)""""),
    Regex("""\.src = '(.*?m=base)'"""),
    Regex("""\.src = '(.*?)'; .\.id = 'base-js'""")
)
private val CLIENT_REGEX =
    Regex("""clientId:"([-\w]+\.apps\.googleusercontent\.com)",\n?[$\w]+:"(\w+)"""")

/**
 * Signs in to YouTube the way YouTube's own TV app does, and reads the signed-in feeds.
 *
 * Sign-in is Google's device flow for TVs: the TV shows a code, the user enters it at
 * youtube.com/activate on a phone or computer, and the TV polls until it's approved. The feeds
 * come from InnerTube, the API behind YouTube's apps, using the TV client. Playback goes through
 * the trailer extractor, which needs no sign-in.
 */
@Singleton
class YouTubeService @Inject constructor(
    private val authDataStore: YouTubeAuthDataStore
) {
    internal companion object {
        // YouTube's TV app. Read from youtube.com/tv on each run, like SmartTube does, so a rotated
        // client still signs in; these are the fallback when that page can't be read.
        const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
        const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
        const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
        const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
        const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val DEVICE_GRANT_TYPE = "http://oauth.net/grant_type/device/1.0"
        const val ACTIVATION_URL = "https://www.youtube.com/activate"

        const val INNERTUBE_URL = "https://www.youtube.com/youtubei/v1"
        const val TV_CLIENT_NAME = "TVHTML5"
        const val TV_CLIENT_NAME_ID = "7"
        // Kept in step with SmartTube's MediaServiceCore (innertube/utils/Constants.kt).
        const val TV_CLIENT_VERSION = "7.20260901.15.00"
        const val TV_URL = "https://www.youtube.com/tv"
        const val TV_USER_AGENT =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"

        /** Refresh this long before the access token runs out, so a request never carries a stale one. */
        const val TOKEN_REFRESH_MARGIN_MS = 60_000L

        // TV browse ids. "default" is the TV home; FEwhat_to_watch is the older name for it.
        val HOME_BROWSE_IDS = listOf("default", "FEwhat_to_watch")
        const val SUBSCRIPTIONS_BROWSE_ID = "FEsubscriptions"
        const val HISTORY_BROWSE_ID = "FEhistory"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()
    private val refreshMutex = Mutex()
    private val tvAppMutex = Mutex()

    /** What youtube.com/tv tells the TV app at start-up. Read once per run. */
    internal data class TvAppConfig(val clientId: String, val clientSecret: String, val visitorData: String?)

    @Volatile
    private var tvAppConfig: TvAppConfig? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .socketFactory(NoDelaySocketFactory)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // --- Sign-in ---

    suspend fun startDeviceAuth(): Result<YouTubeDeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            val config = tvAppConfig()
            val body = buildJsonObject {
                put("client_id", config.clientId)
                put("device_id", UUID.randomUUID().toString().replace("-", ""))
                put("model_name", "ytlr::")
                put("scope", SCOPE)
            }
            val (status, response) = post(DEVICE_CODE_URL, body)
            val code = parseDeviceCode(response)
                ?: error(errorText(response) ?: "YouTube sign-in failed ($status)")
            authDataStore.saveDeviceFlow(code, config.clientId, config.clientSecret)
            code
        }.onFailure { Log.w(TAG, "Could not start YouTube sign-in: ${it.message}") }
    }

    suspend fun pollDeviceToken(): YouTubeTokenPollResult = withContext(Dispatchers.IO) {
        val state = authDataStore.getCurrentState()
        val deviceCode = state.deviceCode ?: return@withContext YouTubeTokenPollResult.Expired
        val result = runCatching {
            val (_, response) = post(
                TOKEN_URL,
                buildJsonObject {
                    put("code", deviceCode)
                    put("client_id", state.clientId ?: CLIENT_ID)
                    put("client_secret", state.clientSecret ?: CLIENT_SECRET)
                    put("grant_type", DEVICE_GRANT_TYPE)
                }
            )
            parseTokenPoll(response)
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            return@withContext YouTubeTokenPollResult.Failed(error.message ?: "Network error")
        }
        when (result) {
            is TokenPoll.Token -> {
                authDataStore.saveToken(result.token)
                authDataStore.clearDeviceFlow()
                val account = runCatching { fetchAccount(result.token.accessToken) }.getOrNull()
                account?.let { authDataStore.saveAccount(it) }
                YouTubeTokenPollResult.Approved(account?.name)
            }
            is TokenPoll.Status -> {
                when (val status = result.status) {
                    is YouTubeTokenPollResult.SlowDown -> authDataStore.updatePollInterval(status.intervalSeconds)
                    YouTubeTokenPollResult.Expired, YouTubeTokenPollResult.Denied -> authDataStore.clearDeviceFlow()
                    else -> Unit
                }
                result.status
            }
        }
    }

    suspend fun cancelDeviceAuth() = authDataStore.clearDeviceFlow()

    /** Signs out of this profile, and tells Google to drop the sign-in. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val token = authDataStore.getCurrentState().refreshToken
        authDataStore.clearAuth()
        if (!token.isNullOrBlank()) {
            runCatching {
                val request = Request.Builder()
                    .url("$REVOKE_URL?token=${java.net.URLEncoder.encode(token, "UTF-8")}")
                    .post(ByteArray(0).toRequestBody())
                    .build()
                httpClient.newCall(request).execute().close()
            }.onFailure { Log.w(TAG, "Could not revoke YouTube sign-in: ${it.message}") }
        }
    }

    // --- Feeds ---

    suspend fun loadFeed(feed: YouTubeFeed, query: String? = null): List<YouTubeShelf> = withContext(Dispatchers.IO) {
        when (feed) {
            YouTubeFeed.HOME -> loadHome()
            YouTubeFeed.SUBSCRIPTIONS -> browse(SUBSCRIPTIONS_BROWSE_ID)
            YouTubeFeed.HISTORY -> browse(HISTORY_BROWSE_ID)
            YouTubeFeed.SEARCH -> {
                val trimmed = query?.trim().orEmpty()
                if (trimmed.isEmpty()) {
                    emptyList()
                } else {
                    YouTubeResponseParser.parseShelves(innertube("search", validAccessToken()) { put("query", trimmed) })
                }
            }
        }
    }

    /** Fetches the account name again, for profiles that signed in while it couldn't be read. */
    suspend fun refreshAccount() = withContext(Dispatchers.IO) {
        runCatching { fetchAccount(validAccessToken()) }
            .getOrNull()
            ?.let { authDataStore.saveAccount(it) }
    }

    /** Tries each home browse id in turn, so a renamed one falls back to the next. */
    private suspend fun loadHome(): List<YouTubeShelf> {
        var lastError: Exception? = null
        for (browseId in HOME_BROWSE_IDS) {
            try {
                val shelves = browse(browseId)
                if (shelves.isNotEmpty()) return shelves
            } catch (e: YouTubeSignedOutException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        return emptyList()
    }

    private suspend fun browse(browseId: String): List<YouTubeShelf> =
        YouTubeResponseParser.parseShelves(innertube("browse", validAccessToken()) { put("browseId", browseId) })

    private suspend fun innertube(
        endpoint: String,
        accessToken: String,
        params: JsonObjectBuilder.() -> Unit
    ): JsonElement {
        val visitorData = runCatching { tvAppConfig().visitorData }.getOrNull()
        // The context SmartTube sends for TV browse requests (common/helpers/AppClient.kt).
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", TV_CLIENT_NAME)
                    put("clientVersion", TV_CLIENT_VERSION)
                    put("clientScreen", "WATCH")
                    put("userAgent", TV_USER_AGENT)
                    putJsonObject("tvAppInfo") {
                        // Keeps Shorts out of the subscriptions feed.
                        put("appQuality", "TV_APP_QUALITY_FULL_ANIMATION")
                        put("zylonLeftNav", true)
                    }
                    put("webpSupport", false)
                    put("animatedWebpSupport", true)
                    put("hl", Locale.getDefault().language.ifBlank { "en" })
                    Locale.getDefault().country.takeIf { it.isNotBlank() }?.let { put("gl", it) }
                    put("utcOffsetMinutes", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
                    visitorData?.let { put("visitorData", it) }
                }
                putJsonObject("user") {
                    put("enableSafetyMode", false)
                    put("lockedSafetyMode", false)
                }
            }
            put("racyCheckOk", true)
            put("contentCheckOk", true)
            params()
        }
        val request = Request.Builder()
            .url("$INNERTUBE_URL/$endpoint?prettyPrint=false")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", TV_USER_AGENT)
            .header("X-YouTube-Client-Name", TV_CLIENT_NAME_ID)
            .header("X-YouTube-Client-Version", TV_CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", TV_URL)
            .apply { visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        val (code, text) = httpClient.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        if (code == 401) {
            // The access token was refused before it expired; the next request refreshes it.
            authDataStore.expireAccessToken()
            throw IllegalStateException("YouTube refused the sign-in (401)")
        }
        if (code !in 200..299) throw IllegalStateException("YouTube $endpoint failed ($code)")
        return json.parseToJsonElement(text)
    }

    private suspend fun fetchAccount(accessToken: String): YouTubeAccount? =
        YouTubeResponseParser.parseAccount(
            innertube("account/accounts_list", accessToken) {
                putJsonObject("accountReadMask") {
                    put("returnOwner", true)
                    put("returnBrandAccounts", true)
                    put("returnPersonaAccounts", false)
                }
            }
        )

    /** A current access token, refreshed first when it's about to expire. */
    private suspend fun validAccessToken(): String = refreshMutex.withLock { refreshIfNeeded() }

    private suspend fun refreshIfNeeded(): String {
        val state = authDataStore.state.first()
        val refreshToken = state.refreshToken ?: throw YouTubeSignedOutException()
        val expiresAt = state.accessTokenExpiresAt ?: 0L
        val access = state.accessToken
        if (!access.isNullOrBlank() && System.currentTimeMillis() < expiresAt - TOKEN_REFRESH_MARGIN_MS) {
            return access
        }
        val (status, response) = post(
            TOKEN_URL,
            buildJsonObject {
                put("refresh_token", refreshToken)
                put("client_id", state.clientId ?: CLIENT_ID)
                put("client_secret", state.clientSecret ?: CLIENT_SECRET)
                put("grant_type", "refresh_token")
            }
        )
        val token = parseToken(response)
        if (token == null) {
            if (errorCode(response) == "invalid_grant") {
                // The user removed Fetch from their Google account, or the sign-in lapsed.
                authDataStore.clearAuth()
                throw YouTubeSignedOutException()
            }
            throw IllegalStateException(errorText(response) ?: "Could not refresh YouTube sign-in ($status)")
        }
        authDataStore.saveToken(token)
        return token.accessToken
    }

    /**
     * Reads the TV app's client id and secret from its script, the way SmartTube does
     * (app/models/AppInfo.java and ClientData.java), and its visitor id. Falls back to the
     * known client when YouTube can't be reached or the page has changed shape.
     */
    private suspend fun tvAppConfig(): TvAppConfig {
        tvAppConfig?.let { return it }
        return tvAppMutex.withLock {
            tvAppConfig ?: runCatching { fetchTvAppConfig() }
                .onFailure { Log.w(TAG, "Could not read youtube.com/tv: ${it.message}") }
                .getOrNull()
                .let { it ?: TvAppConfig(CLIENT_ID, CLIENT_SECRET, null) }
                .also { tvAppConfig = it }
        }
    }

    private fun fetchTvAppConfig(): TvAppConfig {
        val page = get(TV_URL)
        val visitorData = VISITOR_DATA_REGEX.find(page)?.groupValues?.get(1)
        val scriptUrl = BASE_JS_REGEXES.firstNotNullOfOrNull { it.find(page)?.groupValues?.get(1) }
            ?.replace("\\/", "/")
            ?.let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
        val credentials = scriptUrl?.let { url ->
            runCatching { parseClientCredentials(get(url)) }.getOrNull()
                // An older script layout keeps the client in m=main.
                ?: runCatching {
                    parseClientCredentials(get(url.replace("/dg=0/", "/exm=base/ed=1/").replace("/m=base", "/m=main")))
                }.getOrNull()
        }
        return TvAppConfig(
            clientId = credentials?.first ?: CLIENT_ID,
            clientSecret = credentials?.second ?: CLIENT_SECRET,
            visitorData = visitorData
        )
    }

    /** The first client in the script is the TV's; later ones are refused (SmartTube's ClientData note). */
    internal fun parseClientCredentials(script: String): Pair<String, String>? =
        CLIENT_REGEX.find(script)?.let { it.groupValues[1] to it.groupValues[2] }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", TV_USER_AGENT).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("GET $url failed (${response.code})")
            return response.body?.string().orEmpty()
        }
    }

    private fun post(url: String, body: JsonObject): Pair<Int, JsonElement?> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", TV_USER_AGENT)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return response.code to runCatching { json.parseToJsonElement(text) }.getOrNull()
        }
    }

    // --- Response parsing, kept apart from the network so it can be tested ---

    internal sealed interface TokenPoll {
        data class Token(val token: YouTubeToken) : TokenPoll
        data class Status(val status: YouTubeTokenPollResult) : TokenPoll
    }

    internal fun parseDeviceCode(response: JsonElement?): YouTubeDeviceCode? {
        val obj = response as? JsonObject ?: return null
        return YouTubeDeviceCode(
            deviceCode = obj.string("device_code") ?: return null,
            userCode = obj.string("user_code") ?: return null,
            verificationUrl = obj.string("verification_url") ?: obj.string("verification_uri") ?: ACTIVATION_URL,
            expiresInSeconds = obj.int("expires_in") ?: 1800,
            intervalSeconds = obj.int("interval") ?: 5
        )
    }

    internal fun parseTokenPoll(response: JsonElement?): TokenPoll {
        parseToken(response)?.let { return TokenPoll.Token(it) }
        val status = when (errorCode(response)) {
            "authorization_pending" -> YouTubeTokenPollResult.Pending
            "slow_down" -> YouTubeTokenPollResult.SlowDown(((response as? JsonObject)?.int("interval") ?: 5) + 5)
            "expired_token" -> YouTubeTokenPollResult.Expired
            "access_denied" -> YouTubeTokenPollResult.Denied
            else -> YouTubeTokenPollResult.Failed(errorText(response) ?: "Unexpected response from YouTube")
        }
        return TokenPoll.Status(status)
    }

    internal fun parseToken(response: JsonElement?): YouTubeToken? {
        val obj = response as? JsonObject ?: return null
        return YouTubeToken(
            accessToken = obj.string("access_token") ?: return null,
            refreshToken = obj.string("refresh_token"),
            expiresInSeconds = obj.int("expires_in") ?: 3600
        )
    }

    private fun errorCode(response: JsonElement?): String? = (response as? JsonObject)?.string("error")

    private fun errorText(response: JsonElement?): String? {
        val obj = response as? JsonObject ?: return null
        return obj.string("error_description") ?: obj.string("error")
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
