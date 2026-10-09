package io.github.yuroyami.archivekodec.codec.lzma

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.checksum.Crc64
import io.github.yuroyami.archivekodec.checksum.Sha256
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.readU32Le

/**
 * The .xz container (Tukaani "The .xz File Format" 1.1.0): stream header, blocks whose
 * filter chain ends in LZMA2 (optionally after a delta filter), an index and a footer.
 * Every block check (CRC-32, CRC-64 or SHA-256) and the index are verified. Concatenated
 * streams and stream padding are decoded, as are the delta and x86 BCJ filters. Other branch
 * filters are refused by name.
 */
internal object Xz {
    private val MAGIC = byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0)
    private const val CHECK_NONE = 0
    private const val CHECK_CRC32 = 1
    private const val CHECK_CRC64 = 4
    private const val CHECK_SHA256 = 10
    private const val FILTER_DELTA = 0x03L
    private const val FILTER_X86 = 0x04L
    private const val FILTER_LZMA2 = 0x21L

    private fun checkSize(id: Int): Int = when (id) {
        0 -> 0
        in 1..3 -> 4
        in 4..6 -> 8
        in 7..9 -> 16
        in 10..12 -> 32
        else -> 64
    }

    private class Cursor(val input: ByteArray, var pos: Int) {
        fun fail(message: String, at: Int = pos): Nothing = throw ArchiveFormatException("xz: $message", at.toLong())
        fun need(n: Int) { if (input.size - pos < n) fail("truncated") }
        fun byte(): Int { need(1); return input[pos++].toInt() and 0xFF }

        /** A multibyte integer: seven bits per byte, low first, at most nine bytes. */
        fun varint(): Long {
            var value = 0L
            for (i in 0 until 9) {
                val b = byte()
                if (b == 0 && i > 0) fail("non-minimal integer", pos - 1)
                value = value or ((b and 0x7F).toLong() shl (7 * i))
                if (b and 0x80 == 0) return value
            }
            fail("integer exceeds 63 bits")
        }
    }

    private class Check(val id: Int) {
        private val crc32 = if (id == CHECK_CRC32) Crc32() else null
        private val crc64 = if (id == CHECK_CRC64) Crc64() else null
        private val sha = if (id == CHECK_SHA256) Sha256() else null

        fun update(data: ByteArray, offset: Int, length: Int) {
            crc32?.update(data, offset, length); crc64?.update(data, offset, length); sha?.update(data, offset, length)
        }

        fun result(): ByteArray = when {
            crc32 != null -> ByteArray(4) { (crc32.value() ushr (8 * it)).toByte() }
            crc64 != null -> ByteArray(8) { (crc64.value() ushr (8 * it)).toByte() }
            sha != null -> sha.digest()
            else -> ByteArray(0)
        }
    }

    fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        val out = LzOutput(limits, input.size)
        val c = Cursor(input, 0)
        var streams = 0
        while (c.pos < input.size) {
            if (streams > 0) {
                // Stream padding: zero bytes in multiples of four, between or after streams.
                val padStart = c.pos
                while (c.pos < input.size && input[c.pos].toInt() == 0) c.pos++
                if ((c.pos - padStart) % 4 != 0) c.fail("stream padding is not a multiple of four", padStart)
                if (c.pos == input.size) break
            }
            decodeStream(c, out)
            streams++
        }
        if (streams == 0) c.fail("empty input")
        return out.take()
    }

    private fun decodeStream(c: Cursor, out: LzOutput) {
        val input = c.input
        val start = c.pos
        c.need(12)
        for (i in MAGIC.indices) if (input[c.pos + i] != MAGIC[i]) c.fail("missing stream header magic", start)
        val flags = input.copyOfRange(c.pos + 6, c.pos + 8)
        if (flags[0].toInt() != 0 || flags[1].toInt() and 0xF0 != 0) c.fail("reserved stream flags are set", start + 6)
        if (Crc32.of(flags) != readU32Le(input, c.pos + 8)) c.fail("stream header CRC mismatch", start + 8)
        c.pos += 12
        val checkId = flags[1].toInt() and 0x0F
        if (checkId != CHECK_NONE && checkId != CHECK_CRC32 && checkId != CHECK_CRC64 && checkId != CHECK_SHA256) {
            throw ArchiveUnsupportedException("xz: check type $checkId is not supported", (start + 7).toLong())
        }

        val records = ArrayList<LongArray>()
        while (true) {
            c.need(1)
            if (input[c.pos].toInt() == 0) break
            records.add(decodeBlock(c, out, checkId))
        }

        // Index: every record must match the blocks just decoded.
        val indexStart = c.pos
        c.pos++
        val count = c.varint()
        if (count != records.size.toLong()) c.fail("index lists $count blocks, stream has ${records.size}", indexStart)
        for (record in records) {
            if (c.varint() != record[0] || c.varint() != record[1]) c.fail("index record does not match its block", indexStart)
        }
        while ((c.pos - indexStart) % 4 != 0) if (c.byte() != 0) c.fail("nonzero index padding", c.pos - 1)
        c.need(4)
        if (Crc32().apply { update(input, indexStart, c.pos - indexStart) }.value() != readU32Le(input, c.pos)) {
            c.fail("index CRC mismatch")
        }
        c.pos += 4
        val indexSize = c.pos - indexStart

        // Footer: CRC-32, backward size, the same flags, and "YZ".
        c.need(12)
        val footer = c.pos
        if (Crc32().apply { update(input, footer + 4, 6) }.value() != readU32Le(input, footer)) c.fail("stream footer CRC mismatch", footer)
        if ((readU32Le(input, footer + 4) + 1) * 4 != indexSize.toLong()) c.fail("footer backward size does not match the index", footer + 4)
        if (input[footer + 8] != flags[0] || input[footer + 9] != flags[1]) c.fail("footer flags differ from the header", footer + 8)
        if (input[footer + 10].toInt() != 'Y'.code || input[footer + 11].toInt() != 'Z'.code) c.fail("missing stream footer magic", footer + 10)
        c.pos += 12
    }

    /** Decode one block and return its index record: unpadded size and uncompressed size. */
    private fun decodeBlock(c: Cursor, out: LzOutput, checkId: Int): LongArray {
        val input = c.input
        val blockStart = c.pos
        val headerSize = ((input[c.pos].toInt() and 0xFF) + 1) * 4
        c.need(headerSize)
        if (Crc32().apply { update(input, blockStart, headerSize - 4) }.value() != readU32Le(input, blockStart + headerSize - 4)) {
            c.fail("block header CRC mismatch", blockStart)
        }
        c.pos++
        val flags = c.byte()
        if (flags and 0x3C != 0) c.fail("reserved block flags are set", blockStart + 1)
        val compressedSize = if (flags and 0x40 != 0) c.varint() else -1L
        val uncompressedSize = if (flags and 0x80 != 0) c.varint() else -1L
        val filterCount = (flags and 0x03) + 1
        var dictionarySize = -1L
        val filters = ArrayList<Pair<Long, Int>>() // non-last filters in encoding order: id and delta distance
        for (i in 0 until filterCount) {
            val id = c.varint()
            val propertiesSize = c.varint()
            if (propertiesSize > headerSize) c.fail("filter properties exceed the block header")
            val propertiesStart = c.pos
            c.need(propertiesSize.toInt())
            val last = i == filterCount - 1
            when {
                id == FILTER_LZMA2 && last -> {
                    if (propertiesSize != 1L) c.fail("LZMA2 properties must be one byte")
                    val bits = input[propertiesStart].toInt() and 0xFF
                    if (bits > 40) c.fail("invalid LZMA2 dictionary size", propertiesStart)
                    dictionarySize = lzma2DictionarySize(bits)
                }
                id == FILTER_DELTA && !last -> {
                    if (propertiesSize != 1L) c.fail("delta properties must be one byte")
                    filters.add(FILTER_DELTA to (input[propertiesStart].toInt() and 0xFF) + 1)
                }
                id == FILTER_X86 && !last -> {
                    if (propertiesSize == 4L && readU32Le(input, propertiesStart) != 0L) {
                        throw ArchiveUnsupportedException("xz: x86 filter start offsets are not supported", propertiesStart.toLong())
                    }
                    if (propertiesSize != 0L && propertiesSize != 4L) c.fail("x86 filter properties must be empty or four bytes")
                    filters.add(FILTER_X86 to 0)
                }
                id == FILTER_LZMA2 || id == FILTER_DELTA || id == FILTER_X86 -> c.fail("unsupported filter order")
                id in 0x05L..0x0BL -> throw ArchiveUnsupportedException(
                    "xz: BCJ filter ${BCJ_NAMES[(id - 4).toInt()]} is not supported", propertiesStart.toLong())
                else -> throw ArchiveUnsupportedException("xz: filter 0x${id.toString(16)} is not supported", propertiesStart.toLong())
            }
            c.pos = propertiesStart + propertiesSize.toInt()
        }
        if (dictionarySize < 0) c.fail("block has no LZMA2 filter")
        while (c.pos < blockStart + headerSize - 4) if (c.byte() != 0) c.fail("nonzero block header padding", c.pos - 1)
        if (c.pos != blockStart + headerSize - 4) c.fail("block header fields overrun the header")
        c.pos = blockStart + headerSize

        val dataStart = c.pos
        val outputStart = out.size
        c.pos = Lzma2.decode(input, dataStart, input.size, out, dictionarySize)
        val actualCompressed = (c.pos - dataStart).toLong()
        val actualUncompressed = (out.size - outputStart).toLong()
        if (compressedSize >= 0 && compressedSize != actualCompressed) c.fail("block compressed size differs from its header", blockStart)
        if (uncompressedSize >= 0 && uncompressedSize != actualUncompressed) c.fail("block uncompressed size differs from its header", blockStart)
        // Undo the other filters in reverse order of encoding. Each block starts a fresh dictionary,
        // so rewriting this block's output in place cannot disturb a later block's matches.
        for ((id, distance) in filters.asReversed()) {
            val bytes = out.bytes
            if (id == FILTER_DELTA) {
                for (i in outputStart + distance until out.size) bytes[i] = (bytes[i] + bytes[i - distance]).toByte()
            } else {
                val block = bytes.copyOfRange(outputStart, out.size)
                Bcj.x86Decode(block)
                block.copyInto(bytes, outputStart)
            }
        }
        while ((c.pos - dataStart) % 4 != 0) if (c.byte() != 0) c.fail("nonzero block padding", c.pos - 1)
        val size = checkSize(checkId)
        c.need(size)
        val check = Check(checkId).apply { update(out.bytes, outputStart, out.size - outputStart) }.result()
        for (i in check.indices) if (check[i] != input[c.pos + i]) c.fail("block check mismatch", c.pos)
        c.pos += size
        return longArrayOf(headerSize + actualCompressed + size, actualUncompressed)
    }

    private val BCJ_NAMES = listOf("x86", "PowerPC", "IA-64", "ARM", "ARM-Thumb", "SPARC", "ARM64", "RISC-V")

    /** One stream, one block with an LZMA2 filter and a CRC-64 check, as `xz` writes by default. */
    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(maxOf(64, data.size / 3))
        fun u32(v: Long) { for (i in 0 until 4) out.append((v ushr (8 * i)).toByte()) }
        fun varint(v: Long) { var x = v; while (x >= 0x80) { out.append((x or 0x80).toByte()); x = x ushr 7 }; out.append(x.toByte()) }
        val flags = byteArrayOf(0, CHECK_CRC64.toByte())
        out.append(MAGIC); out.append(flags); u32(Crc32.of(flags))

        // Empty input gets no block at all, as xz writes it.
        var compressed = -1
        if (data.isNotEmpty()) {
            val dictionary = writerDictionarySize(data.size)
            val header = byteArrayOf(2, 0, FILTER_LZMA2.toByte(), 1, lzma2DictionaryBits(dictionary).toByte(), 0, 0, 0)
            out.append(header); u32(Crc32.of(header))
            val dataStart = out.size
            Lzma2.encode(data, dictionary, out)
            compressed = out.size - dataStart
            while ((out.size - dataStart) % 4 != 0) out.append(0)
            val check = Crc64.of(data)
            for (i in 0 until 8) out.append((check ushr (8 * i)).toByte())
        }

        val indexStart = out.size
        out.append(0)
        if (compressed < 0) varint(0) else { varint(1); varint(12L + compressed + 8); varint(data.size.toLong()) }
        while ((out.size - indexStart) % 4 != 0) out.append(0)
        val index = out.toByteArray().copyOfRange(indexStart, out.size)
        u32(Crc32.of(index))
        val backward = byteArrayOf(0, 0, 0, 0, 0, CHECK_CRC64.toByte())
        val backwardSize = (out.size - indexStart) / 4 - 1
        for (i in 0 until 4) backward[i] = (backwardSize ushr (8 * i)).toByte()
        u32(Crc32.of(backward)); out.append(backward)
        out.append('Y'.code.toByte()); out.append('Z'.code.toByte())
        return out.takeByteArray()
    }
}
