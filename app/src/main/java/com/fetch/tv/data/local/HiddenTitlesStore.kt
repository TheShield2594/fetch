package com.fetch.tv.data.local

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** A title the profile said it isn't interested in (#650). */
@Immutable
data class HiddenTitle(
    /** Every id the title was seen under when hidden: the canonical one first, then e.g. "tmdb:123". */
    val ids: List<String>,
    val type: String,
    val name: String,
    val poster: String? = null,
    val hiddenAtMs: Long = 0L
) {
    /** The preference key this title is stored under; one key per title, so sync merges titles one by one. */
    val storageKey: String get() = hiddenTitleStorageKey(ids.first(), type)

    /** True for [contentId] or [imdbId] naming this title, of the same kind (a movie and a show can share "tmdb:1"). */
    fun matches(contentId: String, imdbId: String?, type: String?): Boolean =
        sameTitleKind(this.type, type) && ids.any { it == contentId || (imdbId != null && it == imdbId) }
}

/** What a profile hides from its suggestions: titles it isn't interested in, and whether watched ones go too. */
@Immutable
data class HiddenTitlesState(
    /** Newest first. */
    val titles: List<HiddenTitle> = emptyList(),
    val hideWatched: Boolean = false
) {
    companion object {
        val Empty = HiddenTitlesState()
    }
}

/**
 * Each profile's "Not interested" titles and its "Hide watched" choice, in the profile's
 * `hidden_titles` DataStore. Profile settings sync carries that store, to the Fetch website and
 * other TVs and into backups. Each title is a key of its own (`title:<kind>:<id>`) so the
 * key-by-key settings merge keeps a title hidden on one TV and another restored elsewhere.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenTitlesStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        const val FEATURE = "hidden_titles"
        internal const val HIDE_WATCHED_KEY_NAME = "hide_watched"
        private val HIDE_WATCHED_KEY = booleanPreferencesKey(HIDE_WATCHED_KEY_NAME)
        /**
         * Oldest titles past this go, keeping the synced settings blob (256 KB on the website)
         * well within bounds.
         */
        internal const val MAX_TITLES = 300
    }

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    /** The active profile's hidden titles and setting. An unreadable store reads as nothing hidden. */
    val state: Flow<HiddenTitlesState> = profileManager.activeProfileId
        .flatMapLatest { id ->
            store(id).data.catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
        }
        .map(::hiddenTitlesStateOf)
        .distinctUntilChanged()

    suspend fun hide(title: HiddenTitle) {
        if (title.ids.isEmpty()) return
        store(profileManager.currentProfileId()).edit { prefs -> prefs.withHiddenTitle(title) }
    }

    suspend fun restore(title: HiddenTitle) {
        store(profileManager.currentProfileId()).edit { prefs -> prefs.withoutHiddenTitle(title) }
    }

    suspend fun setHideWatched(hide: Boolean) {
        store(profileManager.currentProfileId()).edit { prefs ->
            // Off is the default, stored as no key so an untouched profile syncs nothing.
            if (hide) prefs[HIDE_WATCHED_KEY] = true else prefs.remove(HIDE_WATCHED_KEY)
        }
    }
}

internal const val HIDDEN_TITLE_KEY_PREFIX = "title:"

/** Movies apart from shows ("series", "tv" and "anime" are one kind); other types as they are; null when blank. */
private fun titleKind(type: String?): String? = when (val normalized = type?.trim()?.lowercase()) {
    null, "" -> null
    "series", "tv", "anime" -> "series"
    else -> normalized
}

internal fun sameTitleKind(a: String?, b: String?): Boolean {
    val mine = titleKind(a) ?: return true
    val theirs = titleKind(b) ?: return true
    return mine == theirs
}

internal fun isSeriesKind(type: String?): Boolean = titleKind(type) == "series"

internal fun hiddenTitleStorageKey(id: String, type: String): String =
    "$HIDDEN_TITLE_KEY_PREFIX${titleKind(type) ?: "any"}:$id"

