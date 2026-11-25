package com.fetch.tv.core.player

import com.fetch.tv.data.repository.SkipInterval

/**
 * One chapter of the playing title. [endMs] is null when the source gives no end; [ChapterRules.normalize]
 * fills it from the next chapter's start or the title's length.
 */
data class Chapter(
    val startMs: Long,
    val endMs: Long? = null,
    val title: String? = null
)

/** Chapter navigation, seek-bar ticks and chapter-named skip segments. Pure logic, no player state. */
object ChapterRules {

    /** The provider name on skip segments made from chapter names. */
    const val SKIP_PROVIDER = "chapters"

    /** Within this much of a chapter's start, Previous goes to the chapter before rather than back to this one's start. */
    const val RESTART_WINDOW_MS = 3_000L

    /** A seek lands a few frames either side of a chapter start; positions this close count as in it. */
    private const val POSITION_TOLERANCE_MS = 1_000L

    private const val MAX_INTRO_MS = 5L * 60_000L
    private const val MAX_RECAP_MS = 5L * 60_000L
    private const val MIN_SEGMENT_MS = 5_000L

    /**
     * Sorted, de-duplicated chapters with blank titles dropped to null and every end filled in where it
     * can be: from the next chapter's start, else [durationMs] when known. Chapters starting at or past a
     * known [durationMs] are dropped.
     */
    fun normalize(chapters: List<Chapter>, durationMs: Long = 0L): List<Chapter> {
        if (chapters.isEmpty()) return emptyList()
        val sorted = chapters
            .asSequence()
            .filter { it.startMs >= 0L && (durationMs <= 0L || it.startMs < durationMs) }
            .map { it.copy(title = it.title?.trim()?.takeIf(String::isNotEmpty)) }
            .sortedBy { it.startMs }
            .toList()
        val distinct = ArrayList<Chapter>(sorted.size)
        for (chapter in sorted) {
            val last = distinct.lastOrNull()
            if (last != null && last.startMs == chapter.startMs) {
                if (last.title == null && chapter.title != null) distinct[distinct.size - 1] = last.copy(title = chapter.title)
                continue
            }
            distinct += chapter
        }
        return distinct.mapIndexed { index, chapter ->
            val nextStart = distinct.getOrNull(index + 1)?.startMs
            val limit = nextStart ?: durationMs.takeIf { it > 0L }
            val explicitEnd = chapter.endMs?.takeIf { it > chapter.startMs && (limit == null || it <= limit) }
            chapter.copy(endMs = explicitEnd ?: limit)
        }
    }

    /** Index of the chapter playing at [positionMs], or -1 before the first chapter. */
    fun indexAt(chapters: List<Chapter>, positionMs: Long): Int =
        chapters.indexOfLast { it.startMs <= positionMs + POSITION_TOLERANCE_MS }

    /** The chapter playing at [positionMs], or null. */
    fun chapterAt(chapters: List<Chapter>, positionMs: Long): Chapter? = chapters.getOrNull(indexAt(chapters, positionMs))

    /**
     * Where Previous chapter goes: back to the start of the playing chapter, or to the one before when
     * playback is within [RESTART_WINDOW_MS] of this one's start. Null when there are no chapters.
     */
    fun previousTargetMs(chapters: List<Chapter>, positionMs: Long): Long? {
        if (chapters.isEmpty()) return null
        val index = indexAt(chapters, positionMs)
        if (index < 0) return 0L
        val current = chapters[index]
        return when {
            positionMs - current.startMs > RESTART_WINDOW_MS -> current.startMs
            index > 0 -> chapters[index - 1].startMs
            else -> current.startMs
        }
    }

    /** Where Next chapter goes: the start of the next chapter after [positionMs], or null in the last one. */
    fun nextTargetMs(chapters: List<Chapter>, positionMs: Long): Long? =
        chapters.firstOrNull { it.startMs > positionMs + POSITION_TOLERANCE_MS }?.startMs

