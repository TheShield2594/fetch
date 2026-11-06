package com.fetch.tv.core.player

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.fetch.tv.R

object ExternalPlayerLauncher {

    /**
     * Fire-and-forget launch of an external player.
     * Used as a fallback when ActivityResultLauncher is not available (e.g. non-Activity context).
     */
    fun launch(
        context: Context,
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        resumePositionMs: Long = 0L,
        startFromBeginning: Boolean = false,
        subtitles: List<SubtitleInput>? = null,
        skipSegmentsJson: String? = null
    ): Boolean {
        return try {
            val intent = ExternalPlayerIntents.build(
                ExternalPlayerInput(
                    url = url,
                    title = title,
                    headers = headers,
                    resumePositionMs = resumePositionMs,
                    startFromBeginning = startFromBeginning,
                    subtitles = subtitles,
                    skipSegmentsJson = skipSegmentsJson
                )
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.player_no_external_player),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }
}
