package io.github.yuroyami.archivekodec.codec.lzma

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.SourceBuffer
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.readI32Le

/**
 * The lzip format (`.lz`, version 1): members of a 6-byte header (`LZIP`, version 1 and a coded
 * dictionary size), an LZMA stream with lc 3, lp 0 and pb 2 that ends with an end marker, and a
 * 20-byte trailer: the CRC-32 of the data, the data size and the member size. Members may be
 * concatenated; their data is joined.
 */
internal object Lzip {
    private const val HEADER = 6
    private const val TRAILER = 20
    private val PROPERTIES = LzmaProperties(3, 0, 2)

    fun isLzip(bytes: ByteArray): Boolean = bytes.size >= 5 &&
        bytes[0] == 'L'.code.toByte() && bytes[1] == 'Z'.code.toByte() && bytes[2] == 'I'.code.toByte() && bytes[3] == 'P'.code.toByte()

    fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        limits.checkOutputSize(0)
        val out = ByteArrayBuilder(maxOf(256, minOf(input.size * 3, 1 shl 20)))
        var pos = 0
        do {
            if (input.size - pos < HEADER + 5 + TRAILER || !isLzip(input.copyOfRange(pos, pos + 5))) {
                throw ArchiveFormatException("lzip: missing or truncated member header", pos.toLong())
            }
            val version = input[pos + 4].toInt() and 0xFF
            if (version != 1) throw ArchiveUnsupportedException("lzip: version $version is not supported", pos + 4L)
            val dictionary = dictionarySize(input[pos + 5].toInt() and 0xFF, pos + 5L)
            // The member's own data, decoded under the limits that remain for the whole stream.
            val remaining = limits.copy { maxUncompressedBytes = limits.maxUncompressedBytes - out.size }
            val member = LzOutput(remaining, input.size - pos)
            val rc = RangeDecoder(input, pos + HEADER, input.size)
            if (!decodeLzma(rc, LzmaModel(PROPERTIES), member, -1, dictionary, markerAllowed = true)) {
                throw ArchiveFormatException("lzip: the LZMA stream has no end marker", pos.toLong())
            }
            val trailer = rc.position
            if (input.size - trailer < TRAILER) throw ArchiveFormatException("lzip: truncated member trailer", trailer.toLong())
            val data = member.take()
            val crc = readI32Le(input, trailer).toLong() and 0xFFFFFFFFL
            val dataSize = readU64(input, trailer + 4)
            val memberSize = readU64(input, trailer + 12)
            if (crc != Crc32.of(data)) throw ArchiveFormatException("lzip: CRC-32 mismatch", trailer.toLong())
            if (dataSize != data.size.toLong()) throw ArchiveFormatException("lzip: data size ${data.size} does not match the trailer's $dataSize", trailer + 4L)
            if (memberSize != (trailer + TRAILER - pos).toLong()) throw ArchiveFormatException("lzip: member size does not match the trailer", trailer + 12L)
            limits.checkOutputSize(out.size.toLong() + data.size)
            limits.checkRatio(input.size.toLong(), out.size.toLong() + data.size)
            out.append(data)
            pos = trailer + TRAILER
        } while (pos < input.size)
        return out.takeByteArray()
    }

    /**
     * Decode every member read from [source] to [sink] and return the byte count. Output leaves
     * through a window of the member's dictionary, which [ArchiveLimits.maxDecoderMemory]
     * bounds, and the ratio is measured against the compressed bytes read so far.
     */
    fun decompress(source: ByteSource, sink: ByteSink, limits: ArchiveLimits): Long {
        val input = SourceBuffer(source)
        val begin = input.streamPosition
        val crc = Crc32()
        val out = LzOutput(limits, Int.MAX_VALUE, sink = sink)
        // While a member decodes, its range decoder holds the read position, not the buffer.
        var rc: RangeDecoder? = null
        out.compressedProgress = { (rc?.offset ?: input.streamPosition) - begin }
        out.onWrite = { b, o, n -> crc.update(b, o, n) }
        val header = ByteArray(TRAILER)
        var members = 0
        while (members == 0 || !input.atEnd()) {
            val start = input.streamPosition
            if (!input.ensure(HEADER + 5 + TRAILER) || !isLzip(input.buffer.copyOfRange(input.position, input.position + 5))) {
                throw ArchiveFormatException("lzip: missing or truncated member header", start)
            }
            input.readFully(header, 0, HEADER, "lzip header")
            val version = header[4].toInt() and 0xFF
            if (version != 1) throw ArchiveUnsupportedException("lzip: version $version is not supported", start + 4)
            val dictionary = dictionarySize(header[5].toInt() and 0xFF, start + 5)
            if (dictionary > limits.maxDecoderMemory) {
                throw DecompressionBombException("lzip: a $dictionary B dictionary exceeds the decoder memory limit ${limits.maxDecoderMemory} B")
            }
            out.dropHistory()
            out.window = dictionary.toInt()
            crc.reset()
            val outStart = out.produced
            val decoder = RangeDecoder(input)
            rc = decoder
            if (!decodeLzma(decoder, LzmaModel(PROPERTIES), out, -1, dictionary, markerAllowed = true)) {
                throw ArchiveFormatException("lzip: the LZMA stream has no end marker", start)
            }
            decoder.release()
            rc = null
            out.flush()
            val trailer = input.streamPosition
            if (!input.ensure(TRAILER)) throw ArchiveFormatException("lzip: truncated member trailer", trailer)
            input.readFully(header, 0, TRAILER, "lzip trailer")
            val size = out.produced - outStart
            if ((readI32Le(header, 0).toLong() and 0xFFFFFFFFL) != crc.value()) throw ArchiveFormatException("lzip: CRC-32 mismatch", trailer)
            if (readU64(header, 4) != size) throw ArchiveFormatException("lzip: data size $size does not match the trailer's ${readU64(header, 4)}", trailer + 4)
            if (readU64(header, 12) != trailer + TRAILER - start) throw ArchiveFormatException("lzip: member size does not match the trailer", trailer + 12)
            members++
        }
        return out.produced
    }

    fun compress(data: ByteArray): ByteArray {
        // A power of two that covers the data, from 4 KiB up to WRITER_MAX_DICTIONARY like the other LZMA writers.
        var bits = 12
        while ((1 shl bits) < WRITER_MAX_DICTIONARY && (1 shl bits) < data.size) bits++
        val dictionary = 1 shl bits
        val out = ByteArrayBuilder(maxOf(64, data.size / 3))
        "LZIP".encodeToByteArray().forEach { out.append(it) }
        out.append(1)
        out.append(bits.toByte())
        val rc = RangeEncoder(out)
        val encoder = LzmaEncoder(data, PROPERTIES, dictionary)
        encoder.encode(rc, 0, data.size)
        encoder.encodeEndMarker(rc, data.size)
        rc.flush()
        val crc = Crc32.of(data)
        for (i in 0 until 4) out.append((crc ushr (8 * i)).toByte())
        for (i in 0 until 8) out.append((data.size.toLong() ushr (8 * i)).toByte())
        val memberSize = out.size.toLong() + 8
        for (i in 0 until 8) out.append((memberSize ushr (8 * i)).toByte())
        return out.toByteArray()
    }

    /** Bits 4 to 0 give a power of two from 2^12 to 2^29; bits 7 to 5 subtract that many sixteenths. */
    private fun dictionarySize(code: Int, offset: Long): Long {
        val bits = code and 0x1F
        if (bits !in 12..29) throw ArchiveFormatException("lzip: invalid dictionary size code $code", offset)
        val base = 1L shl bits
        val size = base - (base / 16) * (code ushr 5)
        if (size < 4096) throw ArchiveFormatException("lzip: dictionary smaller than 4 KiB", offset)
        return size
    }

    private fun readU64(bytes: ByteArray, at: Int): Long =
        (readI32Le(bytes, at).toLong() and 0xFFFFFFFFL) or (readI32Le(bytes, at + 4).toLong() shl 32)
}
