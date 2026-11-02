package com.fetch.desktop.ratings

import java.util.Properties

/**
 * The app's TMDB credential: either a v3 API key (sent as the `api_key` query parameter) or a
 * v4 read access token (a JWT, sent as `Authorization: Bearer`). See [TmdbAuth].
 */
object TmdbCredentials {
    /**
     * Reads `tmdbApiKey` from `/fetch-desktop.properties` on the classpath, falling back to the
     * `TMDB_API_KEY` environment variable. Blank values and unexpanded `${placeholder}`s count as
     * missing; returns null when neither source has one.
     */
    fun load(): String? {
        val props = Properties()
        runCatching {
            TmdbCredentials::class.java.getResourceAsStream("/fetch-desktop.properties")?.use(props::load)
        }
        return from(props, System::getenv)
    }

    /** [load] with its sources injected, for tests. */
    internal fun from(props: Properties, env: (String) -> String?): String? =
        props.getProperty("tmdbApiKey").usable() ?: env("TMDB_API_KEY").usable()

    private fun String?.usable(): String? = this?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("$") }
}

/** How a TMDB credential is attached to requests. */
internal object TmdbAuth {
    /** v4 read access tokens are JWTs: three base64url segments, the header starting `eyJ`. */
    fun isBearerToken(credential: String): Boolean =
        credential.startsWith("eyJ") && credential.count { it == '.' } == 2
}
