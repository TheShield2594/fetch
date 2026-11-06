package com.fetch.tv.core.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Subtitle data to pass to an external player.
 */
data class SubtitleInput(
    val url: String,
    val name: String,
    val lang: String
)

/**
 * Input data for launching an external video player.
 */
data class ExternalPlayerInput(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String>? = null,
    val resumePositionMs: Long = 0L,
    val startFromBeginning: Boolean = false,
    val subtitles: List<SubtitleInput>? = null,
    // Pre-resolved intro/outro skip segments as a JSON array string
    // (`[{"type","start","end"}]`, times in seconds). Read by mpvNova.
    val skipSegmentsJson: String? = null
)

/**
 * Result returned by an external video player after playback ends.
 * Not all players return this data — MX Player, VLC, and Just Player are known to support it.
 */
data class ExternalPlayerResult(
    val positionMs: Long,
    val durationMs: Long?,
    val endedByUser: Boolean = true
)

/**
 * ActivityResultContract that launches an external video player via ACTION_VIEW and parses the
 * playback result. Players disagree wildly on what they return, so [parseResult] is deliberately
 * lenient. Observed contracts (verified on-device):
 * - MX Player / mpvNova: position + duration (Int ms) + end_by.
 * - Just Player: on completion returns ONLY end_by=playback_completion (no position/duration).
 * - mpv-android (vanilla is.xyz.mpv): position/duration (Int) only on a back-press; at EOF returns
 *   RESULT_OK with NO extras and no end_by (handled by the heuristic in [parseResult]).
 * - VLC: extra_position/extra_duration (Long ms), no end_by; duration is <= 0 for network streams
 *   (the tracker backfills it). Ignores our launch resume extras, so resume into VLC won't take.
 * - Vimu: position.
 * - NovaPlayer: returns nothing usable (RESULT_CANCELED + null intent) — cannot be supported here.
 */
class ExternalPlayerResultContract : ActivityResultContract<ExternalPlayerInput, ExternalPlayerResult?>() {

    override fun createIntent(context: Context, input: ExternalPlayerInput): Intent {
        // Do NOT add FLAG_ACTIVITY_NEW_TASK — it prevents receiving the result.
        return ExternalPlayerIntents.build(input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ExternalPlayerResult? {
        // Some players return RESULT_OK, others return RESULT_CANCELED even on normal exit.
        // We try to parse position regardless of resultCode.
        android.util.Log.d("ExtPlayerContract", "parseResult: resultCode=$resultCode, intent=$intent, extras=${intent?.extras?.keySet()?.toList()}")
        val data = intent ?: return null

        val position = parsePosition(data)
        val duration = parseDuration(data)
        // MX Player / Just Player / mpvNova report the end reason here (vanilla mpv-android and
        // VLC do not).
        val endBy = data.getStringExtra("end_by")
        val completedByEndReason = endBy == "playback_completion"

        // Vanilla mpv-android (is.xyz.mpv) includes a position only on a back-press; at EOF it
        // returns its result action with RESULT_OK and NO extras (no position/duration/end_by).
        // Treat that exact shape as a completion so it still auto-advances + marks watched.
        // mpvNova (the fork) sends full data and never hits this branch.
        // WARNING: heuristic — if a future mpv-android build returned this shape for a non-EOF
        // exit, it would be mis-counted as completed.
        val mpvFinishedWithNoData = resultCode == android.app.Activity.RESULT_OK &&
            data.action == "is.xyz.mpv.MPVActivity.result" &&
            position == null && duration == null && endBy == null

        // Players that signal completion via end_by often reset the returned position to 0 at
        // EOF — don't discard those, or the episode never marks watched / auto-advances. Only
        // bail when there is genuinely nothing to act on: no position AND no completion signal.
        if (position == null && !completedByEndReason && !mpvFinishedWithNoData) {
            android.util.Log.d("ExtPlayerContract", "parseResult: no position and no completion signal; dropping")
            return null
        }

        // On a bare completion signal with no/zero position, report the end position so the
        // 90%-of-duration completion check passes and progress is saved as watched (not 0%).
        val effectivePosition = position ?: duration ?: 0L
        // Only an explicit "playback_completion" or the mpv EOF shape counts as a natural end.
        // Absent end_by (VLC/Vimu) stays endedByUser=true and relies on the 90% rule, so a
        // genuine mid-video user exit is NOT treated as a completion.
        val endedByUser = !mpvFinishedWithNoData && endBy != "playback_completion"

        android.util.Log.d(
            "ExtPlayerContract",
            "parseResult parsed position=${effectivePosition}ms, duration=${duration}ms, " +
                "end_by=$endBy; endedByUser=$endedByUser"
        )

        return ExternalPlayerResult(
            positionMs = effectivePosition,
            durationMs = duration,
            endedByUser = endedByUser
        )
    }

    // Robust against key + type variants across players:
    // - VLC: extra_position (Long)
    // - MX Player / Just Player / mpv-android / Nova: position (Int)
    private fun parsePosition(data: Intent): Long? =
        firstPositiveExtra(data, "extra_position", "position")

    private fun parseDuration(data: Intent): Long? =
        firstPositiveExtra(data, "extra_duration", "duration")

    /** Reads the first key that holds a positive Long or Int value, trying both types. */
    private fun firstPositiveExtra(data: Intent, vararg keys: String): Long? {
        for (key in keys) {
            val asLong = data.getLongExtra(key, -1L)
            if (asLong > 0) return asLong
            val asInt = data.getIntExtra(key, -1)
            if (asInt > 0) return asInt.toLong()
        }
        return null
    }
}
