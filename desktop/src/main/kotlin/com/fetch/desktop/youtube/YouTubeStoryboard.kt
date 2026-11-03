package com.fetch.desktop.youtube

/**
 * A video's storyboard (the TV's `data/youtube/YouTubeStoryboard.kt`): sheets of small frames YouTube makes for its seek bar previews.
 *
 * The player response gives it as one spec string, `baseUrl|level|level…`, where the base URL has
 * `$L` for the level and `$N` for the sheet's name, and each level is
 * `width#height#count#columns#rows#intervalMs#name#sigh`. A name holding `$M` is a sheet number;
 * each sheet holds `columns × rows` frames, left to right then top to bottom.
 */
data class YouTubeStoryboard(
    private val urlTemplate: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameCount: Int,
    val columns: Int,
    val rows: Int,
    /** Time between frames; 0 when YouTube leaves it to the video's length. */
    val intervalMs: Long
) {
    /** One frame: the sheet it's on and where in the sheet, in pixels. */
    data class Frame(val sheetUrl: String, val x: Int, val y: Int, val width: Int, val height: Int)

    /** The frame nearest [positionMs], or null when there's nothing to show. */
    fun frameAt(positionMs: Long, durationMs: Long): Frame? {
        if (frameCount <= 0 || columns <= 0 || rows <= 0) return null
        val interval = when {
            intervalMs > 0 -> intervalMs
            durationMs > 0 -> durationMs / frameCount
            else -> return null
        }.coerceAtLeast(1L)
        val index = (positionMs.coerceAtLeast(0L) / interval).toInt().coerceIn(0, frameCount - 1)
        val perSheet = columns * rows
        val sheet = index / perSheet
        val inSheet = index % perSheet
        return Frame(
            sheetUrl = urlTemplate.replace("\$M", sheet.toString()),
            x = (inSheet % columns) * frameWidth,
            y = (inSheet / columns) * frameHeight,
            width = frameWidth,
            height = frameHeight
        )
    }

    companion object {
        /** The level with the widest frames, or null when the spec can't be read. */
        fun parse(spec: String?): YouTubeStoryboard? {
            val parts = spec?.split('|')?.takeIf { it.size >= 2 } ?: return null
            val base = parts[0].takeIf { it.startsWith("http") } ?: return null
            return parts.drop(1).mapIndexedNotNull { level, part ->
                val fields = part.split('#')
                if (fields.size < 8) return@mapIndexedNotNull null
                val width = fields[0].toIntOrNull() ?: return@mapIndexedNotNull null
                val height = fields[1].toIntOrNull() ?: return@mapIndexedNotNull null
                val count = fields[2].toIntOrNull() ?: return@mapIndexedNotNull null
                val columns = fields[3].toIntOrNull() ?: return@mapIndexedNotNull null
                val rows = fields[4].toIntOrNull() ?: return@mapIndexedNotNull null
                val interval = fields[5].toLongOrNull() ?: return@mapIndexedNotNull null
                val name = fields[6]
                val sigh = fields[7]
                if (width <= 0 || height <= 0 || count <= 0 || columns <= 0 || rows <= 0) return@mapIndexedNotNull null
                // The lowest level is one picture of the whole video rather than a sheet per page.
                if (name == "default") return@mapIndexedNotNull null
                val url = base.replace("\$L", level.toString()).replace("\$N", name) +
                    (if (sigh.isNotEmpty()) "&sigh=$sigh" else "")
                YouTubeStoryboard(url, width, height, count, columns, rows, interval)
            }.maxByOrNull { it.frameWidth }
        }
    }
}
