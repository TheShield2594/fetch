package com.fetch.tv.domain.deeplink

sealed interface AppDeepLink {
    data class Meta(
        val type: String,
        val id: String
    ) : AppDeepLink

    data class AddonInstall(
        val manifestUrl: String
    ) : AppDeepLink

    data class Party(
        val code: String
    ) : AppDeepLink

    /** Opens Fetch's search with [query] typed in: the system search's "search in Fetch". */
    data class Search(
        val query: String
    ) : AppDeepLink
}
