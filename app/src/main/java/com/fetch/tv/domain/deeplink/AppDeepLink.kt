package com.fetch.tv.domain.deeplink

sealed interface AppDeepLink {
    data class Meta(
        val type: String,
        val id: String,
        /** The addon the link came from, so its own copy of the title opens; null to look it up in each. */
        val addonBaseUrl: String? = null
    ) : AppDeepLink

    data class AddonInstall(
        val manifestUrl: String
    ) : AppDeepLink

    /** Opens Fetch's search with [query] typed in: the system search's "search in Fetch". */
    data class Search(
        val query: String
    ) : AppDeepLink
}
