package com.fetch.tv.core.server

import com.fetch.tv.core.remote.RemoteCommand
import com.fetch.tv.core.remote.RemoteCommandBus
import com.fetch.tv.core.remote.RemoteKey
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class PhoneHubResult(val ok: Boolean, val message: String? = null)

data class PhoneHubDeviceCode(val userCode: String, val verificationUrl: String)

data class PhoneHubTrackerStatus(
    val connected: Boolean,
    val username: String? = null,
    val pendingCode: PhoneHubDeviceCode? = null
)

data class PhoneHubAccountStatus(
    /** Debrid provider id to whether a key is saved. Keys themselves are never sent. */
    val debridKeys: Map<String, Boolean>,
    val mdbListKeySet: Boolean,
    val trakt: PhoneHubTrackerStatus,
    val simkl: PhoneHubTrackerStatus,
    val mediaServerProviders: List<Pair<String, String>>,
    val mediaServers: List<String>
)

/** What the phone hub can do on the TV. Implemented by [PhoneHubController]. */
interface PhoneHubActions {
    fun sendRemote(command: RemoteCommand): Boolean
    suspend fun accountStatus(): PhoneHubAccountStatus
    suspend fun saveDebridKey(providerId: String, apiKey: String): PhoneHubResult
    suspend fun saveMdbListKey(apiKey: String): PhoneHubResult
    suspend fun connectMediaServer(providerId: String, address: String, username: String, password: String): PhoneHubResult
    suspend fun startTrackerSignIn(tracker: String): Result<PhoneHubDeviceCode>
    suspend fun rankingRules(): StreamRankingRules
    suspend fun saveRankingRules(rules: StreamRankingRules)
}

/**
 * One phone page for everything that needs typing: the landing page links to the remote and
 * keyboard, account sign-ins, stream ranking rules, and the existing pages for debrid name
 * templates, stream badges and custom posters, which are served under their own prefix.
 */
