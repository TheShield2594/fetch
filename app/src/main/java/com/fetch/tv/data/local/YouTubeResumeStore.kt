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
import com.fetch.tv.data.youtube.YouTubeResume
import com.fetch.tv.data.youtube.YouTubeResumePoint

/**
 * Each profile's last position in the YouTube videos it watched in Fetch. YouTube's tiles only say
 * how far in by whole percent, and only on the feeds; this keeps the exact place for any video,
 * Up Next and related ones included. Holds the [YouTubeResume.MAX_POINTS]
 * most recent; when a place counts is [YouTubeResume]'s rule, shared with the desktop.
 */
@Singleton
class YouTubeResumeStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "youtube_resume"
    }

    private val entriesKey = stringPreferencesKey("entries")
    // One at a time, in the order asked, so a late periodic save can't bring back a point the
    // final save just dropped.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    suspend fun get(videoId: String): YouTubeResumePoint? =
        decodeResumePoints(store(profileManager.currentProfileId()).data.first()[entriesKey]).firstOrNull { it.videoId == videoId }

    /** [save] in this store's own scope, so it outlives the screen that asked. */
    fun saveInBackground(videoId: String, positionMs: Long, durationMs: Long) {
        // The profile that watched, not whichever is active when the queued save runs.
        val profileId = profileManager.currentProfileId()
        backgroundScope.launch {
            runSuspendCatching { save(videoId, positionMs, durationMs, profileId = profileId) }
                .onFailure { Log.w("YouTubeResumeStore", "Could not save the position of $videoId: ${it.message}") }
        }
    }

    /** Remembers [positionMs] for [videoId]; a finished video is forgotten, so it starts over next time. */
    suspend fun save(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        profileId: Int
    ) {
        store(profileId).edit { prefs ->
            val entries = decodeResumePoints(prefs[entriesKey])
            prefs[entriesKey] = encodeResumePoints(YouTubeResume.updatePoints(entries, videoId, positionMs, durationMs, nowMs))
        }
    }
}

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
