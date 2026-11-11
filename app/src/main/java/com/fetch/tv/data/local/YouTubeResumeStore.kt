package com.fetch.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.fetch.tv.core.util.runSuspendCatching

/** Where a YouTube video stopped in Fetch, to the millisecond. */
data class YouTubeResumePoint(
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long
)

/**
 * Each profile's last position in the YouTube videos it watched in Fetch. YouTube's tiles only say
 * how far in by whole percent, and only on the feeds; this keeps the exact place for any video,
 * Up Next and related ones included. Holds the [MAX_ENTRIES] most recent.
 */
@Singleton
class YouTubeResumeStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "youtube_resume"
        const val MAX_ENTRIES = 300
    }

    private val entriesKey = stringPreferencesKey("entries")
    // One at a time, in the order asked, so a late periodic save can't bring back a point the
    // final save just dropped.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    suspend fun get(videoId: String): YouTubeResumePoint? =
        decodeResumePoints(store().data.first()[entriesKey]).firstOrNull { it.videoId == videoId }

    /** [save] in this store's own scope, so it outlives the screen that asked. */
    fun saveInBackground(videoId: String, positionMs: Long, durationMs: Long) {
        backgroundScope.launch {
            runSuspendCatching { save(videoId, positionMs, durationMs) }
                .onFailure { Log.w("YouTubeResumeStore", "Could not save the position of $videoId: ${it.message}") }
        }
    }

    /** Remembers [positionMs] for [videoId]; a finished video is forgotten, so it starts over next time. */
    suspend fun save(videoId: String, positionMs: Long, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        store().edit { prefs ->
            val entries = decodeResumePoints(prefs[entriesKey])
            prefs[entriesKey] = encodeResumePoints(updateResumePoints(entries, videoId, positionMs, durationMs, nowMs, MAX_ENTRIES))
        }
    }
}

/**
 * [entries] with [videoId]'s point moved to the front, or dropped when the video was barely started
 * or nearly finished: those start over, as on YouTube.
 */
internal fun updateResumePoints(
    entries: List<YouTubeResumePoint>,
    videoId: String,
    positionMs: Long,
    durationMs: Long,
    nowMs: Long,
    max: Int
): List<YouTubeResumePoint> {
    val others = entries.filterNot { it.videoId == videoId }
    val point = YouTubeResumePoint(videoId, positionMs, durationMs, nowMs)
    return if (resumableMs(point.positionMs, point.durationMs) != null) (listOf(point) + others).take(max) else others
}

/**
 * [positionMs] when it's worth resuming from: past the first few seconds and short of the end.
 * The same window as [com.fetch.tv.ui.screens.youtube.resumePositionMs] uses for YouTube's percent.
 */
internal fun resumableMs(positionMs: Long, durationMs: Long): Long? {
    if (durationMs <= 0L || positionMs <= 0L) return null
    if (positionMs < MIN_RESUME_MS || positionMs < durationMs * 3 / 100) return null
    if (positionMs > durationMs * 95 / 100) return null
    return positionMs
}

private const val MIN_RESUME_MS = 10_000L

// One point per line: id, position, duration, time saved, tab-separated. Video ids hold neither.
internal fun encodeResumePoints(entries: List<YouTubeResumePoint>): String =
    entries.joinToString("\n") { "${it.videoId}\t${it.positionMs}\t${it.durationMs}\t${it.updatedAtMs}" }

internal fun decodeResumePoints(stored: String?): List<YouTubeResumePoint> =
    stored?.lineSequence()?.mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size != 4 || fields[0].isBlank()) return@mapNotNull null
        YouTubeResumePoint(
            videoId = fields[0],
            positionMs = fields[1].toLongOrNull() ?: return@mapNotNull null,
            durationMs = fields[2].toLongOrNull() ?: return@mapNotNull null,
            updatedAtMs = fields[3].toLongOrNull() ?: return@mapNotNull null
        )
    }?.toList().orEmpty()
