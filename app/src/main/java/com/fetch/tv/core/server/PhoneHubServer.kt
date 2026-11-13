package com.fetch.tv.core.server

import android.content.res.Resources
import androidx.annotation.StringRes
import com.fetch.tv.R
import com.fetch.tv.core.addon.normalizeAddonInstallUrl
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
    val mdbListKeySet: Boolean,
    val trakt: PhoneHubTrackerStatus,
    val simkl: PhoneHubTrackerStatus,
    val mediaServerProviders: List<Pair<String, String>>,
    val mediaServers: List<String>,
    /** Null in builds without an AniList client id, which hides the section. */
    val aniList: PhoneHubAnimeAccountStatus? = null,
    /** Null in builds without a MyAnimeList client id. */
    val mal: PhoneHubAnimeAccountStatus? = null
)

/**
 * An anime list service that signs in on the phone: [authorizeUrl] ends on a page showing a token
 * (AniList) or code (MyAnimeList, on Fetch's callback page) to paste back.
 */
data class PhoneHubAnimeAccountStatus(
    val connected: Boolean,
    val username: String?,
    val authorizeUrl: String
)

/**
 * Where signing the TV in to a Fetch website stands, for the phone's Fetch website page.
 * [state] is `idle`, `starting`, `code`, `expired`, `paired` or `failed`; [signedIn] is the
 * website this TV is signed in to, if any.
 */
data class PhoneHubWebsiteStatus(
    val state: String,
    val code: String? = null,
    val confirmUrl: String? = null,
    val message: String? = null,
    val notFound: Boolean = false,
    val signedIn: String? = null,
    val lastAddress: String? = null
)

/** An installed addon as the phone's Addons page lists it. */
data class PhoneHubAddon(val name: String, val streams: Boolean)

/** A suggestion on the phone's Addons page: one tap sends [url] to the TV, like a pasted link. */
data class PhoneHubAddonSuggestion(val name: String, val description: String, val url: String)

enum class PhoneHubAddonStatus { PENDING, INSTALLED, REJECTED, NEEDS_CONFIGURATION, FAILED }

/**
 * Where an addon sent from the phone has got to. [configureUrl] is the addon's own setup page,
 * set when it has one; with [PhoneHubAddonStatus.NEEDS_CONFIGURATION] it must be used first.
 */
data class PhoneHubAddonProposal(
    val status: PhoneHubAddonStatus,
    val id: String? = null,
    val name: String? = null,
    val configureUrl: String? = null,
    val message: String? = null
)

/** What the phone hub can do on the TV. Implemented by [PhoneHubController]. */
interface PhoneHubActions {
    fun sendRemote(command: RemoteCommand): Boolean
    suspend fun accountStatus(): PhoneHubAccountStatus
    suspend fun saveMdbListKey(apiKey: String): PhoneHubResult
    /** Checks an AniList access token pasted on the phone and signs the profile in with it. */
    suspend fun connectAniList(token: String): PhoneHubResult = PhoneHubResult(false)
    /** Exchanges the code MyAnimeList's sign-in ended with (or the whole callback address) for tokens. */
    suspend fun connectMal(code: String): PhoneHubResult = PhoneHubResult(false)
    suspend fun connectMediaServer(providerId: String, address: String, username: String, password: String): PhoneHubResult
    suspend fun startTrackerSignIn(tracker: String): Result<PhoneHubDeviceCode>
    suspend fun rankingRules(): StreamRankingRules
    suspend fun saveRankingRules(rules: StreamRankingRules)
    /** Profiles that can be moved to another TV, as id to name. */
    suspend fun transferableProfiles(): List<Pair<Int, String>>
    /** The profile as a file for [importProfile] on another TV; fails with a message to show. */
    suspend fun exportProfile(profileId: Int): Result<JsonObject>
    suspend fun importProfile(document: JsonObject): PhoneHubResult
    /**
     * Every profile that can be moved, as one file for [restoreBackup], with their addon links when
     * [includeAddons]; fails with a message to show.
     */
    suspend fun exportBackup(includeAddons: Boolean): Result<JsonObject>
    suspend fun restoreBackup(document: JsonObject): PhoneHubResult
    suspend fun installedAddons(): List<PhoneHubAddon>
    fun addonSuggestions(): List<PhoneHubAddonSuggestion>
    /** Reads the manifest at [url] and asks the TV to confirm installing it. */
    suspend fun proposeAddon(url: String): PhoneHubAddonProposal
    /** The state of a proposal from [proposeAddon], or null once the TV no longer knows it. */
    fun addonProposal(id: String): PhoneHubAddonProposal?
    /**
     * Why settings can't be changed from the phone right now (a restricted profile with the TV
     * locked), or null when they can. Checked before any change through a section page.
     */
    fun settingsChangeBlocked(): String? = null

