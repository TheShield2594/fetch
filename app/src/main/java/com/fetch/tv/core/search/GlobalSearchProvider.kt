package com.fetch.tv.core.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Gives the TV's system search Fetch's results (`res/xml/searchable.xml`). The launcher queries
 * `content://<authority>/search_suggest_query/<query>?limit=<n>` on a binder thread, so the
 * blocking wait here is off the main thread; [GlobalSearch] bounds it to a few seconds. Picking a
 * result opens its `fetch://meta` link in [com.fetch.tv.MainActivity].
 */
class GlobalSearchProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GlobalSearchEntryPoint {
        fun globalSearch(): GlobalSearch
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val query = globalSearchQuery(uri.pathSegments, selectionArgs?.firstOrNull()) ?: return cursor
        val limit = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)?.toIntOrNull()
            ?.coerceIn(1, MAX_LIMIT) ?: GlobalSearch.DEFAULT_LIMIT
        val context = context?.applicationContext ?: return cursor
        val results = try {
            val search = EntryPointAccessors.fromApplication(context, GlobalSearchEntryPoint::class.java).globalSearch()
            runBlocking { search.search(query, limit) }
        } catch (error: CancellationException) {
            emptyList()
        } catch (error: Exception) {
            Log.w(TAG, "System search failed", error)
            emptyList()
        }
        results.forEachIndexed { index, result -> cursor.addRow(row(index, result)) }
        return cursor
    }

    private fun row(index: Int, result: GlobalSearchResult): Array<Any?> = arrayOf(
        index.toLong(),
        result.title,
        result.description,
        result.posterUrl,
        result.posterUrl,
        if (result.isMovie) CONTENT_TYPE_MOVIE else CONTENT_TYPE_SERIES,
        0,
        result.year,
        Intent.ACTION_VIEW,
        result.deepLink,
        // Results change with the addons, so the launcher shouldn't keep them as shortcuts.
        SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT
    )

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val TAG = "GlobalSearchProvider"
        private const val MAX_LIMIT = 50
        private const val CONTENT_TYPE_MOVIE = "vnd.android.cursor.item/vnd.fetch.movie"
        private const val CONTENT_TYPE_SERIES = "vnd.android.cursor.item/vnd.fetch.series"

        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_IS_LIVE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_SHORTCUT_ID
        )
    }
}

/**
 * The text typed into the system search: the path segment after `search_suggest_query`, or the
 * selection argument when the searchable config passes it that way. Null when there's none.
 */
internal fun globalSearchQuery(pathSegments: List<String>, selectionArg: String?): String? {
    val fromPath = pathSegments
        .indexOf(SearchManager.SUGGEST_URI_PATH_QUERY)
        .takeIf { it >= 0 }
        ?.let { pathSegments.getOrNull(it + 1) }
    return (selectionArg ?: fromPath)?.trim()?.takeIf { it.isNotEmpty() }
}
