package io.github.yuroyami.archivekodec.codec.xpress

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.lzx.Msb16Reader
import io.github.yuroyami.archivekodec.codec.lzx.PrefixCode

/**
 * XPRESS Huffman (LZ77+Huffman, MS-XCA 2.2.4) decoder for one block, the format of WIM's
 * XPRESS chunks. The block starts with 512 four-bit code lengths: literals 0 to 255 and
 * match headers 256 to 511, each header holding a length (low 4 bits) and the bit count of
 * the offset (high 4 bits). Lengths of 18 or more continue in bytes stored whole after the
 * loaded bits, the way wimlib and Windows read them.
 */
internal class XpressHuffman private constructor(input: ByteArray, from: Int, to: Int) :
    Msb16Reader(input, from + TABLE_BYTES, to, "XPRESS") {

    private val code = PrefixCode(SYMBOLS, maxLength = MAX_CODE)

    private fun decode(size: Int): ByteArray {
        val from = position - TABLE_BYTES
        for (i in 0 until TABLE_BYTES) {
            val b = input[from + i].toInt() and 0xFF
            code.lengths[2 * i] = b and 0x0F
            code.lengths[2 * i + 1] = b ushr 4
        }
        code.build(this, "XPRESS")
        val out = ByteArray(size)
        var at = 0
        while (at < size) {
            val symbol = code.decode(this, "XPRESS")
            if (symbol < 256) {
                out[at++] = symbol.toByte()
                continue
            }
            val header = symbol - 256
            val offsetBits = header ushr 4
            var length = header and 0x0F
            ensure(16)
            val offset = (1 shl offsetBits) or bits(offsetBits)
            if (length == 0x0F) {
                length += rawByte()
                if (length == 0x0F + 0xFF) length = rawByte() or (rawByte() shl 8)
            }
            length += MIN_MATCH
            if (offset > at) fail("match offset $offset reaches before the block")
            if (length > size - at) fail("match runs past the end of the block")
            var source = at - offset
            repeat(length) { out[at++] = out[source++] }
        }
        return out
    }

    companion object {
        private const val SYMBOLS = 512
        private const val TABLE_BYTES = SYMBOLS / 2
        private const val MIN_MATCH = 3
        private const val MAX_CODE = 15

        /** Decode the block in [input] from [from] to [to] into [size] bytes. */
        fun decode(input: ByteArray, from: Int, to: Int, size: Int): ByteArray {
            if (to - from < TABLE_BYTES) throw ArchiveFormatException("XPRESS: block is shorter than its code table", from.toLong())
            return XpressHuffman(input, from, to).decode(size)
        }
    }
}
