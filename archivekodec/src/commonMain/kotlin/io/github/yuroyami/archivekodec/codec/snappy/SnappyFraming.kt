package io.github.yuroyami.archivekodec.codec.snappy

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.Crc32c
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.le32
import io.github.yuroyami.archivekodec.util.leBytes
import io.github.yuroyami.archivekodec.util.readI32Le
import io.github.yuroyami.archivekodec.util.readUIntLe

/** Standard Snappy framing: independent 64 KiB chunks with masked CRC-32C. */
internal object SnappyFraming {
    private const val MAX_CHUNK_OUTPUT = 65536
    private val identifier = byteArrayOf(-1, 6, 0, 0, 0x73, 0x4e, 0x61, 0x50, 0x70, 0x59)

    internal fun hasIdentifier(data: ByteArray, offset: Int = 0): Boolean {
        if (offset < 0 || offset > data.size || data.size - offset < identifier.size) return false
        return identifier.indices.all { data[offset + it] == identifier[it] }
    }

    internal fun decompress(src: ByteArray, limits: ArchiveLimits): ByteArray {
        if (!hasIdentifier(src)) throw ArchiveFormatException("snappy frame: missing stream identifier", 0)
        val maximum = minOf(limits.maxUncompressedBytes, Int.MAX_VALUE.toLong()).toInt()
        val out = ByteArrayBuilder(minOf(src.size, MAX_CHUNK_OUTPUT), maximum)
        fun checkSize(size: Long, offset: Int) {
            if (size > MAX_CHUNK_OUTPUT) {
                throw ArchiveFormatException("snappy frame: chunk output exceeds 65536 bytes", offset.toLong())
            }
            val total = out.size.toLong() + size
            limits.checkOutputSize(total)
            limits.checkRatio(src.size.toLong(), total)
            if (total > Int.MAX_VALUE) {
                throw DecompressionBombException("snappy frame: output exceeds ByteArray maximum ${Int.MAX_VALUE}")
            }
        }
        var position = 0
        while (position < src.size) {
            val header = position
            if (src.size - position < 4) {
                throw ArchiveFormatException("snappy frame: truncated chunk header", header.toLong())
            }
            val type = src[position].toInt() and 0xff
            val length = readUIntLe(src, position + 1, 3).toInt()
            position += 4
            if (length > src.size - position) {
                throw ArchiveFormatException("snappy frame: truncated chunk payload", position.toLong())
            }
            val end = position + length
            when (type) {
                0xff -> if (length != 6 || !hasIdentifier(src, header)) {
                    throw ArchiveFormatException("snappy frame: invalid stream identifier", header.toLong())
                }
                0, 1 -> {
                    if (length < 4) {
                        throw ArchiveFormatException("snappy frame: missing chunk checksum", position.toLong())
                    }
                    val expected = readI32Le(src, position)
                    val payload = position + 4
                    if (type == 0) {
                        val decoded = Snappy.decompressBlock(src, payload, end) { checkSize(it, payload) }
                        verifyChecksum(expected, decoded, 0, decoded.size, position)
                        out.append(decoded)
                    } else {
                        checkSize((length - 4).toLong(), payload)
                        verifyChecksum(expected, src, payload, length - 4, position)
                        out.append(src, payload, length - 4)
                    }
                }
                in 2..0x7f -> throw ArchiveUnsupportedException(
                    "snappy frame: reserved unskippable chunk type $type", header.toLong(),
                )
                // 0x80..0xfe are skippable, including padding; do not inspect their bytes.
            }
            position = end
        }
        return out.toByteArray()
    }

    internal fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(minOf(data.size, MAX_CHUNK_OUTPUT) + identifier.size)
        out.append(identifier)
        var position = 0
        while (position < data.size) {
            val length = minOf(data.size - position, MAX_CHUNK_OUTPUT)
            val chunk = data.copyOfRange(position, position + length)
            val compressed = Snappy.compress(chunk)
            val stored = compressed.size >= length
            val payload = if (stored) chunk else compressed
            out.append((if (stored) 1 else 0).toByte())
            out.leBytes((payload.size + 4).toLong(), 3)
            out.le32(maskedChecksum(chunk, 0, length).toLong())
            out.append(payload)
            position += length
        }
        return out.toByteArray()
    }

    private fun maskedChecksum(data: ByteArray, offset: Int, length: Int): Int {
        val crc = Crc32c().apply { update(data, offset, length) }.value().toInt()
        return ((crc ushr 15) or (crc shl 17)) + 0xa282ead8.toInt()
    }

    private fun verifyChecksum(expected: Int, data: ByteArray, offset: Int, length: Int, sourceOffset: Int) {
        if (expected != maskedChecksum(data, offset, length)) {
            throw ArchiveFormatException("snappy frame: CRC-32C mismatch", sourceOffset.toLong())
        }
    }
}
