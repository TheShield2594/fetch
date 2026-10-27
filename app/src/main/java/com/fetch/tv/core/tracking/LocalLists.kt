package com.fetch.tv.core.tracking

import com.fetch.tv.domain.model.LibraryListPrivacy
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem

/**
 * A list kept on the device for one profile, for people with no tracking account and no
 * Fetch website. The shape follows the website's `/lists` (an id, a name, a [kind] of
 * `watchlist`, `collection` or `custom`, and items with the time they were added), so the
 * library sync can carry them to the website later without a migration. Lists made on the
 * TV are `custom`; the profile's saved titles stay the [LOCAL_LIBRARY_LIST_KEY] list.
 */
data class LocalList(
    val id: String,
    val name: String,
    val kind: String = LocalListKind.CUSTOM,
    val description: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val items: List<SavedLibraryItem> = emptyList()
)

object LocalListKind {
    const val WATCHLIST = "watchlist"
    const val COLLECTION = "collection"
    const val CUSTOM = "custom"
}

private const val LOCAL_LIST_KEY_PREFIX = "local:list:"

fun localListKey(listId: String): String = LOCAL_LIST_KEY_PREFIX + listId

fun localListIdFromKey(key: String): String? =
    key.takeIf { it.startsWith(LOCAL_LIST_KEY_PREFIX) }
        ?.removePrefix(LOCAL_LIST_KEY_PREFIX)
        ?.takeIf { it.isNotBlank() }

fun isLocalListKey(key: String): Boolean = localListIdFromKey(key) != null

val LocalListManagementCapabilities = TrackingListManagementCapabilities(
    privacyOptions = listOf(LibraryListPrivacy.PRIVATE),
    supportsDescription = true,
    supportsReordering = true
)

fun LocalList.toListTab(): LibraryListTab = LibraryListTab(
    key = localListKey(id),
    title = name,
    type = LibraryListTab.Type.PERSONAL,
    description = description,
    privacy = LibraryListPrivacy.PRIVATE
)

/**
 * The poster and detail long-press open the list picker in the local library once the profile
 * has lists of its own; with none, saving a title stays a single toggle.
 */
fun usesLibraryListPicker(
    isLocalSource: Boolean,
    membershipTabs: List<LibraryListTab>
): Boolean = !isLocalSource || membershipTabs.any { isLocalListKey(it.key) }

/** Pure edits of a profile's local lists; the order of the list is the order shown. */
object LocalListsReducer {
    const val MAX_NAME_LENGTH = 100

    /** Drops what Gson can leave null in a damaged or older file, and duplicate ids or items. */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
    fun sanitize(lists: List<LocalList?>?): List<LocalList> {
        val seen = mutableSetOf<String>()
        return lists.orEmpty().mapNotNull { list ->
            if (list == null || list.id == null || list.id.isBlank() || !seen.add(list.id)) return@mapNotNull null
            val itemKeys = mutableSetOf<String>()
            val items: List<SavedLibraryItem?> = list.items ?: emptyList()
            list.copy(
                name = (list.name ?: "").ifBlank { list.id },
                kind = (list.kind ?: "").ifBlank { LocalListKind.CUSTOM },
                items = items.filterNotNull().filter { item ->
                    item.id != null && item.type != null && itemKeys.add(item.membershipKey())
                }.map { item ->
                    item.copy(
                        name = item.name ?: "",
                        genres = item.genres ?: emptyList(),
                        posterShape = item.posterShape ?: PosterShape.POSTER
                    )
                }
            )
        }
    }

    fun create(
        lists: List<LocalList>,
        id: String,
        name: String,
        description: String?,
        nowEpochMs: Long
    ): List<LocalList> {
        val cleanName = cleanName(name)
        return lists + LocalList(
            id = id,
            name = cleanName,
            description = cleanDescription(description),
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs
        )
    }

    fun update(
        lists: List<LocalList>,
        id: String,
        name: String,
        description: String?,
        nowEpochMs: Long
    ): List<LocalList> {
        val cleanName = cleanName(name)
        require(lists.any { it.id == id }) { "List not found" }
        return lists.map { list ->
            if (list.id == id) {
                list.copy(name = cleanName, description = cleanDescription(description), updatedAt = nowEpochMs)
            } else {
                list
            }
        }
    }

    fun delete(lists: List<LocalList>, id: String): List<LocalList> {
        require(lists.any { it.id == id }) { "List not found" }
        return lists.filterNot { it.id == id }
    }

    /**
     * Puts [orderedIds] first in that order; lists the caller didn't name keep their relative
     * order after them, so a reorder never drops a list made on another screen meanwhile.
     */
    fun reorder(lists: List<LocalList>, orderedIds: List<String>): List<LocalList> {
        val byId = lists.associateBy { it.id }
        val ordered = orderedIds.distinct().mapNotNull(byId::get)
        val orderedSet = ordered.mapTo(mutableSetOf()) { it.id }
        return ordered + lists.filterNot { it.id in orderedSet }
    }

    /** Adds or removes [item] from each list named in [desired] by list id. */
    fun applyMembership(
        lists: List<LocalList>,
        item: SavedLibraryItem,
        desired: Map<String, Boolean>,
        nowEpochMs: Long
    ): List<LocalList> {
        if (desired.isEmpty()) return lists
        val key = item.membershipKey()
        return lists.map { list ->
            val wanted = desired[list.id] ?: return@map list
            val present = list.items.any { it.membershipKey() == key }
            when {
                wanted && !present -> list.copy(
                    items = list.items + item.copy(addedAt = nowEpochMs),
                    updatedAt = nowEpochMs
                )
                !wanted && present -> list.copy(
                    items = list.items.filterNot { it.membershipKey() == key },
                    updatedAt = nowEpochMs
                )
                else -> list
            }
        }
    }

    fun membership(lists: List<LocalList>, itemId: String, itemType: String): Map<String, Boolean> {
        val key = membershipKey(itemId, itemType)
        return lists.associate { list ->
            localListKey(list.id) to list.items.any { it.membershipKey() == key }
        }
    }

    private fun cleanName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "List name is required" }
        return trimmed.take(MAX_NAME_LENGTH)
    }

    private fun cleanDescription(description: String?): String? =
        description?.trim()?.takeIf { it.isNotBlank() }

    private fun SavedLibraryItem.membershipKey(): String = membershipKey(id, type)

    private fun membershipKey(itemId: String, itemType: String): String =
        "${itemType.trim().lowercase()}:$itemId"
}
