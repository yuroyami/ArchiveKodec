package io.github.yuroyami.archivekodec.codec.lzo

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.Adler32
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * The lzop format (`.lzo`): a 9-byte magic, a header with its own Adler-32 or CRC-32, then
 * blocks of an uncompressed and a compressed size, optional checksums of either side, and the
 * data, which is LZO1X unless both sizes are equal; a zero size ends the file. Filters and
 * multi-part files are refused.
 */
internal object Lzop {
    private val MAGIC = byteArrayOf(0x89.toByte(), 0x4C, 0x5A, 0x4F, 0x00, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val F_ADLER32_D = 0x0001
    private const val F_ADLER32_C = 0x0002
    private const val F_H_EXTRA_FIELD = 0x0040
    private const val F_CRC32_D = 0x0100
    private const val F_CRC32_C = 0x0200
    private const val F_MULTIPART = 0x0400
    private const val F_H_FILTER = 0x0800
    private const val F_H_CRC32 = 0x1000
    private const val F_OS_UNIX = 0x03000000
    private const val MAX_BLOCK = 64 shl 20
    private const val BLOCK = 256 shl 10

    fun isLzop(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        limits.checkOutputSize(0)
        if (!isLzop(input)) throw ArchiveFormatException("lzop: missing magic", 0)
        var pos = MAGIC.size
        fun need(count: Int) { if (input.size - pos < count) throw ArchiveFormatException("lzop: truncated", pos.toLong()) }
        fun u8(): Int { need(1); return input[pos++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        val headerStart = pos
        val version = u16()
        u16() // library version
        if (version >= 0x0940) {
            val needed = u16()
            if (needed > 0x1040) throw ArchiveUnsupportedException("lzop: version ${needed.toString(16)} is needed to extract", pos - 2L)
        }
        val method = u8()
        if (method !in 1..3) throw ArchiveUnsupportedException("lzop: method $method is not LZO1X", pos - 1L)
        if (version >= 0x0940) u8() // level
        val flags = u32().toInt()
        if (flags and F_H_FILTER != 0) throw ArchiveUnsupportedException("lzop: filters are not supported", pos - 4L)
        if (flags and F_MULTIPART != 0) throw ArchiveUnsupportedException("lzop: multi-part files are not supported", pos - 4L)
        u32() // mode
        u32() // modification time, low
        if (version >= 0x0940) u32() // modification time, high
        val nameLength = u8()
        need(nameLength)
        pos += nameLength
        val headerSum = checksum(flags and F_H_CRC32 != 0, input, headerStart, pos)
        val storedHeaderSum = u32()
        if (storedHeaderSum != headerSum) throw ArchiveFormatException("lzop: header checksum mismatch", pos - 4L)
        if (flags and F_H_EXTRA_FIELD != 0) {
            val length = u32()
            if (length > input.size - pos - 4L) throw ArchiveFormatException("lzop: extra field passes the end", pos.toLong())
            pos += length.toInt() + 4
        }

        val out = ByteArrayBuilder(minOf(maxOf(256, input.size * 3), 1 shl 20))
        while (true) {
            val blockStart = pos
            val size = u32()
            if (size == 0L) break
            if (size > MAX_BLOCK) throw ArchiveFormatException("lzop: block of $size bytes is larger than 64 MiB", blockStart.toLong())
            val packed = u32()
            if (packed > size) throw ArchiveFormatException("lzop: compressed block is larger than its data", blockStart + 4L)
            val dataAdler = if (flags and F_ADLER32_D != 0) u32() else null
            val dataCrc = if (flags and F_CRC32_D != 0) u32() else null
            val compressed = packed < size
            val packedAdler = if (compressed && flags and F_ADLER32_C != 0) u32() else null
            val packedCrc = if (compressed && flags and F_CRC32_C != 0) u32() else null
            need(packed.toInt())
            val total = out.size.toLong() + size
            limits.checkOutputSize(total)
            limits.checkRatio(input.size.toLong(), total)
            val from = pos
            pos += packed.toInt()
            if (packedAdler != null && packedAdler != checksum(false, input, from, pos)) throw ArchiveFormatException("lzop: compressed block Adler-32 mismatch", from.toLong())
            if (packedCrc != null && packedCrc != checksum(true, input, from, pos)) throw ArchiveFormatException("lzop: compressed block CRC-32 mismatch", from.toLong())
            val block = if (compressed) Lzo1x.decode(input, from, pos, size.toInt()) else input.copyOfRange(from, pos)
            if (block.size.toLong() != size) throw ArchiveFormatException("lzop: block decodes to ${block.size} bytes, not $size", from.toLong())
            if (dataAdler != null && dataAdler != checksum(false, block, 0, block.size)) throw ArchiveFormatException("lzop: block Adler-32 mismatch", from.toLong())
            if (dataCrc != null && dataCrc != checksum(true, block, 0, block.size)) throw ArchiveFormatException("lzop: block CRC-32 mismatch", from.toLong())
            out.append(block)
        }
        if (pos != input.size) throw ArchiveFormatException("lzop: ${input.size - pos} bytes follow the end", pos.toLong())
        return out.takeByteArray()
    }

    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(maxOf(64, data.size / 2 + 64))
        out.append(MAGIC)
        val headerStart = out.size
        fun u16(v: Int) { out.append((v ushr 8).toByte()); out.append(v.toByte()) }
        fun u32(v: Long) { u16((v ushr 16).toInt() and 0xFFFF); u16(v.toInt() and 0xFFFF) }
        u16(0x1040) // lzop version
        u16(0x20A0) // LZO library version
        u16(0x0940) // version needed to extract
        out.append(1) // LZO1X-1
        out.append(5) // level
        u32((F_ADLER32_D or F_OS_UNIX).toLong())
        u32(0x81A4) // mode 0100644
        u32(0); u32(0) // modification time
        out.append(0) // no name
        val header = out.toByteArray()
        u32(checksum(false, header, headerStart, header.size))
        var at = 0
        while (at < data.size) {
            val end = minOf(at + BLOCK, data.size)
            val block = data.copyOfRange(at, end)
            val packed = Lzo1x.encode(block)
            u32(block.size.toLong())
            val stored = packed.size >= block.size
            u32(if (stored) block.size.toLong() else packed.size.toLong())
            u32(checksum(false, block, 0, block.size))
            out.append(if (stored) block else packed)
            at = end
        }
        u32(0)
        return out.takeByteArray()
    }

    private fun checksum(crc: Boolean, bytes: ByteArray, from: Int, to: Int): Long =
        if (crc) Crc32().apply { update(bytes, from, to - from) }.value() else Adler32().apply { update(bytes, from, to - from) }.value()
}
