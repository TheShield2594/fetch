package com.fetch.tv.core.player

/**
 * Reads the Matroska Chapters element with at most three small range reads, reusing
 * [MatroskaAfrProbe]'s EBML reader. Media3 does not expose MKV chapters, so ExoPlayer playback
 * of a direct MKV link gets them from here.
 *
 * 1. The head ([HEAD_BYTES]): Chapters is usually written there, before the first Cluster.
 * 2. Otherwise the SeekHead gives Chapters' position: peek its header, then fetch it whole
 *    when it is no larger than [MAX_CHAPTERS_BYTES].
 *
 * Never reads the rest of the file.
 */
internal object MatroskaChapterReader {

    const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_EDITION_FLAG_HIDDEN = 0x45BDL
    private const val ID_EDITION_FLAG_DEFAULT = 0x45DBL
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_TIME_END = 0x92L
    private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_FLAG_ENABLED = 0x4598L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    const val HEAD_BYTES = 512 * 1024
    const val MAX_CHAPTERS_BYTES = 1024 * 1024
    private const val PEEK_BYTES = 16

    private const val NS_PER_MS = 1_000_000L

    /**
     * Reads `length` bytes at `offset` of the remote file, or fewer at its end. Null when the
     * read fails or the server ignores the range.
     */
    fun interface RangeReader {
        fun read(offset: Long, length: Int): ByteArray?
    }

    /** The chapters of the file behind [reader]; empty when it has none or is not Matroska. */
    fun read(reader: RangeReader): List<Chapter> {
        val head = reader.read(0L, HEAD_BYTES) ?: return emptyList()
        return when (val location = locateChapters(head)) {
            is Location.InHead -> parseChapters(head, location.element)
            is Location.At -> fetchAndParse(reader, location.absoluteOffset)
            Location.None -> emptyList()
        }
    }

    internal sealed class Location {
        data class InHead(val element: MatroskaAfrProbe.EbmlElement) : Location()
        data class At(val absoluteOffset: Long) : Location()
        data object None : Location()
    }

    /** Where Chapters is: complete inside [head], at an offset the SeekHead gives, or nowhere known. */
    internal fun locateChapters(head: ByteArray): Location {
        val ebml = MatroskaAfrProbe.readElement(head, 0L) ?: return Location.None
        if (ebml.id != MatroskaAfrProbe.ID_EBML) return Location.None
        val ebmlEnd = ebml.endExclusiveOrNull() ?: return Location.None
        val segment = MatroskaAfrProbe.readElement(head, ebmlEnd) ?: return Location.None
        if (segment.id != MatroskaAfrProbe.ID_SEGMENT) return Location.None
        val segmentDataOffset = segment.dataOffset
        val limit = head.size.toLong()
        val segmentEnd = segment.endExclusiveOrNull()?.coerceAtMost(limit) ?: limit
        var seekPosition: Long? = null
        var pos = segmentDataOffset
        while (pos < segmentEnd) {
            val child = MatroskaAfrProbe.readElement(head, pos) ?: break
            if (child.unknownSize) break
            val childEnd = child.endExclusiveOrNull() ?: break
            when (child.id) {
                ID_CHAPTERS -> return if (childEnd <= limit) {
                    Location.InHead(child)
                } else {
                    Location.At(child.offset)
                }
                MatroskaAfrProbe.ID_SEEK_HEAD -> if (childEnd <= limit && seekPosition == null) {
                    seekPosition = seekPositionOf(head, child, ID_CHAPTERS)
                }
                MatroskaAfrProbe.ID_CLUSTER -> break
            }
            pos = childEnd
        }
        return seekPosition?.let { Location.At(segmentDataOffset + it) } ?: Location.None
    }

    private fun fetchAndParse(reader: RangeReader, offset: Long): List<Chapter> {
        val peek = reader.read(offset, PEEK_BYTES) ?: return emptyList()
        val header = MatroskaAfrProbe.readElement(peek, 0L) ?: return emptyList()
        if (header.id != ID_CHAPTERS || header.unknownSize) return emptyList()
        val total = header.headerSize + header.dataSize
        if (total <= 0L || total > MAX_CHAPTERS_BYTES) return emptyList()
        val bytes = reader.read(offset, total.toInt()) ?: return emptyList()
        if (bytes.size < total) return emptyList()
        val element = MatroskaAfrProbe.readElement(bytes, 0L) ?: return emptyList()
        return parseChapters(bytes, element)
    }

