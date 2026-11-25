package com.fetch.tv.core.player

import com.fetch.tv.core.player.MatroskaAfrProbe.buildElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaChapterReaderTest {

    private fun uint(id: Long, value: Long): ByteArray {
        val bytes = ArrayList<Byte>()
        var v = value
        do {
            bytes.add(0, (v and 0xFF).toByte())
            v = v ushr 8
        } while (v > 0)
        return buildElement(id, bytes.toByteArray())
    }

    private fun atom(startMs: Long, title: String?, hidden: Boolean = false, endMs: Long? = null): ByteArray {
        var payload = uint(0x73C4L, startMs + 1) + uint(0x91L, startMs * 1_000_000L)
        if (endMs != null) payload += uint(0x92L, endMs * 1_000_000L)
        if (hidden) payload += uint(0x98L, 1)
        if (title != null) {
            payload += buildElement(0x80L, buildElement(0x85L, title.toByteArray()) + buildElement(0x437CL, "eng".toByteArray()))
        }
        return buildElement(0xB6L, payload)
    }

    private fun edition(vararg atoms: ByteArray, default: Boolean = false, hidden: Boolean = false): ByteArray {
        var payload = ByteArray(0)
        if (hidden) payload += uint(0x45BDL, 1)
        if (default) payload += uint(0x45DBL, 1)
        atoms.forEach { payload += it }
        return buildElement(0x45B9L, payload)
    }

    private val ebmlHeader = buildElement(MatroskaAfrProbe.ID_EBML, buildElement(0x4282L, "matroska".toByteArray()))
    private val info = buildElement(MatroskaAfrProbe.ID_INFO, uint(0x2AD7B1L, 1_000_000L))
    private val cluster = buildElement(MatroskaAfrProbe.ID_CLUSTER, uint(0xE7L, 0))

    private fun file(vararg children: ByteArray): ByteArray =
        ebmlHeader + buildElement(MatroskaAfrProbe.ID_SEGMENT, children.fold(ByteArray(0)) { acc, b -> acc + b })

    private class Reader(private val bytes: ByteArray) : MatroskaChapterReader.RangeReader {
        val reads = mutableListOf<Pair<Long, Int>>()
        override fun read(offset: Long, length: Int): ByteArray? {
            reads += offset to length
            if (offset >= bytes.size) return null
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            return bytes.copyOfRange(offset.toInt(), end)
        }
    }

    @Test
    fun `reads chapters from the head with one range read`() {
        val chapters = buildElement(
            MatroskaChapterReader.ID_CHAPTERS,
            edition(atom(0, "Opening"), atom(90_000, "Part A", endMs = 600_000), atom(100_000, "Hidden", hidden = true))
        )
        val reader = Reader(file(info, chapters, cluster))
        val result = MatroskaChapterReader.read(reader)
        assertEquals(
            listOf(Chapter(0L, null, "Opening"), Chapter(90_000L, 600_000L, "Part A")),
            result
        )
        assertEquals(1, reader.reads.size)
    }

    @Test
    fun `prefers the default edition and skips hidden ones`() {
        val chapters = buildElement(
            MatroskaChapterReader.ID_CHAPTERS,
            edition(atom(0, "Hidden edition"), hidden = true) +
                edition(atom(0, "First")) +
                edition(atom(0, "Default"), atom(5_000, null), default = true)
        )
        val result = MatroskaChapterReader.read(Reader(file(info, chapters, cluster)))
        assertEquals(listOf(Chapter(0L, null, "Default"), Chapter(5_000L, null, null)), result)
    }

    @Test
    fun `follows the SeekHead to chapters written after the clusters`() {
        val chapters = buildElement(MatroskaChapterReader.ID_CHAPTERS, edition(atom(0, "Intro"), atom(60_000, "Main")))
        // Big filler cluster pushes the chapters past the head window.
        val filler = buildElement(MatroskaAfrProbe.ID_CLUSTER, ByteArray(MatroskaChapterReader.HEAD_BYTES + 1000))
        // SeekHead with a fixed-width position, so its size does not depend on the value.
        fun seekHead(position: Long): ByteArray {
            val pos = ByteArray(8) { i -> ((position ushr (8 * (7 - i))) and 0xFF).toByte() }
            val seek = buildElement(
                MatroskaAfrProbe.ID_SEEK,
                buildElement(MatroskaAfrProbe.ID_SEEK_ID, MatroskaAfrProbe.elementIdBytes(MatroskaChapterReader.ID_CHAPTERS)) +
                    buildElement(MatroskaAfrProbe.ID_SEEK_POSITION, pos)
            )
            return buildElement(MatroskaAfrProbe.ID_SEEK_HEAD, seek)
        }
        val relative = seekHead(0).size + info.size + filler.size
        val reader = Reader(file(seekHead(relative.toLong()), info, filler, chapters))
        val result = MatroskaChapterReader.read(reader)
        assertEquals(listOf("Intro", "Main"), result.map { it.title })
        assertEquals(3, reader.reads.size)
    }

    @Test
    fun `no chapters and non-Matroska files give nothing`() {
        assertTrue(MatroskaChapterReader.read(Reader(file(info, cluster))).isEmpty())
        assertTrue(MatroskaChapterReader.read(Reader(ByteArray(64) { 0x47 })).isEmpty())
        assertTrue(MatroskaChapterReader.read { _, _ -> null }.isEmpty())
    }
}
