package io.github.yuroyami.archivekodec.codec.lzma

/**
 * The x86 branch-call-jump filter, as the LZMA SDK's `Bra86.c` defines it for 7z and xz:
 * E8/E9 call and jump targets were made absolute when encoding, and are made relative
 * again here. The last four bytes of a stream are never converted.
 */
internal object Bcj {
    private val ALLOWED = booleanArrayOf(true, true, true, false, true, false, false, false)
    private val BIT_NUMBER = intArrayOf(0, 1, 2, 2, 3, 3, 3, 3)

    private fun isMsByte(b: Int): Boolean = ((b + 1) and 0xFE) == 0

    /** Decode [data] in place, as one stream whose first byte sits at [start]. */
    fun x86Decode(data: ByteArray, start: Int = 0) {
        val size = data.size
        if (size < 5) return
        val ip = 5 + start
        var position = 0
        var previousPosition = -1
        var previousMask = 0
        val limit = size - 4
        while (true) {
            while (position < limit && (data[position].toInt() and 0xFE) != 0xE8) position++
            if (position >= limit) break
            val gap = position - previousPosition
            if (gap > 3) {
                previousMask = 0
            } else {
                previousMask = (previousMask shl (gap - 1)) and 0x7
                if (previousMask != 0) {
                    val b = data[position + 4 - BIT_NUMBER[previousMask]].toInt() and 0xFF
                    if (!ALLOWED[previousMask] || isMsByte(b)) {
                        previousPosition = position
                        previousMask = ((previousMask shl 1) and 0x7) or 1
                        position++
                        continue
                    }
                }
            }
            previousPosition = position
            if (isMsByte(data[position + 4].toInt() and 0xFF)) {
                var source = (data[position + 4].toInt() and 0xFF shl 24) or (data[position + 3].toInt() and 0xFF shl 16) or
                    (data[position + 2].toInt() and 0xFF shl 8) or (data[position + 1].toInt() and 0xFF)
                var destination: Int
                while (true) {
                    destination = source - (ip + position)
                    if (previousMask == 0) break
                    val index = BIT_NUMBER[previousMask] * 8
                    val b = (destination ushr (24 - index)) and 0xFF
                    if (!isMsByte(b)) break
                    source = destination xor ((1 shl (32 - index)) - 1)
                }
                data[position + 4] = ((((destination ushr 24) and 1) - 1).inv()).toByte()
                data[position + 3] = (destination ushr 16).toByte()
                data[position + 2] = (destination ushr 8).toByte()
                data[position + 1] = destination.toByte()
                position += 5
            } else {
                previousMask = ((previousMask shl 1) and 0x7) or 1
                position++
            }
        }
    }

    // The other branch filters, as xz and 7z define them. Each converts absolute branch targets
    // back to relative ones, with [start] the stream position of data[0]; bytes after the last
    // whole instruction are left alone.

    private fun le32(d: ByteArray, i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) or
        ((d[i + 2].toInt() and 0xFF) shl 16) or ((d[i + 3].toInt() and 0xFF) shl 24)
    private fun be32(d: ByteArray, i: Int) = (d[i + 3].toInt() and 0xFF) or ((d[i + 2].toInt() and 0xFF) shl 8) or
        ((d[i + 1].toInt() and 0xFF) shl 16) or ((d[i].toInt() and 0xFF) shl 24)
    private fun putLe32(d: ByteArray, i: Int, v: Int) { for (k in 0 until 4) d[i + k] = (v ushr (8 * k)).toByte() }
    private fun putBe32(d: ByteArray, i: Int, v: Int) { for (k in 0 until 4) d[i + 3 - k] = (v ushr (8 * k)).toByte() }

    /** ARM: BL instructions, little-endian, condition "always". */
    fun armDecode(d: ByteArray, start: Int = 0) {
        var i = 0
        while (i + 4 <= d.size) {
            if (d[i + 3].toInt() and 0xFF == 0xEB) {
                val src = (((d[i + 2].toInt() and 0xFF) shl 16) or ((d[i + 1].toInt() and 0xFF) shl 8) or (d[i].toInt() and 0xFF)) shl 2
                val dest = (src - (start + i + 8)) ushr 2
                d[i + 2] = (dest ushr 16).toByte(); d[i + 1] = (dest ushr 8).toByte(); d[i] = dest.toByte()
            }
            i += 4
        }
    }

    /** ARM-Thumb: the two halves of a BL pair. */
    fun armThumbDecode(d: ByteArray, start: Int = 0) {
        var i = 0
        while (i + 4 <= d.size) {
            if (d[i + 1].toInt() and 0xF8 == 0xF0 && d[i + 3].toInt() and 0xF8 == 0xF8) {
                val src = ((((d[i + 1].toInt() and 7) shl 19) or ((d[i].toInt() and 0xFF) shl 11) or
                    ((d[i + 3].toInt() and 7) shl 8) or (d[i + 2].toInt() and 0xFF)) shl 1)
                val dest = (src - (start + i + 4)) ushr 1
                d[i + 1] = (0xF0 or ((dest ushr 19) and 7)).toByte()
                d[i] = (dest ushr 11).toByte()
                d[i + 3] = (0xF8 or ((dest ushr 8) and 7)).toByte()
                d[i + 2] = dest.toByte()
                i += 2
            }
            i += 2
        }
    }

