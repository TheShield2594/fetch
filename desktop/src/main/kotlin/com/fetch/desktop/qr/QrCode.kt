package com.fetch.desktop.qr

/**
 * A QR code (ISO/IEC 18004, model 2) for a text such as an invite link, as the TV draws one
 * with ZXing (`core/qr/QrCodeGenerator.kt`). The desktop has no ZXing, so this is a small
 * encoder of its own: byte mode, versions 1 to 40, the error correction level asked for, and the
 * mask with the lowest penalty, as the standard picks it.
 *
 * [modules] is `size × size`, `true` for a dark module, without the quiet zone.
 */
class QrCode private constructor(val version: Int, val size: Int, val mask: Int, private val modules: Array<BooleanArray>) {

    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y][x]

    /** How much damage the code survives (the TV's ZXing default is L; M is used here for links). */
    enum class Ecc(internal val formatBits: Int, internal val eccPerBlock: IntArray, internal val blocks: IntArray) {
        LOW(
            1,
            intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25)
        ),
        MEDIUM(
            0,
            intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49)
        );

        internal fun dataCodewords(version: Int): Int =
            rawDataModules(version) / 8 - eccPerBlock[version - 1] * blocks[version - 1]
    }

    companion object {
        /** Encodes [text] as UTF-8 in the smallest version that holds it; null when it's too long for any. */
        fun encode(text: String, ecc: Ecc = Ecc.MEDIUM, forceMask: Int? = null): QrCode? {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val version = (1..40).firstOrNull { v ->
                val countBits = if (v <= 9) 8 else 16
                bytes.size < (1 shl countBits) && 4 + countBits + bytes.size * 8 <= ecc.dataCodewords(v) * 8
            } ?: return null
            val codewords = withEcc(dataCodewords(bytes, version, ecc), version, ecc)
            val size = version * 4 + 17
            val dark = Array(size) { BooleanArray(size) }
            val function = Array(size) { BooleanArray(size) }
            drawFunctionPatterns(version, size, dark, function)
            drawCodewords(codewords, size, dark, function)
            val mask = forceMask ?: (0 until 8).minBy { m ->
                val trial = Array(size) { dark[it].copyOf() }
                applyMask(m, size, trial, function)
                drawFormatBits(ecc, m, size, trial, function)
                penalty(size, trial)
            }
            applyMask(mask, size, dark, function)
            drawFormatBits(ecc, mask, size, dark, function)
            return QrCode(version, size, mask, dark)
        }

        // --- Data ---

        private fun dataCodewords(bytes: ByteArray, version: Int, ecc: Ecc): ByteArray {
            val capacityBits = ecc.dataCodewords(version) * 8
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(bytes.size, if (version <= 9) 8 else 16)
            bytes.forEach { bits.append(it.toInt() and 0xFF, 8) }
            bits.append(0, minOf(4, capacityBits - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacityBits) {
                bits.append(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }
            return bits.toBytes()
        }

        private fun withEcc(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
            val numBlocks = ecc.blocks[version - 1]
            val eccLen = ecc.eccPerBlock[version - 1]
            val raw = rawDataModules(version) / 8
            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val divisor = rsDivisor(eccLen)
            val blocks = ArrayList<ByteArray>(numBlocks)
            var k = 0
            for (i in 0 until numBlocks) {
                val len = shortLen - eccLen + if (i < numShort) 0 else 1
                val dat = data.copyOfRange(k, k + len)
                k += len
                val block = ByteArray(shortLen + 1)
                dat.copyInto(block)
                rsRemainder(dat, divisor).copyInto(block, shortLen + 1 - eccLen)
                blocks += block
            }
            val out = ByteArray(raw)
            var n = 0
            for (i in 0..shortLen) {
                for ((j, block) in blocks.withIndex()) {
                    // The short blocks have no codeword at the padding position.
                    if (i != shortLen - eccLen || j >= numShort) out[n++] = block[i]
                }
            }
            return out
        }

        internal fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        // --- Reed–Solomon over GF(256), polynomial 0x11D ---

        private fun gfMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = gfMultiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMultiply(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }

        // --- The matrix ---

        private fun set(x: Int, y: Int, dark: Boolean, modules: Array<BooleanArray>, function: Array<BooleanArray>) {
            modules[y][x] = dark
            function[y][x] = true
        }

        internal fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
            val result = IntArray(count)
            result[0] = 6
            var pos = version * 4 + 17 - 7
            for (i in count - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        private fun drawFunctionPatterns(version: Int, size: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0, m, f)
                set(i, 6, i % 2 == 0, m, f)
            }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        set(x, y, dist != 2 && dist != 4, m, f)
                    }
                }
            }
            val align = alignmentPositions(version)
            val last = align.size - 1
            for (i in align.indices) for (j in align.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) {
                    set(align[i] + dx, align[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1, m, f)
                }
            }
            // Reserve the format areas (filled in with the mask) and draw the version blocks.
            drawFormatBits(Ecc.MEDIUM, 0, size, m, f)
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val bit = (bits ushr i) and 1 != 0
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, bit, m, f)
                    set(b, a, bit, m, f)
                }
            }
        }

        private fun drawFormatBits(ecc: Ecc, mask: Int, size: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val data = (ecc.formatBits shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i), m, f)
            set(8, 7, bit(6), m, f)
            set(8, 8, bit(7), m, f)
            set(7, 8, bit(8), m, f)
            for (i in 9 until 15) set(14 - i, 8, bit(i), m, f)
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i), m, f)
            for (i in 8 until 15) set(8, size - 15 + i, bit(i), m, f)
            set(8, size - 8, true, m, f)
        }

        private fun drawCodewords(data: ByteArray, size: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!f[y][x] && i < data.size * 8) {
                            m[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int, size: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !f[y][x]) m[y][x] = !m[y][x]
            }
        }

        /** The standard's four penalty rules, to pick the mask. */
        internal fun penalty(size: Int, m: Array<BooleanArray>): Int {
            var result = 0
            fun line(get: (Int) -> Boolean) {
                var run = 1
                for (i in 1 until size) {
                    if (get(i) == get(i - 1)) {
                        run++
                    } else {
                        if (run >= 5) result += run - 2
                        run = 1
                    }
                }
                if (run >= 5) result += run - 2
                // 1:1:3:1:1 with four light modules on a side (outside the code counts as light).
                val pattern = booleanArrayOf(true, false, true, true, true, false, true)
                for (start in -4 until size) {
                    val core = (0 until 7).all { k -> start + k in 0 until size && get(start + k) == pattern[k] }
                    if (!core) continue
                    val before = (1..4).all { k -> start - k !in 0 until size || !get(start - k) }
                    val after = (7..10).all { k -> start + k !in 0 until size || !get(start + k) }
                    if (before) result += 40
                    if (after) result += 40
                }
            }
            for (y in 0 until size) line { x -> m[y][x] }
            for (x in 0 until size) line { y -> m[y][x] }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3
            }
            val dark = m.sumOf { row -> row.count { it } }
            val total = size * size
            val k = ((kotlin.math.abs(dark * 20L - total * 10L) + total - 1) / total).toInt() - 1
            result += k * 10
            return result
        }
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size: Int get() = bits.size

        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits += (value ushr i) and 1 != 0
        }

        fun toBytes(): ByteArray = ByteArray(bits.size / 8) { i ->
            var b = 0
            for (j in 0 until 8) b = (b shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            b.toByte()
        }
    }
}