    /** The Fetch website sign-in, for the phone's Fetch website page. */
    suspend fun websiteStatus(): PhoneHubWebsiteStatus = PhoneHubWebsiteStatus(state = "idle")

    /**
     * Starts signing the TV in to the Fetch website at [address] (the website's or its API's)
     * and returns once there is a code to confirm, or a reason there isn't.
     */
    suspend fun connectWebsite(address: String): PhoneHubWebsiteStatus =
        PhoneHubWebsiteStatus(state = "failed")
}

/**
 * One phone page for everything that needs typing: the landing page links to the remote and
 * keyboard, addons (each confirmed on the TV), account sign-ins, stream ranking rules, and the
 * existing pages for stream badges and custom posters, which are served under their own prefix.
 */
class PhoneHubServer(
    private val actions: PhoneHubActions,
    /** Resources in the TV's language, read per request so a language change applies at once. */
    private val resources: () -> Resources,
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

    private fun text(@StringRes id: Int, vararg args: Any): String = resources().getString(id, *args)

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
            errorResponse(text(R.string.phone_web_error_invalid_request))
        } catch (error: IllegalArgumentException) {
            errorResponse(error.message ?: text(R.string.phone_web_error_invalid_request))
        }
    }

    private fun pages() = PhoneHubWebPage(resources())

    private fun route(session: IHTTPSession, uri: String): Response = when {
        session.method == Method.GET && uri == "/" -> html(pages().landing(sections.keys))
        session.method == Method.GET && uri == "/remote" -> html(pages().remote())
        session.method == Method.GET && uri == "/accounts" -> html(pages().accounts())
        session.method == Method.GET && uri == "/ranking" -> html(pages().ranking())
        session.method == Method.GET && uri == "/profiles" -> html(pages().profiles())
        session.method == Method.GET && uri == "/backup" -> html(pages().backup())
        session.method == Method.GET && uri == "/addons" -> html(pages().addons())
        session.method == Method.GET && uri == "/website" -> html(pages().website())
        session.method == Method.GET && uri == "/api/website" -> jsonResponse(websiteJson(runBlocking { actions.websiteStatus() }))
        session.method == Method.POST && uri == "/api/website" -> handleWebsite(readJson(session))
        session.method == Method.POST && uri == "/api/remote" -> handleRemote(readJson(session))
        session.method == Method.GET && uri == "/api/accounts" -> jsonResponse(accountsJson())
        session.method == Method.POST && uri == "/api/accounts/mdblist" -> handleMdbList(readJson(session))
        session.method == Method.POST && uri == "/api/accounts/anilist" ->
            result(runBlocking { actions.connectAniList(readJson(session).string("token").orEmpty().trim()) })
        session.method == Method.POST && uri == "/api/accounts/mal" ->
            result(runBlocking { actions.connectMal(readJson(session).string("code").orEmpty().trim()) })
        session.method == Method.POST && uri == "/api/accounts/mediaserver" -> handleMediaServer(readJson(session))
        session.method == Method.POST && uri == "/api/accounts/tracker" -> handleTracker(readJson(session))
        session.method == Method.GET && uri == "/api/ranking" -> jsonResponse(rankingJson())
        session.method == Method.POST && uri == "/api/ranking" -> handleRanking(session)
        session.method == Method.GET && uri == "/api/addons" -> jsonResponse(addonsJson())
        session.method == Method.POST && uri == "/api/addons" -> handleAddonProposal(readJson(session))
        session.method == Method.GET && uri == "/api/addons/status" -> handleAddonStatus(session)
        session.method == Method.GET && uri == "/api/profiles" -> jsonResponse(profilesJson())
        session.method == Method.GET && uri == "/api/profiles/export" -> handleProfileExport(session)
        session.method == Method.POST && uri == "/api/profiles/import" ->
            result(runBlocking { actions.importProfile(readJson(session, MAX_PROFILE_BYTES)) })
        session.method == Method.GET && uri == "/api/backup" ->
            handleBackupExport(includeAddons = session.parameters["addons"]?.firstOrNull() == "1")
        session.method == Method.POST && uri == "/api/backup/restore" ->
            result(runBlocking { actions.restoreBackup(readJson(session, MAX_BACKUP_BYTES)) })
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    private fun handleRemote(body: JsonObject): Response {
        val command = when (body.string("type")) {
            "key" -> RemoteCommand.Key(
                RemoteKey.fromName(body.string("key")) ?: throw IllegalArgumentException("Unknown key.")
            )
            "text" -> RemoteCommand.Text(body.requiredText("text"))
            "search" -> RemoteCommand.Search(body.requiredText("query"))
            "settings_search" -> RemoteCommand.SettingsSearch(body.requiredText("query"))
            else -> throw IllegalArgumentException("Unknown command.")
        }
        return if (actions.sendRemote(command)) ok() else errorResponse(text(R.string.phone_web_error_busy))
    }

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
            onFailure = { errorResponse(it.message ?: text(R.string.phone_web_error_sign_in)) }
        )
    }

    private fun handleWebsite(body: JsonObject): Response {
        // Signing the TV in to an account changes what every profile sees, so a locked TV says no.
        actions.settingsChangeBlocked()?.let { return forbidden(it) }
        val address = body.requiredText("address")
        return jsonResponse(websiteJson(runBlocking { actions.connectWebsite(address) }))
    }

    private fun websiteJson(status: PhoneHubWebsiteStatus): JsonElement = buildJsonObject {
        put("state", status.state)
        status.code?.let { put("code", it) }
        status.confirmUrl?.let { put("confirmUrl", it) }
        status.message?.let { put("message", it) }
        put("notFound", status.notFound)
        status.signedIn?.let { put("signedIn", it) }
        status.lastAddress?.takeIf { it.isNotBlank() }?.let { put("lastAddress", it) }
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

    private fun addonsJson(): JsonElement = buildJsonObject {
        val installed = runBlocking { actions.installedAddons() }
        put("summary", addonSummary(installed))
        put("installed", buildJsonArray {
            installed.forEach { addon ->
                add(buildJsonObject {
                    put("name", addon.name)
                    put("streams", addon.streams)
                })
            }
        })
        put("suggestions", buildJsonArray {
            actions.addonSuggestions().forEach { suggestion ->
                add(buildJsonObject {
                    put("name", suggestion.name)
                    put("description", suggestion.description)
                    put("url", suggestion.url)
                })
            }
        })
    }

    /** One line for the "On this TV" list: the names, then how many give streams. */
    private fun addonSummary(installed: List<PhoneHubAddon>): String {
        if (installed.isEmpty()) return text(R.string.phone_web_addons_none)
        val res = resources()
        val names = installed.joinToString(res.getString(R.string.phone_web_list_separator)) { it.name }
        val streams = installed.count { it.streams }
        val streamsLine = if (streams == 0) {
            res.getString(R.string.phone_web_addons_no_streams)
        } else {
            res.getQuantityString(R.plurals.phone_web_addons_streams, streams, streams)
        }
        return res.getString(R.string.phone_web_addons_installed, names, streamsLine)
    }

    private fun handleAddonProposal(body: JsonObject): Response {
        // Configured addon links carry their settings and can run long, so not requiredText's limit.
        val raw = body.string("url")?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Missing url.")
        require(raw.length <= MAX_ADDON_URL_LENGTH) { text(R.string.phone_web_error_link_too_long) }
        val url = normalizeAddonInstallUrl(raw)
            ?: throw IllegalArgumentException(text(R.string.phone_web_error_not_addon_link))
        return proposalResponse(runBlocking { actions.proposeAddon(url) })
    }

    private fun handleAddonStatus(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull() ?: throw IllegalArgumentException("Missing id.")
        val proposal = actions.addonProposal(id)
            ?: PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, id = id, message = text(R.string.phone_web_error_request_gone))
        return proposalResponse(proposal)
    }

    private fun proposalResponse(proposal: PhoneHubAddonProposal): Response = jsonResponse(buildJsonObject {
        put("status", proposal.status.name.lowercase())
        proposal.id?.let { put("id", it) }
        proposal.name?.let { put("name", it) }
        proposal.configureUrl?.let { put("configureUrl", it) }
        proposal.message?.let { put("message", it) }
    })

    private fun profilesJson(): JsonElement = buildJsonObject {
        put("profiles", buildJsonArray {
            runBlocking { actions.transferableProfiles() }.forEach { (id, name) ->
                add(buildJsonObject {
                    put("id", id)
                    put("name", name)
                })
            }
        })
    }

    private fun handleProfileExport(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull()
            ?: throw IllegalArgumentException("Missing profile.")
        val document = runBlocking { actions.exportProfile(id) }
            .getOrElse { return errorResponse(it.message ?: text(R.string.phone_web_error_export)) }
        val bytes = document.toString().toByteArray(StandardCharsets.UTF_8)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Content-Disposition", "attachment; filename=\"fetch-profile-$id.json\"")
        }
    }

    private fun handleBackupExport(includeAddons: Boolean): Response {
        val document = runBlocking { actions.exportBackup(includeAddons) }
            .getOrElse { return errorResponse(it.message ?: text(R.string.phone_web_error_backup)) }
        val bytes = document.toString().toByteArray(StandardCharsets.UTF_8)
        val date = java.time.LocalDate.now().toString()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Content-Disposition", "attachment; filename=\"fetch-backup-$date.json\"")
        }
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
            status.aniList?.let { put("aniList", animeAccountJson(it)) }
            status.mal?.let { put("mal", animeAccountJson(it)) }
        }
    }

    private fun animeAccountJson(account: PhoneHubAnimeAccountStatus) = buildJsonObject {
        put("connected", account.connected)
        account.username?.let { put("username", it) }
        put("authorizeUrl", account.authorizeUrl)
    }

    private fun rankingJson(): JsonElement = buildJsonObject {
        put("rules", json.encodeToJsonElement(StreamRankingRules.serializer(), runBlocking { actions.rankingRules() }))
    }

    private fun sectionFor(uri: String): Pair<String, NanoHTTPD>? =
        sections.entries.firstOrNull { (prefix, _) -> uri == "/$prefix" || uri.startsWith("/$prefix/") }
            ?.let { it.key to it.value }

    /** Serves an existing phone page under `/<prefix>/`, pointing its links at the prefix. */
    private fun serveSection(prefix: String, server: NanoHTTPD, session: IHTTPSession): Response {
        // Section pages write straight to settings (and badge import fetches a URL on the TV), so
        // every change goes through the same parental check as the hub's own pages.
        if (session.method != Method.GET) {
            actions.settingsChangeBlocked()?.let { return forbidden(it) }
        }
        val innerUri = session.uri.removePrefix("/$prefix").ifEmpty { "/" }
        val response = server.serve(PrefixedSession(session, innerUri))
        if (response.mimeType?.startsWith("text/html") != true) return response
        val page = response.data?.use { String(it.readBytes(), StandardCharsets.UTF_8) }.orEmpty()
        return html(rewriteSectionPage(page, prefix, text(R.string.phone_web_back)), response.status)
    }

    private class PrefixedSession(
        private val inner: IHTTPSession,
        private val innerUri: String
    ) : IHTTPSession by inner {
        override fun getUri(): String = innerUri
    }

    private fun readJson(session: IHTTPSession, maxBytes: Int = MAX_BODY_BYTES): JsonObject {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return JsonObject(emptyMap())
        require(length <= maxBytes) { text(R.string.phone_web_error_too_large) }
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
        if (result.ok) ok(result.message) else errorResponse(result.message ?: text(R.string.phone_web_error_save))

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

    private fun forbidden(message: String): Response = newFixedLengthResponse(
        Response.Status.FORBIDDEN,
        "application/json; charset=utf-8",
        buildJsonObject { put("error", message) }.toString()
    )

    private fun errorResponse(message: String): Response = newFixedLengthResponse(
        Response.Status.BAD_REQUEST,
        "application/json; charset=utf-8",
        buildJsonObject { put("error", message) }.toString()
    )

    companion object {
        const val SECTION_BADGES = "badges"
        const val SECTION_POSTERS = "posters"
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_ADDON_URL_LENGTH = 8 * 1024
        // A profile file carries the whole watch history.
        private const val MAX_PROFILE_BYTES = 16 * 1024 * 1024
        // A backup is every profile's file in one.
        private const val MAX_BACKUP_BYTES = 48 * 1024 * 1024

        internal fun rewriteSectionPage(page: String, prefix: String, backLabel: String): String =
            page
                .replace("'/api/", "'/$prefix/api/")
                .replace("\"/api/", "\"/$prefix/api/")
                .replace("\"/logo.png\"", "\"/$prefix/logo.png\"")
                // The label is translated text: escape it for the replacement, where `$` and `\` are special.
                .replaceFirst(
                    Regex("<body([^>]*)>"),
                    "<body\$1>" + Regex.escapeReplacement(PhoneHubWebPage.backLink(backLabel))
                )

        fun startOnAvailablePort(
            actions: PhoneHubActions,
            resources: () -> Resources,
            sections: Map<String, NanoHTTPD>,
            onActivity: () -> Unit,
            startPort: Int = 8095,
            maxAttempts: Int = 10
        ): PhoneHubServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = PhoneHubServer(
                        actions = actions,
                        resources = resources,
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