    /** ARM64: BL and ADRP instructions. */
    fun arm64Decode(d: ByteArray, start: Int = 0) {
        var i = 0
        while (i + 4 <= d.size) {
            val pc = start + i
            var instr = le32(d, i)
            if (instr ushr 26 == 0x25) {
                instr = 0x94000000.toInt() or ((instr - (pc ushr 2)) and 0x03FFFFFF)
                putLe32(d, i, instr)
            } else if (instr and 0x9F000000.toInt() == 0x90000000.toInt()) {
                val src = ((instr ushr 29) and 3) or ((instr ushr 3) and 0x001FFFFC)
                if ((src + 0x00020000) and 0x001C0000 == 0) {
                    val dest = src - (pc ushr 12)
                    instr = instr and 0x9000001F.toInt()
                    instr = instr or ((dest and 3) shl 29)
                    instr = instr or ((dest and 0x0003FFFC) shl 3)
                    instr = instr or ((0 - (dest and 0x00020000)) and 0x00E00000)
                    putLe32(d, i, instr)
                }
            }
            i += 4
        }
    }

    /** PowerPC: big-endian `bl` instructions. */
    fun powerPcDecode(d: ByteArray, start: Int = 0) {
        var i = 0
        while (i + 4 <= d.size) {
            if ((d[i].toInt() and 0xFF) ushr 2 == 0x12 && d[i + 3].toInt() and 3 == 1) {
                val src = (((d[i].toInt() and 3) shl 24) or ((d[i + 1].toInt() and 0xFF) shl 16) or
                    ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFC))
                val dest = src - (start + i)
                d[i] = (0x48 or ((dest ushr 24) and 3)).toByte()
                d[i + 1] = (dest ushr 16).toByte()
                d[i + 2] = (dest ushr 8).toByte()
                d[i + 3] = ((d[i + 3].toInt() and 3) or (dest and 0xFC)).toByte()
            }
            i += 4
        }
    }

    /** SPARC: `call` instructions. */
    fun sparcDecode(d: ByteArray, start: Int = 0) {
        var i = 0
        while (i + 4 <= d.size) {
            val b0 = d[i].toInt() and 0xFF
            val b1 = d[i + 1].toInt() and 0xFF
            if ((b0 == 0x40 && b1 and 0xC0 == 0) || (b0 == 0x7F && b1 and 0xC0 == 0xC0)) {
                var dest = ((be32(d, i) shl 2) - (start + i)) ushr 2
                dest = (((0 - ((dest ushr 22) and 1)) shl 22) and 0x3FFFFFFF) or (dest and 0x3FFFFF) or 0x40000000
                putBe32(d, i, dest)
            }
            i += 4
        }
    }

    private val IA64_BRANCH = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 6, 6, 0, 0, 7, 7, 4, 4, 0, 0, 4, 4, 0, 0)

    /** IA-64: IP-relative branches in the slots of 16-byte bundles. */
    fun ia64Decode(d: ByteArray, start: Int = 0) {
        var i = 0
        while (i + 16 <= d.size) {
            val mask = IA64_BRANCH[d[i].toInt() and 0x1F]
            var bitPos = 5
            for (slot in 0 until 3) {
                if ((mask ushr slot) and 1 != 0) {
                    val bytePos = bitPos ushr 3
                    val bitRes = bitPos and 7
                    var instruction = 0L
                    for (j in 0 until 6) instruction = instruction or ((d[i + j + bytePos].toLong() and 0xFF) shl (8 * j))
                    var norm = instruction ushr bitRes
                    if (((norm ushr 37) and 0xF) == 0x5L && ((norm ushr 9) and 0x7) == 0L) {
                        var src = ((norm ushr 13) and 0xFFFFF).toInt()
                        src = src or (((norm ushr 36) and 1).toInt() shl 20)
                        src = src shl 4
                        val dest = (src - (start + i)) ushr 4
                        norm = norm and (0x8FFFFFL shl 13).inv()
                        norm = norm or ((dest.toLong() and 0xFFFFF) shl 13)
                        norm = norm or ((dest.toLong() and 0x100000) shl (36 - 20))
                        instruction = instruction and ((1L shl bitRes) - 1)
                        instruction = instruction or (norm shl bitRes)
                        for (j in 0 until 6) d[i + j + bytePos] = (instruction ushr (8 * j)).toByte()
                    }
                }
                bitPos += 41
            }
            i += 16
        }
    }
}
