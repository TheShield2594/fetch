package com.fetch.desktop.player.autosync

import com.fetch.desktop.AddonSubtitle
import com.fetch.desktop.player.SubtitleOrganisation
import com.fetch.desktop.player.embedded.MpvTrack

/** The video's own subtitle track Auto Sync compares with. */
data class ReferenceTrack(
    val track: MpvTrack,
    /** A bitmap track (PGS, VobSub): its timings are read from the file, as mpv can't show it hidden. */
    val image: Boolean,
    /** Its place among the file's own subtitle tracks (ffprobe's `s:N`). */
    val ordinal: Int
)

/** Which tracks and subtitles Auto Sync works with. */
object AutoSyncSources {
    /** Text subtitle codecs mpv can show as a (hidden) secondary track. */
    val TextCodecs = setOf("subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text", "microdvd")

    /** Bitmap subtitle codecs whose timings can be read from the file. */
    val ImageCodecs = setOf("hdmv_pgs_subtitle", "pgssub", "pgs", "dvd_subtitle", "dvdsub", "vobsub", "dvb_subtitle", "dvbsub", "xsub")

    /** How many other subtitles in the language are tried when the chosen one won't sync (the TV's). */
    const val MAX_ALTERNATIVES = 8

    /**
     * The track to compare with: a text track mpv can show hidden, else an image track (as the
     * TV uses PGS and VobSub timings). Within each, one that isn't forced (forced tracks hold a
     * few lines), in [language] first; then a forced one as a last resort.
     */
    fun reference(tracks: List<MpvTrack>, language: String?): ReferenceTrack? {
        val own = tracks.filter { it.type == "sub" && !it.external }.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
        val key = language?.let { SubtitleOrganisation.languageKey(it) }
        fun pick(codecs: Set<String>): MpvTrack? {
            val usable = own.filter { it.codec?.lowercase() in codecs }
            val full = usable.filter { !it.forced }
            return full.firstOrNull { key != null && SubtitleOrganisation.languageKey(it.lang, it.title) == key }
                ?: full.firstOrNull()
                ?: usable.firstOrNull()
        }
        val text = pick(TextCodecs)
        val track = text ?: pick(ImageCodecs) ?: return null
        return ReferenceTrack(track, image = text == null, ordinal = own.indexOf(track))
    }

    /**
     * The other addon subtitles in [language] to try when the one at [selectedUrl] won't sync,
     * in the addons' order, at most [MAX_ALTERNATIVES].
     */
    fun alternatives(selectedUrl: String, language: String?, subtitles: List<AddonSubtitle>): List<AddonSubtitle> {
        val key = SubtitleOrganisation.languageKey(language)
        if (key == SubtitleOrganisation.UNKNOWN) return emptyList()
        return subtitles.distinctBy { it.url }
            .filter { it.url != selectedUrl && SubtitleOrganisation.languageKey(it.language) == key }
            .take(MAX_ALTERNATIVES)
    }

    /** mpv's `http-header-fields` ("Name: value,Name: value") as a map; commas inside values stay. */
    fun headers(fields: String?, userAgent: String?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fields?.split(Regex(""",(?=\s*[A-Za-z0-9-]+\s*:)"""))?.forEach { field ->
            val name = field.substringBefore(':', "").trim()
            if (name.isNotEmpty()) out[name] = field.substringAfter(':').trim()
        }
        userAgent?.takeIf { it.isNotBlank() }?.let { ua -> if (out.keys.none { it.equals("User-Agent", true) }) out["User-Agent"] = ua }
        return out
    }

    /** "re-timed, +1.25 s to +4.80 s" or "+1.25 s": what was done, for the on-screen message. */
    fun describe(map: TimeMap, target: List<Cue>): String {
        if (map.isConstantShift) return AutoSyncAligner.label(map.offsetMs.toLong())
        val first = target.minOfOrNull { it.startMs } ?: 0
        val last = target.maxOfOrNull { it.endMs } ?: 0
        val a = map.shiftAt(first)
        val b = map.shiftAt(last)
        return if (kotlin.math.abs(a - b) < 50) "re-timed, ${AutoSyncAligner.label(a)}"
        else "re-timed, ${AutoSyncAligner.label(a)} to ${AutoSyncAligner.label(b)}"
    }
}
