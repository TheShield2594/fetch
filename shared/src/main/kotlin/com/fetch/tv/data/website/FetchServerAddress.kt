package com.fetch.tv.data.website

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/** Where a Fetch website's API is, and the website itself when it is known. */
data class ResolvedFetchServer(
    /** The API base URL that pairing and sync talk to. */
    val apiUrl: String,
    /** The address the website is opened at in a browser; null when only the API was given. */
    val webUrl: String?
)

/**
 * Turns whatever someone types to find their Fetch website into the API to pair with.
 *
 * People know the address they open the website at, not the API's: the API usually sits on
 * another port (7000 next to the website's 7002), or under `/api` behind a reverse proxy. So
 * any of these work:
 *
 * - the API itself (`http://192.168.1.25:7000`, `https://fetch.example.com/api`);
 * - the website (`fetch.example.com`, `http://192.168.1.25:7002`), from which the API is read
 *   out of `fetch-server.json` (or the older `config.js`) that the website container writes
 *   at start, then tried at `/api` and on port 7000 of the same host.
 *
 * With no scheme typed, a public host is tried over HTTPS first, then HTTP.
 *
 * Shared by the TV and desktop apps; [resolve] takes the HTTP GET as a function so it stays
 * free of any one client's networking.
 */
object FetchServerAddress {
    const val DISCOVERY_PATH = "fetch-server.json"
    private const val LEGACY_CONFIG_PATH = "config.js"
    const val DEFAULT_API_PORT = 7000

    private val json = Json { ignoreUnknownKeys = true }
    private val CONFIG_JS_API = Regex("""__FETCH_API_BASE__\s*=\s*"((?:[^"\\]|\\.)*)"""")

    /**
     * [raw] as a URL with a scheme and no trailing slash, or null when it isn't an address.
     * Without a scheme, a host that can only be on the local network gets `http://`; any
     * other gets `https://`.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.contains("://") -> trimmed
            isLocalNetworkHost(hostOf(trimmed)) -> "http://$trimmed"
            else -> "https://$trimmed"
        }
        // Parsed before the trailing slashes go: trimmed first, "http://" became "http:",
        // which then parsed as a host named "http".
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return url.toString().trimEnd('/')
    }

    /**
     * The base URLs to try for [raw], best first: just the normalized one when a scheme was
     * typed or the host is local, else HTTPS then HTTP (a home server reached over a public
     * name doesn't always have a certificate).
     */
    fun baseCandidates(raw: String): List<String> {
        val first = normalize(raw) ?: return emptyList()
        val trimmed = raw.trim()
        if (trimmed.contains("://") || !first.startsWith("https://")) return listOf(first)
        return listOf(first, "http://" + first.removePrefix("https://"))
    }

    /**
     * Finds the API behind [raw]. [get] performs a GET and returns the body of a 2xx answer,
     * or null for anything else (including no answer at all). Null when nothing answers as a
     * Fetch API.
     */
    fun resolve(raw: String, get: (String) -> String?): ResolvedFetchServer? {
        for (base in baseCandidates(raw)) {
            resolveBase(base, get)?.let { return it }
        }
        return null
    }

    private fun resolveBase(base: String, get: (String) -> String?): ResolvedFetchServer? {
        // The API itself. A website answers /health too (its single-page fallback serves
        // index.html for every path), which is why the body is checked, not the status.
        if (isApiHealth(get(endpoint(base, "health")))) return ResolvedFetchServer(base, null)

        // The website, saying where its API is.
        val announced = apiFromDiscovery(get(endpoint(base, DISCOVERY_PATH)))
            ?: apiFromConfigJs(get(endpoint(base, LEGACY_CONFIG_PATH)))
        announced?.let { resolveAgainst(base, it) }?.let { api ->
            if (api != base && isApiHealth(get(endpoint(api, "health")))) return ResolvedFetchServer(api, base)
        }

        // A website whose API is set to an address this client can't reach (the compose
        // file's example IP, say): the usual places relative to the website.
        for (api in conventionalApiUrls(base)) {
            if (isApiHealth(get(endpoint(api, "health")))) return ResolvedFetchServer(api, base)
        }
        return null
    }

    /** `<base>/api` (one domain behind a reverse proxy) and the same host on the API's default port. */
    internal fun conventionalApiUrls(base: String): List<String> {
        val url = base.toHttpUrlOrNull() ?: return emptyList()
        val sameHostDefaultPort = if (url.port == DEFAULT_API_PORT) {
            null
        } else {
            url.newBuilder().port(DEFAULT_API_PORT).encodedPath("/").build().toString().trimEnd('/')
        }
        return listOfNotNull("${base.trimEnd('/')}/api", sameHostDefaultPort)
    }

    /** True for the API's `/health` answer: JSON with `service: "api"`. */
    internal fun isApiHealth(body: String?): Boolean =
        parseObject(body)?.get("service")?.jsonPrimitive?.contentOrNull == "api"

    /** The `api` named by the website's `fetch-server.json`, when set. */
    internal fun apiFromDiscovery(body: String?): String? =
        parseObject(body)?.get("api")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** The API base the website's `config.js` gives its own pages, when set. */
    internal fun apiFromConfigJs(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val raw = CONFIG_JS_API.find(body)?.groupValues?.get(1) ?: return null
        val unescaped = runCatching { json.decodeFromString<String>("\"$raw\"") }.getOrNull() ?: return null
        return unescaped.trim().takeIf { it.isNotEmpty() }
    }

    /** [reference] (absolute, or a path such as `/api`) made absolute against [base], with no trailing slash. */
    internal fun resolveAgainst(base: String, reference: String): String? {
        val baseUrl = (base.trimEnd('/') + "/").toHttpUrlOrNull() ?: return null
        return baseUrl.resolve(reference)?.toString()?.trimEnd('/')
    }

    /** The website page that confirms a pairing [code], filled in. */
    fun confirmUrl(webUrl: String, code: String): String =
        "${webUrl.trimEnd('/')}/pair?code=${URLEncoder.encode(code, "UTF-8").replace("+", "%20")}"

    fun endpoint(serverUrl: String, path: String): String = "${serverUrl.trimEnd('/')}/$path"

    /** The host part of a scheme-less address: no user info, port, path or IPv6 brackets. */
    fun hostOf(address: String): String {
        val authority = address.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostAndPort = authority.substringAfterLast('@')
        if (hostAndPort.startsWith("[")) return hostAndPort.substring(1).substringBefore(']')
        return hostAndPort.substringBefore(':')
    }

    /**
     * True for hosts that can only be on the local network: loopback, private (RFC 1918)
     * and link-local IPv4, loopback / link-local / unique-local IPv6, `.local` and `.lan`
     * names, and single-label names such as `nas` or `localhost`.
     */
    fun isLocalNetworkHost(host: String): Boolean {
        val name = host.trim().trimEnd('.').lowercase()
        if (name.isEmpty()) return false
        if (name.contains(':')) {
            // IPv6 literal.
            if (name == "::1") return true
            val firstGroup = name.substringBefore(':').toIntOrNull(16) ?: return false
            return (firstGroup and 0xffc0) == 0xfe80 || // fe80::/10 link-local
                (firstGroup and 0xfe00) == 0xfc00 // fc00::/7 unique local
        }
        val octets = name.split('.')
        if (octets.size == 4 && octets.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = octets.take(2).map { it.toInt() }
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254)
        }
        return !name.contains('.') || name.endsWith(".local") || name.endsWith(".lan")
    }

    private fun parseObject(body: String?): JsonObject? {
        if (body.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }
}
