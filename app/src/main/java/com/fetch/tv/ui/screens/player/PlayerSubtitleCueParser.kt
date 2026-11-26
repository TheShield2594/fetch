package com.fetch.tv.ui.screens.player

internal object PlayerSubtitleCueParser {
    private val timestampRegex = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})([.,](\d+))?""")

    fun parseFromText(rawText: String, sourceUrl: String): List<SubtitleSyncCue> {
        val cleanedText = rawText
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        return when {
            looksLikeAss(cleanedText, sourceUrl) -> parseAss(cleanedText)
            looksLikeVtt(cleanedText, sourceUrl) -> parseVtt(cleanedText)
            else -> parseSrt(cleanedText)
        }
    }

    private val assEventsHeaderRegex = Regex("""(?im)^\[Events]\s*$""")
    private val assDialogueRegex = Regex("""(?im)^Dialogue:""")

    /** ASS override blocks such as `{\an8}` or `{\i1}`, which some SRT files carry too. */
    private val assOverrideTagRegex = Regex("""\{\\[^}]*\}""")

    private val defaultAssEventFormat = listOf(
        "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"
    )

    private fun looksLikeAss(text: String, sourceUrl: String): Boolean {
        val normalizedUrl = sourceUrl.substringBefore('?').substringBefore('#').lowercase()
        if (normalizedUrl.endsWith(".ass") || normalizedUrl.endsWith(".ssa")) return true
        if (text.trimStart().startsWith("[Script Info]", ignoreCase = true)) return true
        return assEventsHeaderRegex.containsMatchIn(text) && assDialogueRegex.containsMatchIn(text)
    }

    /**
     * ASS/SSA: reads the `[Events]` section's `Format:` line for the Start, End and Text columns
     * (falling back to the standard layout) and turns each `Dialogue:` line into a cue.
     */
    private fun parseAss(text: String): List<SubtitleSyncCue> {
        var inEvents = false
        var format = defaultAssEventFormat
        val cues = mutableListOf<SubtitleSyncCue>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val startIndex = format.indexOf("start")
            val endIndex = format.indexOf("end")
            val textIndex = format.indexOf("text")
            // Text is the last column and may itself contain commas.
            if (startIndex < 0 || endIndex < 0 || textIndex != format.lastIndex) continue
            val fields = line.substringAfter(':').split(',', limit = format.size)
            if (fields.size < format.size) continue
            val startTimeMs = parseTimestampMs(fields[startIndex].trim()) ?: continue
            val endTimeMs = parseTimestampMs(fields[endIndex].trim()) ?: continue
            if (endTimeMs - startTimeMs <= 0) continue
            val cueText = normalizeCueText(
                stripAssDrawings(fields[textIndex])
                    .replace("\\N", "\n")
                    .replace("\\n", "\n")
                    .replace("\\h", " ")
            )
            if (cueText.isBlank()) continue
            cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
        }
        return cues.sortedBy { it.startTimeMs }
    }

    private val assOverrideBlockRegex = Regex("""\{[^}]*\}""")
    private val assDrawingTagRegex = Regex("""\\p(\d+)""")

    /**
     * Drops ASS drawing commands: text after a `\p` with a positive scale, up to `\p0`, is vector
     * shapes (`m 0 0 l 100 100`), not words. Override blocks are kept for the later tag strip.
     */
    private fun stripAssDrawings(text: String): String {
        if (!text.contains("\\p")) return text
        val out = StringBuilder()
        var drawing = false
        var cursor = 0
        for (block in assOverrideBlockRegex.findAll(text)) {
            if (!drawing) out.append(text, cursor, block.range.first)
            out.append(block.value)
            assDrawingTagRegex.findAll(block.value).lastOrNull()?.let { tag ->
                drawing = (tag.groupValues[1].toIntOrNull() ?: 0) > 0
            }
            cursor = block.range.last + 1
        }
        if (!drawing) out.append(text, cursor, text.length)
        return out.toString()
    }

    private fun looksLikeVtt(text: String, sourceUrl: String): Boolean {
        val normalizedUrl = sourceUrl.substringBefore('?').substringBefore('#').lowercase()
        if (normalizedUrl.endsWith(".vtt") || normalizedUrl.endsWith(".webvtt")) return true
        return text.trimStart().startsWith("WEBVTT")
    }

    private fun parseSrt(text: String): List<SubtitleSyncCue> {
        val blocks = text.split(Regex("""\n\s*\n"""))
        val cues = mutableListOf<SubtitleSyncCue>()
        for (block in blocks) {
            val lines = block
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var index = 0
            if (lines[index].all { it.isDigit() } && index + 1 < lines.size) {
                index++
            }
            val timing = lines.getOrNull(index) ?: continue
            if (!timing.contains("-->")) continue
            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timing) ?: continue
            // Skip zero-duration cues that cause desync after scene transitions (#2757)
            if (endTimeMs - startTimeMs <= 0) continue
            val textLines = lines.drop(index + 1)
            val cueText = normalizeCueText(textLines.joinToString("\n"))
            if (cueText.isBlank()) continue
            cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
        }
        return cues
    }

    private fun parseVtt(text: String): List<SubtitleSyncCue> {
        val lines = text
            .lines()
            .map { it.trimEnd() }

        val cues = mutableListOf<SubtitleSyncCue>()
        var cursor = 0

        while (cursor < lines.size) {
            val line = lines[cursor].trim()
            if (line.isBlank()) {
                cursor++
                continue
            }
            if (line.startsWith("WEBVTT")) {
                cursor++
                continue
            }
            // Only skip STYLE/REGION/NOTE when the next line is not cue timings.
            if (isWebVttMetadataBlockHeader(line)) {
                val nextLine = lines.getOrNull(cursor + 1)?.trim().orEmpty()
                if (nextLine.isEmpty() || !nextLine.contains("-->")) {
                    cursor = skipWebVttBlock(lines, cursor + 1)
                    continue
                }
            }

            var timingLine = line
            var textStart = cursor + 1
            if (!timingLine.contains("-->")) {
                timingLine = lines.getOrNull(cursor + 1)?.trim().orEmpty()
                textStart = cursor + 2
            }
            if (!timingLine.contains("-->")) {
                cursor++
                continue
            }

            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timingLine) ?: run {
                cursor++
                continue
            }
            // Skip zero-duration cues that cause desync after scene transitions (#2757)
            if (endTimeMs - startTimeMs <= 0) {
                cursor++
                continue
            }

            val textParts = mutableListOf<String>()
            var i = textStart
            while (i < lines.size && lines[i].isNotBlank()) {
                textParts += lines[i].trim()
                i++
            }
            val cueText = normalizeCueText(textParts.joinToString("\n"))
            if (cueText.isNotBlank()) {
                cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
            }
            cursor = i + 1
        }

        return cues
    }

    private fun isWebVttMetadataBlockHeader(line: String): Boolean {
        return line == "STYLE" ||
            line == "REGION" ||
            line == "NOTE" ||
            line.startsWith("NOTE ") ||
            line.startsWith("NOTE\t")
    }

    private fun skipWebVttBlock(lines: List<String>, start: Int): Int {
        var cursor = start
        while (cursor < lines.size && lines[cursor].isNotBlank()) {
            cursor++
        }
        return if (cursor < lines.size) cursor + 1 else cursor
    }

    private fun parseStartEndTimeMs(timingLine: String): Pair<Long, Long>? {
        val parts = timingLine.split("-->")
        if (parts.size != 2) return null
        val startTimeMs = parseTimestampMs(parts[0].trim().substringBefore(' ')) ?: return null
        val endTimeMs = parseTimestampMs(parts[1].trim().substringBefore(' ')) ?: return null
        return startTimeMs to endTimeMs
    }

    private fun parseTimestampMs(rawTimestamp: String): Long? {
        val match = timestampRegex.matchEntire(rawTimestamp.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millisRaw = match.groupValues[5]
        val millis = when (millisRaw.length) {
            0 -> 0L
            1 -> "${millisRaw}00".toLong()
            2 -> "${millisRaw}0".toLong()
            else -> millisRaw.take(3).toLongOrNull() ?: 0L
        }
        return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis
    }

    private fun normalizeCueText(text: String): String {
        return SubtitleMojibakeSanitizer.sanitize(
            text
                .replace(assOverrideTagRegex, "")
                .replace(Regex("""<(?:\d+:)?\d{1,2}:\d{2}(?:[.,]\d+)?>"""), "")
                .replace(Regex("""</?[a-zA-Z0-9._-]+(?: [^>]*)?>"""), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .lines()
                .map { it.replace(Regex("""[ \t]+"""), " ").trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        ).toString()
    }
}
