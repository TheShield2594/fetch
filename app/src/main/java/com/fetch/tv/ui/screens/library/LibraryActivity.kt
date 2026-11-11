package com.fetch.tv.ui.screens.library

import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * The Library's tabs (audit C3), in the order the row shows them. Watchlist and Lists split the
 * connected provider's lists (or this TV's own) in two: its watchlist, and every other list,
 * the profile's lists on this TV shown beside the provider's. In progress and History are this
 * profile's watch activity; Servers is the connected media servers. Upcoming and Stats open their
 * own pages from the same row, so the Library is the one place for "my things".
 */
enum class LibraryTab {
    WATCHLIST,
    IN_PROGRESS,
    HISTORY,
    UPCOMING,
    LISTS,
    SERVERS,
    STATS;

    /** Tabs that open another page instead of switching the grid. */
    val opensPage: Boolean get() = this == UPCOMING || this == STATS

    /** The lists the tab's grid offers, for the tabs that show the saved-titles grid. */
    val listScope: LibraryListScope?
        get() = when (this) {
            WATCHLIST -> LibraryListScope.WATCHLIST
            LISTS -> LibraryListScope.LISTS
            else -> null
        }
}

/** One title on the In progress or History tab: the latest activity for that title. */
data class LibraryActivityItem(
    val id: String,
    val type: String,
    val title: String,
    val poster: String?,
    val season: Int?,
    val episode: Int?,
    /** When it was last watched (History) or last played (In progress), epoch ms. */
    val at: Long,
    val addonBaseUrl: String? = null,
    /** 0..100 for In progress; null on History. */
    val progressPercent: Int? = null
) {
    val key: String get() = "$type:$id"

    val isSeries: Boolean
        get() = type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)

    /** [detail] goes in the card's second line (the episode, or how far in). */
    fun toMetaPreview(detail: String?): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = title,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = detail,
        imdbRating = null,
        genres = emptyList()
    )
}

/** The History tab's items of one calendar month, newest first. */
data class LibraryHistoryMonth(
    val month: YearMonth,
    val items: List<LibraryActivityItem>
)

/**
 * One entry per title, at its most recent watch, newest first. A series appears once, with the
 * episode watched last, so a show binged last month is one poster rather than a screenful.
 */
internal fun libraryHistoryItems(watched: List<WatchedItem>): List<LibraryActivityItem> =
    watched.asSequence()
        .filter { it.contentId.isNotBlank() && it.watchedAt > 0L }
        .groupBy { it.contentId }
        .values
        .map { entries ->
            val latest = entries.maxBy { it.watchedAt }
            LibraryActivityItem(
                id = latest.contentId,
                type = latest.contentType,
                // Older entries can lack a title; another entry of the same title may have one.
                title = latest.title.ifBlank { entries.firstOrNull { it.title.isNotBlank() }?.title ?: latest.contentId },
                poster = latest.poster ?: entries.firstNotNullOfOrNull { it.poster },
                season = latest.season,
                episode = latest.episode,
                at = latest.watchedAt
            )
        }
        .sortedByDescending { it.at }

/** [items] (newest first) split into calendar months in [zone], newest month first. */
internal fun groupHistoryByMonth(
    items: List<LibraryActivityItem>,
    zone: ZoneId = ZoneId.systemDefault()
): List<LibraryHistoryMonth> {
    if (items.isEmpty()) return emptyList()
    val months = LinkedHashMap<YearMonth, MutableList<LibraryActivityItem>>()
    items.sortedByDescending { it.at }.forEach { item ->
        val month = YearMonth.from(Instant.ofEpochMilli(item.at).atZone(zone))
        months.getOrPut(month) { mutableListOf() } += item
    }
    return months.map { (month, monthItems) -> LibraryHistoryMonth(month, monthItems) }
}

/**
 * What is partly watched, one entry per title at its latest play, newest first. Only progress
 * that is genuinely under way: the repository's Continue Watching list already leaves out
 * finished items.
 */
internal fun libraryInProgressItems(progress: List<WatchProgress>): List<LibraryActivityItem> =
    progress.asSequence()
        .filter { it.contentId.isNotBlank() }
        .groupBy { it.contentId }
        .values
        .map { entries ->
            val latest = entries.maxBy { it.lastWatched }
            val percent = latest.progressPercent
                ?: if (latest.duration > 0L) latest.position * 100f / latest.duration else null
            LibraryActivityItem(
                id = latest.contentId,
                type = latest.contentType,
                title = latest.name.ifBlank { latest.contentId },
                poster = latest.poster,
                season = latest.season,
                episode = latest.episode,
                at = latest.lastWatched,
                addonBaseUrl = latest.addonBaseUrl,
                progressPercent = percent?.toInt()?.coerceIn(0, 100)
            )
        }
        .sortedByDescending { it.at }