class PhoneHubServer(
    private val actions: PhoneHubActions,
    private val sections: Map<String, NanoHTTPD> = emptyMap(),
    private val pairingGuard: PairingGuard? = null,
    private val onActivity: () -> Unit = {},
    port: Int = 8095
) : NanoHTTPD(port) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun pairingUrl(host: String): String =
        pairingGuard?.pairingUrl(host, listeningPort) ?: "http://$host:$listeningPort"

    val pairedSessionCount: Int get() = pairingGuard?.pairedSessionCount ?: 0

    override fun serve(session: IHTTPSession): Response {
        pairingGuard?.check(session)?.let { return it }
        onActivity()
        val uri = session.uri.orEmpty()
        sectionFor(uri)?.let { (prefix, server) -> return serveSection(prefix, server, session) }
        return try {
            route(session, uri)
        } catch (error: SerializationException) {
            errorResponse("Invalid request.")
        } catch (error: IllegalArgumentException) {
            errorResponse(error.message ?: "Invalid request.")
        }
    }

    private fun route(session: IHTTPSession, uri: String): Response = when {
        session.method == Method.GET && uri == "/" -> html(PhoneHubWebPage.landing(sections.keys))
        session.method == Method.GET && uri == "/remote" -> html(PhoneHubWebPage.remote())
        session.method == Method.GET && uri == "/accounts" -> html(PhoneHubWebPage.accounts())
        session.method == Method.GET && uri == "/ranking" -> html(PhoneHubWebPage.ranking())
        session.method == Method.POST && uri == "/api/remote" -> handleRemote(readJson(session))
        session.method == Method.GET && uri == "/api/accounts" -> jsonResponse(accountsJson())
        session.method == Method.POST && uri == "/api/accounts/debrid" -> handleDebrid(readJson(session))
        session.method == Method.POST && uri == "/api/accounts/mdblist" -> handleMdbList(readJson(session))
        session.method == Method.POST && uri == "/api/accounts/mediaserver" -> handleMediaServer(readJson(session))
        session.method == Method.POST && uri == "/api/accounts/tracker" -> handleTracker(readJson(session))
        session.method == Method.GET && uri == "/api/ranking" -> jsonResponse(rankingJson())
        session.method == Method.POST && uri == "/api/ranking" -> handleRanking(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    private fun handleRemote(body: JsonObject): Response {
        val command = when (body.string("type")) {
            "key" -> RemoteCommand.Key(
                RemoteKey.fromName(body.string("key")) ?: throw IllegalArgumentException("Unknown key.")
            )
            "text" -> RemoteCommand.Text(body.requiredText("text"))
            "search" -> RemoteCommand.Search(body.requiredText("query"))
            else -> throw IllegalArgumentException("Unknown command.")
        }
        return if (actions.sendRemote(command)) ok() else errorResponse("The TV is busy. Try again.")
    }

    private fun handleDebrid(body: JsonObject): Response = result(
        runBlocking { actions.saveDebridKey(body.requiredText("provider"), body.string("apiKey").orEmpty().trim()) }
    )

    private fun handleMdbList(body: JsonObject): Response = result(
        runBlocking { actions.saveMdbListKey(body.string("apiKey").orEmpty().trim()) }
    )

    private fun handleMediaServer(body: JsonObject): Response = result(
        runBlocking {
            actions.connectMediaServer(
                providerId = body.requiredText("provider"),
                address = body.requiredText("address"),
                username = body.string("username").orEmpty(),
                password = body.string("password").orEmpty()
            )
        }
    )

    private fun handleTracker(body: JsonObject): Response {
        val tracker = body.requiredText("tracker")
        val code = runBlocking { actions.startTrackerSignIn(tracker) }
        return code.fold(
            onSuccess = { deviceCode ->
                jsonResponse(buildJsonObject {
                    put("status", "pending")
                    put("userCode", deviceCode.userCode)
                    put("verificationUrl", deviceCode.verificationUrl)
                })
            },
            onFailure = { errorResponse(it.message ?: "Couldn't start sign-in.") }
        )
    }

    private fun handleRanking(session: IHTTPSession): Response {
        val body = readJson(session)
        val rules = json.decodeFromJsonElement(
            StreamRankingRules.serializer(),
            body["rules"] ?: throw IllegalArgumentException("Missing rules.")
        )
        runBlocking { actions.saveRankingRules(rules) }
        return ok()
    }

    private fun accountsJson(): JsonElement {
        val status = runBlocking { actions.accountStatus() }
        fun tracker(value: PhoneHubTrackerStatus) = buildJsonObject {
            put("connected", value.connected)
            value.username?.let { put("username", it) }
            value.pendingCode?.let {
                put("userCode", it.userCode)
                put("verificationUrl", it.verificationUrl)
            }
        }
        return buildJsonObject {
            put("debrid", buildJsonObject { status.debridKeys.forEach { (id, set) -> put(id, set) } })
            put("mdbList", status.mdbListKeySet)
            put("trakt", tracker(status.trakt))
            put("simkl", tracker(status.simkl))
            put("mediaServerProviders", buildJsonArray {
                status.mediaServerProviders.forEach { (id, name) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("name", name)
                    })
                }
            })
            put("mediaServers", buildJsonArray { status.mediaServers.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun rankingJson(): JsonElement = buildJsonObject {
        put("rules", json.encodeToJsonElement(StreamRankingRules.serializer(), runBlocking { actions.rankingRules() }))
    }

    private fun sectionFor(uri: String): Pair<String, NanoHTTPD>? =
        sections.entries.firstOrNull { (prefix, _) -> uri == "/$prefix" || uri.startsWith("/$prefix/") }
            ?.let { it.key to it.value }

    /** Serves an existing phone page under `/<prefix>/`, pointing its links at the prefix. */
    private fun serveSection(prefix: String, server: NanoHTTPD, session: IHTTPSession): Response {
        val innerUri = session.uri.removePrefix("/$prefix").ifEmpty { "/" }
        val response = server.serve(PrefixedSession(session, innerUri))
        if (response.mimeType?.startsWith("text/html") != true) return response
        val page = response.data?.use { String(it.readBytes(), StandardCharsets.UTF_8) }.orEmpty()
        return html(rewriteSectionPage(page, prefix), response.status)
    }

    private class PrefixedSession(
        private val inner: IHTTPSession,
        private val innerUri: String
    ) : IHTTPSession by inner {
        override fun getUri(): String = innerUri
    }

    private fun readJson(session: IHTTPSession): JsonObject {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return JsonObject(emptyMap())
        require(length <= MAX_BODY_BYTES) { "Request too large." }
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return json.parseToJsonElement(String(buffer, 0, offset, StandardCharsets.UTF_8)).jsonObject
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.requiredText(key: String): String {
        val value = string(key)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing $key.")
        require(value.length <= RemoteCommandBus.MAX_TEXT_LENGTH) { "$key is too long." }
        return value
    }

    private fun result(result: PhoneHubResult): Response =
        if (result.ok) ok(result.message) else errorResponse(result.message ?: "Couldn't save.")

    private fun ok(message: String? = null): Response = jsonResponse(buildJsonObject {
        put("status", "ok")
        message?.let { put("message", it) }
    })

    private fun html(body: String, status: Response.IStatus = Response.Status.OK): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Frame-Options", "DENY")
        }

    private fun jsonResponse(body: JsonElement): Response {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        ).apply { addHeader("Cache-Control", "no-store") }
    }

    private fun errorResponse(message: String): Response = newFixedLengthResponse(
        Response.Status.BAD_REQUEST,
        "application/json; charset=utf-8",
        buildJsonObject { put("error", message) }.toString()
    )

    companion object {
        const val SECTION_TEMPLATES = "templates"
        const val SECTION_BADGES = "badges"
        const val SECTION_POSTERS = "posters"
        private const val MAX_BODY_BYTES = 256 * 1024

        internal fun rewriteSectionPage(page: String, prefix: String): String =
            page
                .replace("'/api/", "'/$prefix/api/")
                .replace("\"/api/", "\"/$prefix/api/")
                .replace("\"/logo.png\"", "\"/$prefix/logo.png\"")
                .replaceFirst(Regex("<body([^>]*)>"), "<body$1>${PhoneHubWebPage.BACK_LINK}")

        fun startOnAvailablePort(
            actions: PhoneHubActions,
            sections: Map<String, NanoHTTPD>,
            onActivity: () -> Unit,
            startPort: Int = 8095,
            maxAttempts: Int = 10
        ): PhoneHubServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = PhoneHubServer(
                        actions = actions,
                        sections = sections,
                        pairingGuard = PairingGuard(),
                        onActivity = onActivity,
                        port = port
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: Exception) {
                }
            }
            return null
        }
    }
}