internal fun encodeHiddenTitle(title: HiddenTitle): String = JSONObject().apply {
    put("ids", JSONArray(title.ids))
    put("type", title.type)
    put("name", title.name)
    title.poster?.takeIf { it.isNotBlank() }?.let { put("poster", it) }
    put("hiddenAt", title.hiddenAtMs)
}.toString()

/** Null for a value that isn't a hidden title, so one damaged entry doesn't hide the rest. */
internal fun decodeHiddenTitle(raw: String?): HiddenTitle? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val json = JSONObject(raw)
        val idsArray = json.optJSONArray("ids") ?: return null
        val ids = (0 until idsArray.length())
            .mapNotNull { idsArray.optString(it).trim().takeIf(String::isNotEmpty) }
            .distinct()
        if (ids.isEmpty()) return null
        HiddenTitle(
            ids = ids,
            type = json.optString("type"),
            name = json.optString("name").ifBlank { ids.first() },
            poster = json.optString("poster").takeIf { it.isNotBlank() },
            hiddenAtMs = json.optLong("hiddenAt", 0L)
        )
    }.getOrNull()
}

internal fun hiddenTitlesStateOf(prefs: Preferences): HiddenTitlesState {
    var hideWatched = false
    val titles = mutableListOf<HiddenTitle>()
    prefs.asMap().forEach { (key, value) ->
        when {
            key.name == HiddenTitlesStore.HIDE_WATCHED_KEY_NAME -> hideWatched = value == true
            key.name.startsWith(HIDDEN_TITLE_KEY_PREFIX) -> decodeHiddenTitle(value as? String)?.let(titles::add)
        }
    }
    return HiddenTitlesState(
        titles = titles.sortedWith(compareByDescending<HiddenTitle> { it.hiddenAtMs }.thenBy { it.name }),
        hideWatched = hideWatched
    )
}

/**
 * Adds [title], replacing any entry for the same title (by any shared id) so it is stored once,
 * and drops the oldest titles past [HiddenTitlesStore.MAX_TITLES].
 */
internal fun MutablePreferences.withHiddenTitle(title: HiddenTitle) {
    if (title.ids.isEmpty()) return
    val existing = hiddenTitleEntries()
    val merged = existing
        .filter { (_, stored) -> stored.overlaps(title) }
        .fold(title) { acc, (_, stored) -> acc.copy(ids = (acc.ids + stored.ids).distinct()) }
    existing.filter { (_, stored) -> stored.overlaps(title) }.forEach { (key, _) -> remove(key) }
    this[stringPreferencesKey(merged.storageKey)] = encodeHiddenTitle(merged)
    trimHiddenTitles()
}

/**
 * Drops the oldest titles past [HiddenTitlesStore.MAX_TITLES]: after a local hide, and after a
 * sync, whose merge keeps every title either side had.
 */
internal fun MutablePreferences.trimHiddenTitles() {
    val all = hiddenTitleEntries()
    if (all.size > HiddenTitlesStore.MAX_TITLES) {
        all.sortedBy { (_, stored) -> stored.hiddenAtMs }
            .take(all.size - HiddenTitlesStore.MAX_TITLES)
            .forEach { (key, _) -> remove(key) }
    }
}

/** Removes every stored entry for [title] (any entry sharing an id with it). */
internal fun MutablePreferences.withoutHiddenTitle(title: HiddenTitle) {
    hiddenTitleEntries()
        .filter { (key, stored) -> key.name == title.storageKey || stored.overlaps(title) }
        .forEach { (key, _) -> remove(key) }
}

private fun HiddenTitle.overlaps(other: HiddenTitle): Boolean =
    sameTitleKind(type, other.type) && ids.any { it in other.ids }

private fun MutablePreferences.hiddenTitleEntries(): List<Pair<Preferences.Key<String>, HiddenTitle>> =
    asMap().mapNotNull { (key, value) ->
        if (!key.name.startsWith(HIDDEN_TITLE_KEY_PREFIX)) return@mapNotNull null
        val title = decodeHiddenTitle(value as? String) ?: return@mapNotNull null
        stringPreferencesKey(key.name) to title
    }
