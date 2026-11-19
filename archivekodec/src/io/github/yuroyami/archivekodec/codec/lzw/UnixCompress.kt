package io.github.yuroyami.archivekodec.codec.lzw

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * Unix `compress` (`.Z`): magic 1F 9D, a flags byte (maximum code width 9 to 16 and block mode),
 * then LZW codes packed least significant bit first, starting at 9 bits. Codes travel in groups
 * of eight, n bytes for n-bit codes: when the width grows or block mode clears the table (code
 * 256), the rest of the current group is padding. Both directions follow ncompress.
 */
internal object UnixCompress {
    private const val CLEAR = 256
    private const val FIRST = 257
    private const val INIT_BITS = 9
    private const val BLOCK_MODE = 0x80
    private const val CHECK_GAP = 10_000

    fun isCompress(bytes: ByteArray): Boolean = bytes.size >= 3 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x9D.toByte()

    fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        limits.checkOutputSize(0)
        if (!isCompress(input)) throw ArchiveFormatException("compress: missing 1F 9D magic", 0)
        val flags = input[2].toInt() and 0xFF
        val maxBits = flags and 0x1F
        if (maxBits !in INIT_BITS..16) throw ArchiveFormatException("compress: maximum code width $maxBits is not 9 to 16", 2)
        // ncompress changes width differently when the first width is already the widest, in a way
        // not pinned down here; refusing beats returning wrong bytes. `compress -b9` is rare.
        if (maxBits == INIT_BITS) throw ArchiveUnsupportedException("compress: a maximum code width of 9 is not supported", 2)
        if (flags and 0x60 != 0) throw ArchiveFormatException("compress: reserved flag bits are set", 2)
        val block = flags and BLOCK_MODE != 0
        val maxMaxCode = 1 shl maxBits
        val prefix = IntArray(maxMaxCode)
        val suffix = ByteArray(maxMaxCode)
        for (i in 0 until 256) suffix[i] = i.toByte()
        val stack = ByteArray(maxMaxCode)
        val out = ByteArrayBuilder(minOf(maxOf(256, input.size * 3), 1 shl 20))

        var bits = INIT_BITS
        // At the widest code the table fills completely before anything changes.
        fun limitFor(width: Int) = if (width == maxBits) maxMaxCode else (1 shl width) - 1
        var maxCode = limitFor(bits)
        var freeEnt = if (block) FIRST else 256
        var clearFlag = false
        // The current group: its first byte, how many bits it holds, and the bit offset into it.
        var position = 3
        var groupStart = 3
        var groupBits = 0
        var offset = 0

        fun getCode(): Int {
            if (clearFlag || offset + bits > groupBits || freeEnt > maxCode) {
                if (freeEnt > maxCode) {
                    bits++
                    maxCode = limitFor(bits)
                }
                if (clearFlag) {
                    bits = INIT_BITS
                    maxCode = limitFor(bits)
                    clearFlag = false
                }
                // A new group of `bits` bytes; whatever was left of the old one is padding.
                groupStart = position
                val length = minOf(bits, input.size - position)
                if (length <= 0) return -1
                position += length
                groupBits = length * 8
                offset = 0
                if (offset + bits > groupBits) return -1
            }
            var code = 0
            for (i in 0 until bits) {
                val bit = offset + i
                if ((input[groupStart + (bit ushr 3)].toInt() ushr (bit and 7)) and 1 != 0) code = code or (1 shl i)
            }
            offset += bits
            return code
        }

        fun emit(bytes: ByteArray, from: Int, to: Int) {
            val total = out.size.toLong() + (to - from)
            limits.checkOutputSize(total)
            limits.checkRatio(input.size.toLong(), total)
            out.append(bytes, from, to - from)
        }

