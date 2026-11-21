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
 * streams and stream padding are decoded, as are the delta filter and the x86, PowerPC,
 * IA-64, ARM, ARM-Thumb, SPARC and ARM64 branch filters, with or without a start offset.
 * RISC-V is refused by name.
 */
internal object Xz {
    private val MAGIC = byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0)
    private const val CHECK_NONE = 0
    private const val CHECK_CRC32 = 1
    private const val CHECK_CRC64 = 4
    private const val CHECK_SHA256 = 10
    private const val FILTER_DELTA = 0x03L
    private const val FILTER_X86 = 0x04L
    private const val FILTER_ARM64 = 0x0AL

    /** Undo branch filter [id] (0x04 to 0x0A) on [block], which starts at stream position [start]. */
    private fun branchDecode(id: Long, block: ByteArray, start: Int) = when (id) {
        0x04L -> Bcj.x86Decode(block, start)
        0x05L -> Bcj.powerPcDecode(block, start)
        0x06L -> Bcj.ia64Decode(block, start)
        0x07L -> Bcj.armDecode(block, start)
        0x08L -> Bcj.armThumbDecode(block, start)
        0x09L -> Bcj.sparcDecode(block, start)
        else -> Bcj.arm64Decode(block, start)
    }
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

    /**
     * Decode every stream read from [source] to [sink] and return the byte count. Input is read
     * as it is needed and output leaves through a window of the block's dictionary size, so
     * memory stays near the dictionary whatever the output's size. A block with a branch filter
     * is the exception: it is held whole until its end, since those filters rewrite instructions
     * that may straddle any boundary. The delta filter streams.
     */
    fun decompress(source: io.github.yuroyami.archivekodec.codec.ByteSource, sink: io.github.yuroyami.archivekodec.codec.ByteSink, limits: ArchiveLimits): Long {
        val input = io.github.yuroyami.archivekodec.codec.SourceBuffer(source)
        val begin = input.streamPosition
        var blockSink: io.github.yuroyami.archivekodec.codec.ByteSink = sink
        val out = LzOutput(limits, Int.MAX_VALUE, sink = { b, o, n -> blockSink.write(b, o, n) })
        out.compressedProgress = { input.streamPosition - begin }
        var streams = 0
        var written = 0L
        while (true) {
            if (streams > 0) {
                var zeros = 0
                while (input.ensure(1) && input.buffer[input.position].toInt() == 0) { input.position++; zeros++ }
                if (zeros % 4 != 0) throw ArchiveFormatException("xz: stream padding is not a multiple of four", input.streamPosition - zeros)
                if (input.atEnd()) break
            } else if (input.atEnd()) {
                throw ArchiveFormatException("xz: empty input", 0)
            }
            written += decodeStream(input, out, sink, limits) { blockSink = it }
            streams++
        }
        return written
    }

    /** One stream from [input]; [route] points the output at each block's filters and check. */
    private fun decodeStream(
        input: io.github.yuroyami.archivekodec.codec.SourceBuffer, out: LzOutput,
        sink: io.github.yuroyami.archivekodec.codec.ByteSink, limits: ArchiveLimits,
        route: (io.github.yuroyami.archivekodec.codec.ByteSink) -> Unit,
    ): Long {
        val start = input.streamPosition
        val head = ByteArray(12)
        input.readFully(head, 0, 12, "xz stream header")
        for (i in MAGIC.indices) if (head[i] != MAGIC[i]) throw ArchiveFormatException("xz: missing stream header magic", start)
        val flags = head.copyOfRange(6, 8)
        if (flags[0].toInt() != 0 || flags[1].toInt() and 0xF0 != 0) throw ArchiveFormatException("xz: reserved stream flags are set", start + 6)
        if (Crc32.of(flags) != readU32Le(head, 8)) throw ArchiveFormatException("xz: stream header CRC mismatch", start + 8)
        val checkId = flags[1].toInt() and 0x0F
        if (checkId != CHECK_NONE && checkId != CHECK_CRC32 && checkId != CHECK_CRC64 && checkId != CHECK_SHA256) {
            throw ArchiveUnsupportedException("xz: check type $checkId is not supported", start + 7)
        }
        val records = ArrayList<LongArray>()
        var written = 0L
        while (true) {
            val blockStart = input.streamPosition
            val first = input.read()
            if (first < 0) throw ArchiveFormatException("xz: truncated", blockStart)
            if (first == 0) break
            val headerSize = (first + 1) * 4
            val header = ByteArray(headerSize)
            header[0] = first.toByte()
            input.readFully(header, 1, headerSize - 1, "xz block header")
            if (Crc32().apply { update(header, 0, headerSize - 4) }.value() != readU32Le(header, headerSize - 4)) {
                throw ArchiveFormatException("xz: block header CRC mismatch", blockStart)
            }
            val h = try {
                parseBlockHeader(Cursor(header, 0), 0, headerSize)
            } catch (error: ArchiveFormatException) {
                throw ArchiveFormatException(error.message.orEmpty().substringBefore(" ["), blockStart + (error.offset ?: 0))
            }
            if (h.dictionarySize > limits.maxDecoderMemory) {
                throw io.github.yuroyami.archivekodec.security.DecompressionBombException(
                    "xz: a ${h.dictionarySize} B dictionary exceeds the decoder memory limit ${limits.maxDecoderMemory} B")
            }
            // The block's own output path: filters first, then the check, then the caller's sink.
            val check = Check(checkId)
            var blockOutput = 0L
            val delta = h.filters.firstOrNull { it.first == FILTER_DELTA }?.second
            val branch = h.filters.any { it.first != FILTER_DELTA }
            val held = if (branch) io.github.yuroyami.archivekodec.util.ByteArrayBuilder(1 shl 16) else null
            val history = if (delta != null) ByteArray(delta) else null
            var historyAt = 0L
            route { bytes, offset, length ->
                if (held != null) { held.append(bytes, offset, length); return@route }
                var data = bytes
                var at = offset
                if (history != null) {
                    // Delta, streamed: each byte adds the byte `distance` before it. The bytes are
                    // the decoder's window, which later matches copy from, so transform a copy.
                    data = bytes.copyOfRange(offset, offset + length)
                    at = 0
                    val distance = history.size
                    for (i in 0 until length) {
                        val slot = (historyAt % distance).toInt()
                        val value = (data[i] + history[slot]).toByte()
                        data[i] = value
                        history[slot] = value
                        historyAt++
                    }
                }
                check.update(data, at, length)
                sink.write(data, at, length)
                blockOutput += length
            }
            out.dropHistory()
            out.window = maxOf(h.dictionarySize, 4096L).coerceAtMost(Int.MAX_VALUE - 8L).toInt()
            val outStart = out.produced
            val compressed = Lzma2.decode(input, out, h.dictionarySize)
            out.flush()
            val uncompressed = out.produced - outStart
            if (h.compressedSize >= 0 && h.compressedSize != compressed) throw ArchiveFormatException("xz: block compressed size differs from its header", blockStart)
            if (h.uncompressedSize >= 0 && h.uncompressedSize != uncompressed) throw ArchiveFormatException("xz: block uncompressed size differs from its header", blockStart)
            if (held != null) {
                val block = held.takeByteArray()
                for ((id, distance) in h.filters.asReversed()) {
                    if (id == FILTER_DELTA) {
                        for (i in distance until block.size) block[i] = (block[i] + block[i - distance]).toByte()
                    } else {
                        branchDecode(id, block, distance)
                    }
                }
                check.update(block, 0, block.size)
                sink.write(block, 0, block.size)
                blockOutput += block.size
            }
            written += blockOutput
            var padding = compressed
            while (padding % 4 != 0L) {
                if (input.read() != 0) throw ArchiveFormatException("xz: nonzero block padding", input.streamPosition - 1)
                padding++
            }
            val size = checkSize(checkId)
            val stored = ByteArray(size)
            input.readFully(stored, 0, size, "xz block check")
            val expected = check.result()
            for (i in expected.indices) if (expected[i] != stored[i]) throw ArchiveFormatException("xz: block check mismatch", input.streamPosition - size)
            records.add(longArrayOf(headerSize + compressed + size, uncompressed))
        }

        // Index, read with a running CRC-32 from its indicator byte.
        val indexStart = input.streamPosition - 1
        val indexCrc = Crc32().apply { update(byteArrayOf(0), 0, 1) }
        var indexSize = 1L
        fun indexByte(): Int {
            val b = input.read()
            if (b < 0) throw ArchiveFormatException("xz: truncated index", input.streamPosition)
            indexCrc.update(byteArrayOf(b.toByte()), 0, 1)
            indexSize++
            return b
        }
        fun varint(): Long {
            var value = 0L
            for (i in 0 until 9) {
                val b = indexByte()
                if (b == 0 && i > 0) throw ArchiveFormatException("xz: non-minimal integer", input.streamPosition - 1)
                value = value or ((b and 0x7F).toLong() shl (7 * i))
                if (b and 0x80 == 0) return value
            }
            throw ArchiveFormatException("xz: integer exceeds 63 bits", input.streamPosition)
        }
        val count = varint()
        if (count != records.size.toLong()) throw ArchiveFormatException("xz: index lists $count blocks, stream has ${records.size}", indexStart)
        for (record in records) {
            if (varint() != record[0] || varint() != record[1]) throw ArchiveFormatException("xz: index record does not match its block", indexStart)
        }
        while (indexSize % 4 != 0L) if (indexByte() != 0) throw ArchiveFormatException("xz: nonzero index padding", input.streamPosition - 1)
        val crcBytes = ByteArray(4)
        input.readFully(crcBytes, 0, 4, "xz index CRC")
        if (indexCrc.value() != readU32Le(crcBytes, 0)) throw ArchiveFormatException("xz: index CRC mismatch", input.streamPosition - 4)
        indexSize += 4
        val footer = ByteArray(12)
        val footerAt = input.streamPosition
        input.readFully(footer, 0, 12, "xz stream footer")
        if (Crc32().apply { update(footer, 4, 6) }.value() != readU32Le(footer, 0)) throw ArchiveFormatException("xz: stream footer CRC mismatch", footerAt)
        if ((readU32Le(footer, 4) + 1) * 4 != indexSize) throw ArchiveFormatException("xz: footer backward size does not match the index", footerAt + 4)
        if (footer[8] != flags[0] || footer[9] != flags[1]) throw ArchiveFormatException("xz: footer flags differ from the header", footerAt + 8)
        if (footer[10].toInt() != 'Y'.code || footer[11].toInt() != 'Z'.code) throw ArchiveFormatException("xz: missing stream footer magic", footerAt + 10)
        return written
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
        val h = parseBlockHeader(c, blockStart, headerSize)
        val compressedSize = h.compressedSize
        val uncompressedSize = h.uncompressedSize
        val dictionarySize = h.dictionarySize
        val filters = h.filters
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
                branchDecode(id, block, distance)
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


    /** A parsed block header: sizes (-1 when absent), the LZMA2 dictionary and the other filters. */
    internal class BlockHeader(val compressedSize: Long, val uncompressedSize: Long, val dictionarySize: Long, val filters: List<Pair<Long, Int>>)

    /** Parse the block header at [blockStart], whose CRC-32 was checked; leaves [c] after it. */
    private fun parseBlockHeader(c: Cursor, blockStart: Int, headerSize: Int): BlockHeader {
        val input = c.input
        c.pos = blockStart
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
                id in FILTER_X86..FILTER_ARM64 && !last -> {
                    if (propertiesSize != 0L && propertiesSize != 4L) c.fail("${BCJ_NAMES[(id - 4).toInt()]} filter properties must be empty or four bytes")
                    val start = if (propertiesSize == 4L) readU32Le(input, propertiesStart) else 0L
                    if (start > Int.MAX_VALUE) throw ArchiveUnsupportedException("xz: a filter start offset of $start is not supported", propertiesStart.toLong())
                    filters.add(id to start.toInt())
                }
                id == FILTER_LZMA2 || id == FILTER_DELTA || id in FILTER_X86..FILTER_ARM64 -> c.fail("unsupported filter order")
                id == 0x0BL -> throw ArchiveUnsupportedException(
                    "xz: BCJ filter ${BCJ_NAMES[(id - 4).toInt()]} is not supported", propertiesStart.toLong())
                else -> throw ArchiveUnsupportedException("xz: filter 0x${id.toString(16)} is not supported", propertiesStart.toLong())
            }
            c.pos = propertiesStart + propertiesSize.toInt()
        }
        if (dictionarySize < 0) c.fail("block has no LZMA2 filter")
        while (c.pos < blockStart + headerSize - 4) if (c.byte() != 0) c.fail("nonzero block header padding", c.pos - 1)
        if (c.pos != blockStart + headerSize - 4) c.fail("block header fields overrun the header")
        c.pos = blockStart + headerSize
        return BlockHeader(compressedSize, uncompressedSize, dictionarySize, filters)
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

    /**
     * Streaming xz: input is cut into blocks of [BLOCK] bytes, each compressed and written with
     * its CRC-64 as soon as it is full, and the index and footer follow at [EncoderSession.finish].
     * Memory stays near one block. Each block starts a fresh dictionary, which costs a little
     * compression against one block of everything, as `xz -T` does too.
     */
    internal class EncoderSession(private val sink: io.github.yuroyami.archivekodec.codec.ByteSink) : io.github.yuroyami.archivekodec.codec.EncoderSession {
        private val block = ByteArray(BLOCK)
        private var size = 0
        private val records = ArrayList<LongArray>()
        private var finished = false

        init {
            val flags = byteArrayOf(0, CHECK_CRC64.toByte())
            val head = MAGIC + flags + le32(Crc32.of(flags))
            sink.write(head, 0, head.size)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!finished) { "the session is finished" }
            require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) { "invalid slice" }
            var at = offset
            val end = offset + length
            while (at < end) {
                val n = minOf(end - at, block.size - size)
                bytes.copyInto(block, size, at, at + n)
                size += n
                at += n
                if (size == block.size) emit()
            }
        }

        /** xz blocks end only when full or flushed: flushing writes the pending input as a block. */
        override fun flush() {
            check(!finished) { "the session is finished" }
            if (size > 0) emit()
        }

        override fun finish() {
            if (finished) return
            finished = true
            if (size > 0) emit()
            val index = ByteArrayBuilder(64)
            index.append(0)
            fun varint(v: Long) { var x = v; while (x >= 0x80) { index.append((x or 0x80).toByte()); x = x ushr 7 }; index.append(x.toByte()) }
            varint(records.size.toLong())
            for (record in records) { varint(record[0]); varint(record[1]) }
            while (index.size % 4 != 0) index.append(0)
            val indexBytes = index.takeByteArray()
            val backward = byteArrayOf(0, 0, 0, 0, 0, CHECK_CRC64.toByte())
            val backwardSize = (indexBytes.size + 4) / 4 - 1
            for (i in 0 until 4) backward[i] = (backwardSize ushr (8 * i)).toByte()
            val tail = indexBytes + le32(Crc32.of(indexBytes)) + le32(Crc32.of(backward)) + backward + byteArrayOf('Y'.code.toByte(), 'Z'.code.toByte())
            sink.write(tail, 0, tail.size)
        }

        private fun emit() {
            val data = block.copyOf(size)
            val dictionary = writerDictionarySize(data.size)
            val header = byteArrayOf(2, 0, FILTER_LZMA2.toByte(), 1, lzma2DictionaryBits(dictionary).toByte(), 0, 0, 0)
            val out = ByteArrayBuilder(maxOf(64, data.size / 3))
            out.append(header); out.append(le32(Crc32.of(header)))
            val dataStart = out.size
            Lzma2.encode(data, dictionary, out)
            val compressed = out.size - dataStart
            while ((out.size - dataStart) % 4 != 0) out.append(0)
            val check = Crc64.of(data)
            for (i in 0 until 8) out.append((check ushr (8 * i)).toByte())
            val bytes = out.takeByteArray()
            sink.write(bytes, 0, bytes.size)
            records.add(longArrayOf(12L + compressed + 8, data.size.toLong()))
            size = 0
        }

        private fun le32(v: Long) = ByteArray(4) { (v ushr (8 * it)).toByte() }
    }

    private const val BLOCK = 2 shl 20
}