    /** Chapter starts as fractions of [durationMs] for seek-bar ticks; the start at 0 gets no tick. */
    fun tickFractions(chapters: List<Chapter>, durationMs: Long): List<Float> {
        if (durationMs <= 0L || chapters.size < 2) return emptyList()
        return chapters
            .asSequence()
            .filter { it.startMs > 0L && it.startMs < durationMs }
            .map { (it.startMs.toDouble() / durationMs).toFloat() }
            .toList()
    }

    /** What a chapter name says the chapter is, when it names a skippable segment. */
    enum class SegmentKind { INTRO, RECAP, CREDITS }

    private val NUMBER_PREFIX = Regex("^(chapter|kapitel|poglavje|ch\\.?)\\s*\\d+\\s*[:.\\-–—)]*\\s*|^\\d+\\s*[:.\\-–—)]+\\s*")
    private val SEPARATORS = Regex("[\\s_\\-–—:./]+")
    private val SONG_NUMBER = Regex("^(op|ed)\\s*\\d*$")

    private val INTRO_NAMES = setOf(
        "intro", "opening", "opening credits", "opening titles", "opening theme", "opening song",
        "title sequence", "main title", "main titles", "theme song", "op", "avant title"
    )
    private val RECAP_NAMES = setOf("recap", "previously", "previously on", "summary of previous episode")
    private val CREDITS_NAMES = setOf(
        "credits", "end credits", "ending credits", "closing credits", "end titles", "outro",
        "ending theme", "ending song", "ed"
    )

    /**
     * Matches the whole chapter name, after a leading "Chapter 3 -" or "03." is removed, so a story
     * chapter such as "Intro to the Murder" is not taken for an intro. "Ending" counts only for
     * episodes: in a film it is as often the story's last act.
     */
    fun segmentKind(title: String?, isMovie: Boolean): SegmentKind? {
        val raw = title?.trim()?.lowercase() ?: return null
        val name = raw
            .replace(NUMBER_PREFIX, "")
            .replace(SEPARATORS, " ")
            .trim()
            .trimEnd('!', '?', ')', '(')
            .trim()
        if (name.isEmpty()) return null
        val songMatch = SONG_NUMBER.matchEntire(name)
        return when {
            songMatch != null -> if (name.startsWith("op")) SegmentKind.INTRO else SegmentKind.CREDITS
            name in INTRO_NAMES -> SegmentKind.INTRO
            name in RECAP_NAMES || name.startsWith("previously on ") -> SegmentKind.RECAP
            name in CREDITS_NAMES -> SegmentKind.CREDITS
            name == "ending" && !isMovie -> SegmentKind.CREDITS
            else -> null
        }
    }

    /**
     * Skip segments from chapters named like an intro, recap or credits, for titles the skip services
     * have nothing for. [chapters] must be normalized. Intros and recaps must start in the first half
     * and run at most five minutes; a credits chapter in the first half is taken as opening credits.
     * Credits map to "movie-credits" for films and "outro" for episodes, the types the skip services use.
     */
    fun skipIntervals(chapters: List<Chapter>, durationMs: Long, isMovie: Boolean): List<SkipInterval> {
        if (durationMs <= 0L || chapters.isEmpty()) return emptyList()
        val half = durationMs / 2
        return chapters.mapNotNull { chapter ->
            val kind = segmentKind(chapter.title, isMovie) ?: return@mapNotNull null
            val end = (chapter.endMs ?: durationMs).coerceAtMost(durationMs)
            val length = end - chapter.startMs
            if (length < MIN_SEGMENT_MS) return@mapNotNull null
            val inFirstHalf = chapter.startMs < half
            val type = when (kind) {
                SegmentKind.INTRO -> if (inFirstHalf && length <= MAX_INTRO_MS) "intro" else null
                SegmentKind.RECAP -> if (inFirstHalf && length <= MAX_RECAP_MS) "recap" else null
                SegmentKind.CREDITS -> when {
                    !inFirstHalf -> if (isMovie) "movie-credits" else "outro"
                    length <= MAX_INTRO_MS -> "intro"
                    else -> null
                }
            } ?: return@mapNotNull null
            SkipInterval(
                startTime = chapter.startMs / 1_000.0,
                endTime = end / 1_000.0,
                type = type,
                provider = SKIP_PROVIDER
            )
        }
    }
}
