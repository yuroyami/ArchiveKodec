package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.tar.TarHeader

/**
 * Magic-byte format sniffer. It reads a header, or the first 512 bytes of a
 * [RandomAccessSource], and returns the matching [ArchiveFormat]. Raw Snappy and
 * Brotli have no identifying magic bytes. LZMA-alone headers are too weak to
 * identify reliably here, and Snappy framing is not supported, so this object
 * never returns SNAPPY, BROTLI or LZMA. A match identifies a format
 * candidate; readers and codecs validate its complete structure and payload.
 */
public object Magic {

    public fun sniff(header: ByteArray): ArchiveFormat {
        fun at(i: Int): Int = if (i < header.size) header[i].toInt() and 0xFF else -1
        return when {
            // A TAR filename can begin with any of the shorter format signatures.
            TarHeader.hasValidChecksum(header) || TarHeader.isZeroRecord(header) -> ArchiveFormat.TAR
            at(0) == 0x1F && at(1) == 0x8B -> ArchiveFormat.GZIP
            at(0) == 0x50 && at(1) == 0x4B && (
                (at(2) == 0x03 && at(3) == 0x04) || // local file header
                (at(2) == 0x05 && (at(3) == 0x05 || at(3) == 0x06)) || // digital signature / EOCD
                (at(2) == 0x06 && at(3) == 0x06) || // empty ZIP64 starts with ZIP64 EOCD
                (at(2) == 0x07 && at(3) == 0x08) // descriptor / spanning marker
            ) ->
                ArchiveFormat.ZIP
            at(0) == 0xFD && at(1) == 0x37 && at(2) == 0x7A && at(3) == 0x58 && at(4) == 0x5A && at(5) == 0x00 ->
                ArchiveFormat.XZ
            at(0) == 0x28 && at(1) == 0xB5 && at(2) == 0x2F && at(3) == 0xFD -> ArchiveFormat.ZSTD
            at(0) == 0x04 && at(1) == 0x22 && at(2) == 0x4D && at(3) == 0x18 -> ArchiveFormat.LZ4
            isBzip2(header) -> ArchiveFormat.BZIP2
            at(0) == 0x37 && at(1) == 0x7A && at(2) == 0xBC && at(3) == 0xAF && at(4) == 0x27 && at(5) == 0x1C ->
                ArchiveFormat.SEVEN_ZIP
            // ar: "!<arch>\n"
            at(0) == 0x21 && at(1) == 0x3C && at(2) == 0x61 && at(3) == 0x72 && at(4) == 0x63 &&
                at(5) == 0x68 && at(6) == 0x3E && at(7) == 0x0A -> ArchiveFormat.AR
            // cpio: "070701"/"070702" (newc/crc) or "070707" (odc)
            at(0) == 0x30 && at(1) == 0x37 && at(2) == 0x30 && at(3) == 0x37 && at(4) == 0x30 &&
                (at(5) == 0x31 || at(5) == 0x32 || at(5) == 0x37) -> ArchiveFormat.CPIO
            at(0) == 0x52 && at(1) == 0x61 && at(2) == 0x72 && at(3) == 0x21 && at(4) == 0x1A && at(5) == 0x07 ->
                ArchiveFormat.RAR
            isZlib(header) -> ArchiveFormat.ZLIB
            else -> ArchiveFormat.UNKNOWN
        }
    }

    /**
     * Sniff up to 512 bytes, filling partial reads without taking source ownership.
     * Inconsistent sizes, premature EOF and invalid read counts are format errors.
     */
    @Throws(ArchiveException::class)
    public fun sniff(source: RandomAccessSource): ArchiveFormat {
        val size = source.size
        if (size < 0) throw ArchiveFormatException("negative source size $size", 0)
        if (size == 0L) return ArchiveFormat.UNKNOWN
        val n = minOf(size, 512L).toInt()
        return sniff(source.readFully(0, n))
    }

    private fun isBzip2(header: ByteArray): Boolean {
        if (header.size < 10 || header[0] != 0x42.toByte() || header[1] != 0x5A.toByte() ||
            header[2] != 0x68.toByte() || header[3].toInt() !in '1'.code..'9'.code) return false
        var marker = 0L
        for (i in 4..9) marker = (marker shl 8) or (header[i].toLong() and 0xFF)
        return marker == 0x314159265359L || marker == 0x177245385090L
    }

    private fun isZlib(header: ByteArray): Boolean {
        if (header.size < 2) return false
        val cmf = header[0].toInt() and 0xFF
        val flg = header[1].toInt() and 0xFF
        if ((cmf and 0x0F) != 8) return false              // CM must be DEFLATE
        if ((cmf ushr 4) > 7) return false                 // CINFO window <= 32 KiB
        if (((cmf shl 8) or flg) % 31 != 0) return false   // header checksum
        // FDICT inserts four DICTID bytes before the first DEFLATE block.
        val blockOffset = if ((flg and 0x20) != 0) 6 else 2
        if (header.size <= blockOffset) return blockOffset == 2
        return ((header[blockOffset].toInt() ushr 1) and 3) != 3
    }
}
