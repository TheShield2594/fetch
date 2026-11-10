package com.fetch.desktop.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoder was checked module for module against segno (a reference Python encoder) for
 * versions 1 to 29, both levels and several masks; this golden symbol keeps it that way.
 */
class QrCodeTest {
    private val golden = """
        111111101100001111111
        100000101100101000001
        101110100110001011101
        101110101101101011101
        101110100011001011101
        100000100100001000001
        111111101010101111111
        000000001110000000000
        101101110010001001011
        010000011010010011101
        011101110011010100011
        011100001000001011010
        000000110011100100001
        000000001001101010101
        111111101001001010000
        100000101110101101101
        101110100110110111110
        101110101100100001110
        101110101001101100100
        100000100101111110001
        111111101100001100100
    """.trimIndent().lines()

    private fun QrCode.rows() = (0 until size).map { y -> (0 until size).joinToString("") { x -> if (this[x, y]) "1" else "0" } }

    @Test
    fun `matches the reference symbol`() {
        val qr = QrCode.encode("Hello, wörld!", QrCode.Ecc.MEDIUM, forceMask = 3)!!
        assertEquals(1, qr.version)
        assertEquals(golden, qr.rows())
    }

    @Test
    fun `picks the smallest version and a mask, with finder patterns in the corners`() {
        val qr = QrCode.encode("fetch://movie/tt0111")!!
        assertEquals(2, qr.version)
        assertEquals(25, qr.size)
        assertTrue(qr.mask in 0..7)
        for ((cx, cy) in listOf(3 to 3, qr.size - 4 to 3, 3 to qr.size - 4)) {
            assertTrue(qr[cx, cy])
            assertTrue(!qr[cx + 2, cy])
            assertTrue(qr[cx + 3, cy + 3])
        }
        // Longer links take bigger versions, with version information from 7 up.
        assertEquals(25, QrCode.encode("y".repeat(1200), QrCode.Ecc.LOW)!!.version)
        assertNull(QrCode.encode("z".repeat(3000)))
    }

    @Test
    fun `alignment pattern positions follow the standard table`() {
        assertEquals(listOf(6, 18), QrCode.alignmentPositions(2).toList())
        assertEquals(listOf(6, 22, 38), QrCode.alignmentPositions(7).toList())
        assertEquals(listOf(6, 26, 46, 66), QrCode.alignmentPositions(14).toList())
        assertEquals(listOf(6, 34, 60, 86, 112, 138), QrCode.alignmentPositions(32).toList())
    }
}
