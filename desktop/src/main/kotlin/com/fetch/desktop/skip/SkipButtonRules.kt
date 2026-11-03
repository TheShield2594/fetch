package com.fetch.desktop.skip

/**
 * The TV's Skip button rules (SkipIntroButton, SkipIntroVisibilityRules): which segment it's
 * for, what it says, where it goes and how long it stays up.
 */
object SkipButtonRules {
    /** The button's shortest time on screen when left alone, in ms. */
    const val AUTO_HIDE_MS = 10_000

    /** Its longest: long credits must not keep it up for minutes. */
    const val MAX_AUTO_HIDE_MS = 120_000

    /** More than this after an episode's credits counts as a scene after them (the TV's 5 s). */
    const val POST_OUTRO_GAP_SEC = 5.0

    /** The segment playing at [position] that the button is for: never a post-credits scene. */
    fun active(segments: List<Segment>, position: Double): Segment? =
        segments.firstOrNull { it.type != SegmentType.POST_CREDITS && position >= it.startSec && position < it.endSec }

    /**
     * The post-credits scene skipping [segment] (credits) lands on: IntroDB's own after it, or,
     * when the file goes on more than five seconds after the credits, what's left. Null otherwise.
     */
    fun followingPostCredits(segment: Segment, segments: List<Segment>, duration: Double): Segment? {
        if (segment.type != SegmentType.MOVIE_CREDITS && segment.type != SegmentType.OUTRO) return null
        val explicit = segments.filter {
            it.type == SegmentType.POST_CREDITS && it.startSec >= segment.endSec && it.endSec > it.startSec &&
                (duration <= 0 || it.startSec < duration)
        }.minByOrNull { it.startSec }
        if (explicit != null) return explicit
        if (duration > 0 && segment.endSec != Segment.OPEN_END && duration - segment.endSec > POST_OUTRO_GAP_SEC) {
            return Segment(SegmentType.POST_CREDITS, segment.endSec, duration, provider = "heuristic")
        }
        return null
    }

    /** Where Skip goes: the post-credits scene's start when there is one, else the segment's end. */
    fun target(segment: Segment, segments: List<Segment>, duration: Double): Double {
        val scene = followingPostCredits(segment, segments, duration)
        val to = scene?.startSec ?: segment.targetSec
        return if (duration > 0) to.coerceAtMost(duration) else to
    }

    /** The TV's wording: Skip Intro, Skip Recap, Skip Ending, Skip Credits, Skip to Post-Credits. */
    fun label(segment: Segment, targetsPostCredits: Boolean): String {
        segment.label?.let { return "Skip $it" }
        return when (segment.type) {
            SegmentType.INTRO -> "Skip Intro"
            SegmentType.RECAP -> "Skip Recap"
            SegmentType.OUTRO -> if (targetsPostCredits) "Skip to Post-Credits" else "Skip Ending"
            SegmentType.MOVIE_CREDITS -> if (targetsPostCredits) "Skip to Post-Credits" else "Skip Credits"
            SegmentType.POST_CREDITS -> "Skip"
        }
    }

    /**
     * Shown while a segment plays, unless dismissed (then only with the controls), and after its
     * countdown ran out only while the controls are up.
     */
    fun visible(hasActive: Boolean, dismissed: Boolean, controlsVisible: Boolean, autoHidden: Boolean): Boolean =
        hasActive && (!dismissed || controlsVisible) && (!autoHidden || controlsVisible)

    /** How long the button stays up when left alone: the segment's length, between 10 s and 2 minutes. */
    fun autoHideMs(segment: Segment?): Int {
        if (segment == null) return AUTO_HIDE_MS
        val length = segment.endSec - segment.startSec
        if (!length.isFinite() || length <= 0 || segment.endSec == Segment.OPEN_END) return AUTO_HIDE_MS
        return (length * 1000).coerceAtMost(MAX_AUTO_HIDE_MS.toDouble()).toInt().coerceAtLeast(AUTO_HIDE_MS)
    }
}
