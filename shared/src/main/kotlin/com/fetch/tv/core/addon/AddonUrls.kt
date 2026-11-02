package com.fetch.tv.core.addon

import com.fetch.tv.domain.model.Addon
import java.net.URLEncoder

/**
 * Builds Stremio addon resource URLs from an addon's base (transport) URL.
 *
 * A base URL may carry a query string (some addons keep their configuration there); it belongs
 * after the resource path, so `https://a.example/x?cfg=1` becomes
 * `https://a.example/x/stream/movie/tt1.json?cfg=1`.
 */
object AddonUrls {

    fun manifest(baseUrl: String): String {
        val (basePath, baseQuery) = split(baseUrl)
        return "$basePath/manifest.json$baseQuery"
    }

    fun meta(baseUrl: String, type: String, id: String): String {
        val (basePath, baseQuery) = split(baseUrl)
        return "$basePath/meta/${encode(type)}/${encode(id)}.json$baseQuery"
    }

    fun stream(baseUrl: String, type: String, videoId: String): String {
        val (basePath, baseQuery) = split(baseUrl)
        return "$basePath/stream/${encode(type)}/${encode(videoId)}.json$baseQuery"
    }

    fun catalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int = 0,
        extraArgs: Map<String, String> = emptyMap()
    ): String {
        val (basePath, baseQuery) = split(baseUrl)
        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)
            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }
            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }
        return catalogPath + baseQuery
    }

    /** Splits [baseUrl] into its path (no trailing slash) and its query string (with `?`, or ""). */
    fun split(baseUrl: String): Pair<String, String> {
        val trimmed = baseUrl.trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val basePath = if (queryStart >= 0) trimmed.substring(0, queryStart).trimEnd('/') else trimmed
        val baseQuery = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        return basePath to baseQuery
    }

    /** Percent-encodes one path segment or query value (spaces as `%20`, not `+`). */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** Whether the addon has its own `/configure` page (Stremio `behaviorHints.configurable`). */
val Addon.isConfigurable: Boolean
    get() = behaviorHints?.configurable == true || behaviorHints?.configurationRequired == true

/** Whether the addon refuses to work until it is configured (`behaviorHints.configurationRequired`). */
val Addon.needsConfiguration: Boolean
    get() = behaviorHints?.configurationRequired == true

/**
 * The addon's configuration page: `<transportUrl base>/configure`. Query strings belong to the
 * manifest request, not the page, so they are dropped.
 */
fun addonConfigureUrl(baseUrl: String): String {
    val path = baseUrl.trim().substringBefore('?').trimEnd('/')
    val base = if (path.endsWith("/manifest.json", ignoreCase = true)) {
        path.dropLast("/manifest.json".length).trimEnd('/')
    } else path
    return "$base/configure"
}

/**
 * Normalizes what a user pastes or an addon hands back (`stremio://…/manifest.json`,
 * `https://…/manifest.json`, a bare base URL) into an http(s) base URL, or null.
 */
fun normalizeAddonInstallUrl(input: String): String? {
    var trimmed = input.trim()
    if (trimmed.startsWith("stremio://", ignoreCase = true)) {
        trimmed = "https://" + trimmed.substring("stremio://".length)
    }
    if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val queryStart = trimmed.indexOf('?')
    val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
    val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
    val cleanPath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
        path.dropLast("/manifest.json".length)
    } else path
    return cleanPath.trimEnd('/') + query
}