    /** SeekPosition (relative to the Segment data) of [targetId] in [seekHead], or null. */
    private fun seekPositionOf(bytes: ByteArray, seekHead: MatroskaAfrProbe.EbmlElement, targetId: Long): Long? {
        for (seek in children(bytes, seekHead)) {
            if (seek.id != MatroskaAfrProbe.ID_SEEK) continue
            var id: Long? = null
            var position: Long? = null
            for (field in children(bytes, seek)) {
                when (field.id) {
                    MatroskaAfrProbe.ID_SEEK_ID -> id = readUnsigned(bytes, field)
                    MatroskaAfrProbe.ID_SEEK_POSITION -> position = readUnsigned(bytes, field)
                }
            }
            if (id == targetId && position != null && position >= 0L) return position
        }
        return null
    }

    /**
     * The chapters of the default edition (else the first one not hidden), top-level atoms only,
     * leaving out hidden and disabled ones.
     */
    internal fun parseChapters(bytes: ByteArray, chaptersElement: MatroskaAfrProbe.EbmlElement): List<Chapter> {
        if (chaptersElement.id != ID_CHAPTERS) return emptyList()
        val editions = children(bytes, chaptersElement).filter { it.id == ID_EDITION_ENTRY }
        if (editions.isEmpty()) return emptyList()
        val flags = editions.map { edition ->
            var hidden = false
            var default = false
            for (field in children(bytes, edition)) {
                when (field.id) {
                    ID_EDITION_FLAG_HIDDEN -> hidden = readUnsigned(bytes, field) == 1L
                    ID_EDITION_FLAG_DEFAULT -> default = readUnsigned(bytes, field) == 1L
                }
            }
            Triple(edition, hidden, default)
        }
        val edition = (flags.firstOrNull { it.third && !it.second } ?: flags.firstOrNull { !it.second } ?: flags.first()).first
        return children(bytes, edition)
            .filter { it.id == ID_CHAPTER_ATOM }
            .mapNotNull { atom -> parseAtom(bytes, atom) }
    }

    private fun parseAtom(bytes: ByteArray, atom: MatroskaAfrProbe.EbmlElement): Chapter? {
        var startNs: Long? = null
        var endNs: Long? = null
        var hidden = false
        var enabled = true
        var title: String? = null
        for (field in children(bytes, atom)) {
            when (field.id) {
                ID_CHAPTER_TIME_START -> startNs = readUnsigned(bytes, field)
                ID_CHAPTER_TIME_END -> endNs = readUnsigned(bytes, field)
                ID_CHAPTER_FLAG_HIDDEN -> hidden = readUnsigned(bytes, field) == 1L
                ID_CHAPTER_FLAG_ENABLED -> enabled = readUnsigned(bytes, field) != 0L
                ID_CHAPTER_DISPLAY -> if (title == null) {
                    title = children(bytes, field).firstOrNull { it.id == ID_CHAP_STRING }?.let { readString(bytes, it) }
                }
            }
        }
        if (hidden || !enabled) return null
        val start = startNs ?: return null
        return Chapter(
            startMs = start / NS_PER_MS,
            endMs = endNs?.let { it / NS_PER_MS },
            title = title
        )
    }

    /** The complete, sized children of [parent] inside [bytes]. */
    private fun children(bytes: ByteArray, parent: MatroskaAfrProbe.EbmlElement): List<MatroskaAfrProbe.EbmlElement> {
        val end = (parent.endExclusiveOrNull() ?: return emptyList()).coerceAtMost(bytes.size.toLong())
        val out = ArrayList<MatroskaAfrProbe.EbmlElement>()
        var pos = parent.dataOffset
        while (pos < end) {
            val child = MatroskaAfrProbe.readElement(bytes, pos) ?: break
            val childEnd = child.endExclusiveOrNull() ?: break
            if (childEnd > end) break
            out += child
            pos = childEnd
        }
        return out
    }

    private fun readUnsigned(bytes: ByteArray, element: MatroskaAfrProbe.EbmlElement): Long? {
        if (element.unknownSize || element.dataSize > 8L) return null
        if (element.dataSize == 0L) return 0L
        val start = element.dataOffset.toInt()
        val end = (element.dataOffset + element.dataSize).toInt()
        if (end > bytes.size) return null
        var value = 0L
        for (i in start until end) value = (value shl 8) or (bytes[i].toLong() and 0xFFL)
        return value
    }

    private fun readString(bytes: ByteArray, element: MatroskaAfrProbe.EbmlElement): String? {
        if (element.unknownSize) return null
        val start = element.dataOffset.toInt()
        val end = (element.dataOffset + element.dataSize).toInt()
        if (end > bytes.size || start > end) return null
        return String(bytes, start, end - start, Charsets.UTF_8).trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }
    }
}
