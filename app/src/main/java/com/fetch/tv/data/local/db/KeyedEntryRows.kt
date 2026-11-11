package com.fetch.tv.data.local.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Rows of the stores kept as one JSON entry per key: watch progress and the library (its items
 * and its queue of changes waiting to sync). Each entry keeps the JSON the DataStore layout
 * stored, so the format older builds wrote still reads; [contentId] and [sortTime] are copied
 * out of it to query by title and order by time without parsing.
 *
 * The two stores get a table each because Room re-runs a table's queries after any write to it:
 * progress is saved every few seconds during playback, and that mustn't wake the library.
 */
@Entity(
    tableName = "watch_progress",
    primaryKeys = ["profileId", "kind", "key"],
    indices = [Index(value = ["profileId", "contentId"])]
)
data class WatchProgressRow(
    val profileId: Int,
    val kind: String,
    val key: String,
    val contentId: String,
    val sortTime: Long,
    val json: String
)

@Entity(
    tableName = "library_entries",
    primaryKeys = ["profileId", "kind", "key"]
)
data class LibraryEntryRow(
    val profileId: Int,
    val kind: String,
    val key: String,
    val contentId: String,
    val sortTime: Long,
    val json: String
)
