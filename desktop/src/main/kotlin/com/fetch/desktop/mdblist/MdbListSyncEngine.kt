package com.fetch.desktop.mdblist

import com.fetch.desktop.data.ProgressEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What the last MDBList sync brought back, kept between syncs so the next one fetches only what
 * changed (the TV's `MdbListSyncSnapshot`). [watermark] is MDBList's clock at that sync.
 */
@Serializable
data class MdbListSyncCache(
    val accountId: Long,
    val watched: List<MdbListWatchedItem> = emptyList(),
    val playback: List<MdbListPlayback> = emptyList(),
    val dropped: List<MdbListDropped> = emptyList(),
    val activities: MdbListActivities? = null,
    val watermark: String? = null,
    val checkedAtMs: Long? = null
) {
    fun watchedIds(): Set<String> = MdbListWatched(watched).videoIds()

    /** Whether [ids] (in [season], when given) is a show, or a season of one, dropped on MDBList. */
    fun isDropped(ids: MdbListIds, season: Int? = null): Boolean =
        dropped.any { it.ids.matches(ids) && (it.season == null || (season != null && it.season == season)) }

    /** Resume points for Continue Watching: not finished, and not in a dropped show or season (as on TV). */
    fun continueWatching(): List<ProgressEntry> =
        playback.filterNot { !it.isMovie && isDropped(it.ids, it.season) }.mapNotNull { it.toProgressEntry() }
}

/** Keeps [MdbListSyncCache] in the profile's `mdblist-sync.json` (no credentials in it). */
class MdbListSyncCacheStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun load(): MdbListSyncCache? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(MdbListSyncCache.serializer(), file.readText()) }.getOrNull()
    }

    fun save(value: MdbListSyncCache) {
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.encodeToString(MdbListSyncCache.serializer(), value))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun clear() {
        file.delete()
    }
}

fun mdbListSyncCacheFile(dir: File) = MdbListSyncCacheStore(File(dir, "mdblist-sync.json"))

/**
 * The TV's `MdbListSyncEngine`: asks `/sync/last_activities` what changed since the last sync,
 * then fetches only that. Watched history comes from the journal (`/sync/journal`) of changes
 * since the last sync; resume points and dropped shows are fetched again only when their
 * activity time moved. Everything is fetched in full on the first sync, for another account,
 * when the last one is older than the journal keeps, or when MDBList asks for it.
 */
class MdbListSyncEngine(private val client: MdbListSyncClient, private val now: () -> Long = System::currentTimeMillis) {

    suspend fun synchronize(auth: MdbListAuth, accountId: Long, previous: MdbListSyncCache?): MdbListSyncCache {
        val activities = client.activities(auth)
        val last = previous?.takeIf { it.accountId == accountId }
        val lastActivities = last?.activities
        val watermark = last?.watermark
        val full = last == null || lastActivities == null || activities == null || watermark == null || expired(watermark, activities.serverTime)
        if (full) {
            return MdbListSyncCache(
                accountId = accountId,
                watched = client.watched(auth).items,
                playback = client.playback(auth),
                dropped = client.dropped(auth),
                activities = activities,
                watermark = activities?.serverTime,
                checkedAtMs = now()
            )
        }
        last!!
        activities!!
        val watched = if (activities.watchedChanged(lastActivities!!)) {
            try {
                applyJournal(last.watched, client.journal(auth, watermark!!))
            } catch (_: MdbListJournalExpired) {
                client.watched(auth).items
            }
        } else last.watched
        val playback = if (activities.playbackChanged(lastActivities)) client.playback(auth) else last.playback
        val dropped = if (activities.droppedChanged(lastActivities)) client.dropped(auth) else last.dropped
        return last.copy(
            watched = watched,
            playback = playback,
            dropped = dropped,
            activities = activities,
            watermark = activities.serverTime,
            checkedAtMs = now()
        )
    }

    private fun expired(watermark: String, serverTime: String): Boolean {
        val from = MdbListAccountClient.timestamp(watermark) ?: return true
        val to = MdbListAccountClient.timestamp(serverTime) ?: return true
        return to - from !in 0 until JOURNAL_RETENTION_MS
    }

    companion object {
        /** How far back MDBList's journal reaches. */
        const val JOURNAL_RETENTION_MS = 30L * 24 * 60 * 60 * 1_000

        /**
         * Applies [journal]'s changes, oldest first, to [watched]. A journal entry names a title by
         * whichever ids MDBList has, so it's matched to the history by any of them. Whole shows or
         * seasons marked watched aren't listed episode by episode, so those need the full history:
         * this throws [MdbListJournalExpired] for them and the caller fetches it.
         */
        fun applyJournal(watched: List<MdbListWatchedItem>, journal: List<MdbListJournalEntry>): List<MdbListWatchedItem> {
            val items = watched.toMutableList()
            journal.sortedBy { it.actionAtMs }.forEach { change ->
                // TMDB and Trakt number movies and shows separately, so only match the same kind.
                val isMovieKind = change.kind == MdbListJournalEntry.Kind.MOVIE
                val known = items.firstOrNull { it.isMovie == isMovieKind && it.ids.matches(change.ids) }?.ids
                val ids = known?.merged(change.ids) ?: change.ids
                when (change.kind) {
                    MdbListJournalEntry.Kind.MOVIE, MdbListJournalEntry.Kind.EPISODE -> {
                        val isMovie = isMovieKind
                        val same: (MdbListWatchedItem) -> Boolean = {
                            it.isMovie == isMovie && it.ids.matches(change.ids) && it.season == change.season && it.episode == change.episode
                        }
                        items.removeAll(same)
                        if (!change.removed) items += MdbListWatchedItem(ids, isMovie, change.season, change.episode)
                    }
                    MdbListJournalEntry.Kind.SHOW, MdbListJournalEntry.Kind.SEASON -> {
                        if (!change.removed) throw MdbListJournalExpired()
                        items.removeAll {
                            !it.isMovie && it.ids.matches(change.ids) &&
                                (change.kind == MdbListJournalEntry.Kind.SHOW || it.season == change.season)
                        }
                    }
                }
            }
            return items
        }
    }
}
