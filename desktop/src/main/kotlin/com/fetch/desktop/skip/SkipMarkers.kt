package com.fetch.desktop.skip

/** The title a marker you record goes to on IntroDB; [season]/[episode] are null for a movie. */
data class MarkerTarget(val imdbId: String, val season: Int?, val episode: Int?) {
    val isMovie: Boolean get() = season == null || episode == null
}

/**
 * The TV's marker submitting and "wrong marker" reports (PlayerRuntimeControllerSkipMarkers,
 * SkipIntroRepository): with an IntroDB API key, the player's Mark button records a segment's start
 * and then its end and sends it to IntroDB; an AniSkip marker can be reported as wrong.
 */
object SkipMarkerRules {
    private val IMDB_ID = Regex("tt[0-9]+")

    fun isImdbId(id: String?): Boolean = id != null && id.matches(IMDB_ID)

    /** What the Mark button records: an episode's intro, a movie's end credits (as on TV). */
    fun markedType(isMovie: Boolean): SegmentType = if (isMovie) SegmentType.MOVIE_CREDITS else SegmentType.INTRO

    /** IntroDB's `segment_type` for [type], or null when IntroDB has none for it (the TV's introDbSubmissionType). */
    fun submissionType(type: SegmentType, isMovie: Boolean): String? = when (type) {
        SegmentType.INTRO -> if (isMovie) null else "intro"
        SegmentType.RECAP -> if (isMovie) null else "recap"
        SegmentType.OUTRO, SegmentType.MOVIE_CREDITS -> "outro"
        SegmentType.POST_CREDITS -> if (isMovie) "post-credits" else null
    }

    /** Only AniSkip takes reports, by the id it gave the segment. */
    fun canReport(segment: Segment): Boolean = segment.provider == AniSkipClient.PROVIDER && !segment.sourceId.isNullOrBlank()

    /** The Mark button's label: "Mark intro start", then "Mark intro end" once the start is set. */
    fun markLabel(isMovie: Boolean, marking: Boolean): String {
        val what = if (isMovie) "credits" else "intro"
        return if (marking) "Mark $what end" else "Mark $what start"
    }

    /** What a press at [positionSec] does, given the start already recorded (or null). */
    sealed interface Press {
        data class Start(val atSec: Double) : Press
        data class Submit(val startSec: Double, val endSec: Double) : Press
    }

    /** As on TV: the first press (or one at or before the recorded start) records the start; the next sends it. */
    fun press(recordedStartSec: Double?, positionSec: Double): Press =
        if (recordedStartSec == null || positionSec <= recordedStartSec) Press.Start(positionSec)
        else Press.Submit(recordedStartSec, positionSec)
}
