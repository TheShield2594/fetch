package com.fetch.tv.core.player

import android.content.ClipData
import android.content.Intent
import androidx.core.net.toUri

/**
 * Builds the ACTION_VIEW intent handed to external video players. Shared by
 * [ExternalPlayerResultContract] (result-returning launch) and [ExternalPlayerLauncher]
 * (fire-and-forget fallback) so the two paths cannot drift apart.
 */
internal object ExternalPlayerIntents {

    fun build(input: ExternalPlayerInput): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(input.url.toUri(), "video/*")

        input.title?.let {
            putExtra("title", it)
            putExtra(Intent.EXTRA_TITLE, it)
            putExtra("forcename", it) // Vimu Player
        }

        headersExtra(input.headers)?.let { putExtra("headers", it) }

        // Resume position — supported by MX Player, VLC, Just Player, mpv-android, Vimu
        if (input.startFromBeginning) {
            putExtra("position", 0)
            putExtra("extra_position", 0L)
            putExtra("startfrom", 0)
            putExtra("forceresume", false)
            putExtra("from_start", true)
        } else if (input.resumePositionMs > 0L) {
            putExtra("position", input.resumePositionMs.toInt())  // MX Player / Just Player / mpv (Int ms)
            putExtra("extra_position", input.resumePositionMs)    // VLC (Long ms)
            putExtra("startfrom", input.resumePositionMs.toInt()) // Vimu Player (Int ms)
            putExtra("forceresume", true)                         // Vimu: enable resume for network streams
            putExtra("from_start", false)                         // VLC: don't force start from beginning
        }

        // Request that the player returns result with position/duration.
        // Required by MX Player; harmless for other players.
        putExtra("return_result", true)

        // Pre-resolved intro/outro skip segments (mpvNova reads this; other players ignore it).
        input.skipSegmentsJson?.let { putExtra("skip_segments", it) }

        val subs = input.subtitles
        if (!subs.isNullOrEmpty()) {
            val subtitleUris = subs.map { it.url.toUri() }.toTypedArray()
            val subtitleNames = subs.map { it.name }.toTypedArray()
            val subtitleFilenames = subtitleFilenames(subs).toTypedArray()

            // Grant read permission for content:// URIs via ClipData.
            // FLAG_GRANT_READ_URI_PERMISSION only covers intent.data, not extras.
            // Adding all subtitle URIs to ClipData ensures the receiving player
            // gets read access to all of them.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clipData = ClipData(
                "subtitles",
                arrayOf("application/x-subrip", "text/vtt"),
                ClipData.Item(subtitleUris.first())
            )
            subtitleUris.drop(1).forEach { uri -> clipData.addItem(ClipData.Item(uri)) }
            setClipData(clipData)

            // MX Player / mpv-android / Nova. The first entry is the preferred language
            // (see SubtitleFileCache.prioritize), so it is the one enabled by default.
            putExtra("subs", subtitleUris)
            putExtra("subs.name", subtitleNames)
            putExtra("subs.filename", subtitleFilenames)
            putExtra("subs.enable", arrayOf(subtitleUris.first()))

            // Just Player
            putExtra("subtitle_uri", subtitleUris)
            putExtra("subtitle_name", subtitleNames)

            // VLC (single subtitle — use first one)
            putExtra("subtitles_location", subtitleUris.first())

            // Vimu Player
            putExtra("forcedsrt", subs.first().url)
        }
    }

    /**
     * Headers in the MX Player intent API format, which Just Player and mpv-android follow:
     * a flat `String[]` of alternating names and values (`[k1, v1, k2, v2]`). Returns null
     * when there is nothing to send.
     */
    fun headersExtra(headers: Map<String, String>?): Array<String>? {
        if (headers.isNullOrEmpty()) return null
        return headers.entries.flatMap { listOf(it.key, it.value) }.toTypedArray()
    }

    /**
     * Distinct display filenames for the subtitle list. Players that key subtitles by filename
     * would otherwise merge entries sharing a language and name.
     */
    fun subtitleFilenames(subs: List<SubtitleInput>): List<String> =
        subs.mapIndexed { index, sub ->
            val ext = sub.url.substringBefore('?').substringBefore('#')
                .substringAfterLast('/').substringAfterLast('.', "")
                .lowercase().takeIf { it in SUBTITLE_EXTENSIONS } ?: "srt"
            "${index + 1}_${sub.lang}_${sub.name}.$ext"
        }

    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")
}