        var oldCode = getCode()
        if (oldCode == -1) return ByteArray(0)
        if (oldCode > 255) throw ArchiveFormatException("compress: the first code $oldCode is not a literal", 3)
        var finChar = oldCode
        emit(byteArrayOf(finChar.toByte()), 0, 1)
        while (true) {
            var code = getCode()
            if (code == -1) break
            if (code == CLEAR && block) {
                clearFlag = true
                freeEnt = FIRST - 1
                code = getCode()
                if (code == -1) break
            }
            val inCode = code
            var top = stack.size
            if (code >= freeEnt) {
                if (code > freeEnt) throw ArchiveFormatException("compress: code $code is past the table's next entry $freeEnt", groupStart.toLong())
                stack[--top] = finChar.toByte()
                code = oldCode
            }
            while (code >= 256) {
                if (top == 0) throw ArchiveFormatException("compress: a code's string is longer than the table", groupStart.toLong())
                stack[--top] = suffix[code]
                code = prefix[code]
            }
            finChar = suffix[code].toInt() and 0xFF
            stack[--top] = finChar.toByte()
            emit(stack, top, stack.size)
            if (freeEnt < maxMaxCode) {
                prefix[freeEnt] = oldCode
                suffix[freeEnt] = finChar.toByte()
                freeEnt++
            }
            oldCode = inCode
        }
        return out.takeByteArray()
    }

    fun compress(data: ByteArray, maxBits: Int = 16): ByteArray {
        require(maxBits in INIT_BITS + 1..16) { "compress: maximum code width must be 10 to 16, not $maxBits" }
        val out = ByteArrayBuilder(maxOf(64, data.size / 2))
        out.append(0x1F); out.append(0x9D.toByte()); out.append((BLOCK_MODE or maxBits).toByte())
        if (data.isEmpty()) return out.takeByteArray()
        val maxMaxCode = 1 shl maxBits
        // (prefix code shl 8 or next byte) to code, one table for every width.
        val table = HashMap<Int, Int>()
        var bits = INIT_BITS
        fun limitFor(width: Int) = if (width == maxBits) maxMaxCode else (1 shl width) - 1
        var maxCode = limitFor(bits)
        var freeEnt = FIRST
        var clearFlag = false
        val group = ByteArray(16)
        var offset = 0
        var bytesOut = 3L
        var checkpoint = CHECK_GAP.toLong()
        var ratio = 0L

        fun writeGroup(count: Int) {
            out.append(group, 0, count)
            bytesOut += count
            group.fill(0)
            offset = 0
        }

        fun output(code: Int) {
            for (i in 0 until bits) if ((code ushr i) and 1 != 0) {
                val bit = offset + i
                group[bit ushr 3] = (group[bit ushr 3].toInt() or (1 shl (bit and 7))).toByte()
            }
            offset += bits
            if (offset == bits * 8) writeGroup(bits)
            if (freeEnt > maxCode || clearFlag) {
                // The whole group goes out, padding and all, before the width changes.
                if (offset > 0) writeGroup(bits)
                if (clearFlag) {
                    bits = INIT_BITS
                    maxCode = limitFor(bits)
                    clearFlag = false
                } else {
                    bits++
                    maxCode = limitFor(bits)
                }
            }
        }

        var ent = data[0].toInt() and 0xFF
        var inCount = 1L
        for (index in 1 until data.size) {
            val c = data[index].toInt() and 0xFF
            inCount++
            val key = (ent shl 8) or c
            val found = table[key]
            if (found != null) {
                ent = found
                continue
            }
            output(ent)
            ent = c
            if (freeEnt < maxMaxCode) {
                table[key] = freeEnt++
            } else if (inCount >= checkpoint) {
                // ncompress's cl_block: clear the table once the ratio stops improving.
                checkpoint = inCount + CHECK_GAP
                val current = if (inCount > 0x007FFFFF) { val r = bytesOut ushr 8; if (r == 0L) Long.MAX_VALUE else inCount / r } else (inCount shl 8) / bytesOut
                if (current > ratio) {
                    ratio = current
                } else {
                    ratio = 0
                    table.clear()
                    freeEnt = FIRST
                    clearFlag = true
                    output(CLEAR)
                }
            }
        }
        output(ent)
        if (offset > 0) writeGroup((offset + 7) / 8)
        return out.takeByteArray()
    }
}
