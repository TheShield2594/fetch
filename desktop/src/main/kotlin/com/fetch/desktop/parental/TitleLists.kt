package com.fetch.desktop.parental

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.JsonFile
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * One title a parent hid from a profile or allowed on it whatever its rating, as the TV's
 * `ParentalTitle` stores it: every id it goes by, its type and its artwork.
 */
@Serializable
data class ParentalTitle(
    val ids: List<String>,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null
) {
    /**
     * True for [contentId] or [imdbId] naming this title, or one of its episodes ("tt123:1:2").
     * A movie and a series can share an id ("tmdb:123"), so a known [type] must agree too.
     */
    fun matches(contentId: String, imdbId: String?, type: String?): Boolean =
        sameKind(type) && ids.any { id -> id == contentId || id == imdbId || contentId.startsWith("$id:") }

    internal fun sameKind(other: String?): Boolean {
        val mine = titleKind(type) ?: return true
        val theirs = titleKind(other) ?: return true
        return mine == theirs
    }
}

/** Movies apart from series ("series", "tv" and "anime" are one kind); null when unknown. */
private fun titleKind(type: String?): String? = when (type?.trim()?.lowercase()) {
    "movie" -> "movie"
    "series", "tv", "anime" -> "series"
    null, "" -> null
    else -> type.trim().lowercase()
}

/** The title a poster's menu was opened on, as the lists store it (the TV's `parentalTitleOf`). */
fun parentalTitleOf(item: MetaPreview): ParentalTitle = ParentalTitle(
    ids = listOfNotNull(item.id, item.imdbId).filter { it.isNotBlank() }.distinct(),
    type = item.apiType,
    name = item.name,
    poster = item.poster,
    background = item.background
)

/** How a parent set one title for a profile (the TV's `ParentalTitleState`). */
enum class ParentalTitleState(val label: String) {
    /** The profile's rating limit decides. */
    BY_RATING("rating limit decides"),
    HIDDEN("hidden"),
    /** Shown whatever its rating. */
    ALLOWED("always allowed");

    /** The next state the per-profile button steps to, as on TV. */
    val next: ParentalTitleState
        get() = when (this) {
            BY_RATING -> HIDDEN
            HIDDEN -> ALLOWED
            ALLOWED -> BY_RATING
        }
}

/** Titles hidden from one profile and titles allowed on it; a title is on one list at most. */
@Serializable
data class ProfileTitleLists(
    val hidden: List<ParentalTitle> = emptyList(),
    val allowed: List<ParentalTitle> = emptyList()
) {
    val isEmpty: Boolean get() = hidden.isEmpty() && allowed.isEmpty()

    fun isHidden(contentId: String, imdbId: String?, type: String?): Boolean =
        hidden.any { it.matches(contentId, imdbId, type) }

    fun isAllowed(contentId: String, imdbId: String?, type: String?): Boolean =
        allowed.any { it.matches(contentId, imdbId, type) }

    fun stateOf(title: ParentalTitle): ParentalTitleState = title.ids.firstNotNullOfOrNull { id ->
        when {
            isHidden(id, null, title.type) -> ParentalTitleState.HIDDEN
            isAllowed(id, null, title.type) -> ParentalTitleState.ALLOWED
            else -> null
        }
    } ?: ParentalTitleState.BY_RATING

    fun with(title: ParentalTitle, state: ParentalTitleState): ProfileTitleLists = when (state) {
        ParentalTitleState.BY_RATING -> without(title)
        ParentalTitleState.HIDDEN -> without(title).let { it.copy(hidden = it.hidden + title) }
        ParentalTitleState.ALLOWED -> without(title).let { it.copy(allowed = it.allowed + title) }
    }

    /** Takes the title off both lists. */
    fun without(title: ParentalTitle): ProfileTitleLists {
        fun List<ParentalTitle>.drop() = filterNot { listed -> listed.sameKind(title.type) && listed.ids.any { it in title.ids } }
        return ProfileTitleLists(hidden = hidden.drop(), allowed = allowed.drop())
    }

    companion object {
        val Empty = ProfileTitleLists()
    }
}

fun titleListsFile(dir: File = AppDirs.config) = JsonFile(
    File(dir, "parental-titles.json"),
    MapSerializer(String.serializer(), ProfileTitleLists.serializer())
) { emptyMap() }

/**
 * Every profile's title lists, kept in one file for the whole app as the TV keeps them in its
 * restrictions store. Like the TV, they stay on this device: a sync pull never changes them and
 * they aren't pushed. The main profile is the parent's and has none.
 */
class TitleListsState(private val store: JsonFile<Map<String, ProfileTitleLists>> = titleListsFile()) {
    var all: Map<Int, ProfileTitleLists> by mutableStateOf(load())
        private set

    private fun load(): Map<Int, ProfileTitleLists> =
        runCatching { store.load() }.getOrDefault(emptyMap()).mapNotNull { (id, lists) -> id.toIntOrNull()?.let { it to lists } }.toMap()

    fun listsFor(profileId: Int): ProfileTitleLists = all[profileId] ?: ProfileTitleLists.Empty

    fun stateFor(profileId: Int, title: ParentalTitle): ParentalTitleState = listsFor(profileId).stateOf(title)

    fun setState(profileId: Int, title: ParentalTitle, state: ParentalTitleState): Boolean {
        if (profileId == DesktopProfile.PRIMARY_ID) return false
        val updated = listsFor(profileId).with(title, state)
        save(if (updated.isEmpty) all - profileId else all + (profileId to updated))
        return true
    }

    fun cycle(profileId: Int, title: ParentalTitle) = setState(profileId, title, stateFor(profileId, title).next)

    /** A deleted profile's lists go with it. */
    fun deleteProfile(profileId: Int) {
        if (profileId in all) save(all - profileId)
    }

    private fun save(updated: Map<Int, ProfileTitleLists>) {
        all = updated
        runCatching { store.save(updated.mapKeys { it.key.toString() }) }
    }

    // --- The poster menu's dialog and PIN request (one at a time, for the whole window) ---

    /** The title whose per-profile dialog is open (on the main profile). */
    var editing: ParentalTitle? by mutableStateOf(null)

    /** "Hide from <profile>" waiting for the parent PIN. */
    var pendingHide: Pair<Int, ParentalTitle>? by mutableStateOf(null)
}

/** [file] without the Continue Watching entries of titles hidden from the profile, as on TV. */
fun visibleProgress(file: com.fetch.desktop.data.ProgressFile, lists: ProfileTitleLists): com.fetch.desktop.data.ProgressFile {
    if (lists.hidden.isEmpty()) return file
    val kept = file.entries.filterNot { lists.isHidden(it.contentId, null, it.contentType) }
    return if (kept.size == file.entries.size) file else file.copy(entries = kept)
}
