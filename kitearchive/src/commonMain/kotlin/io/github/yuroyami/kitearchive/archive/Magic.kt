package io.github.yuroyami.kitearchive.archive

/**
 * Magic-byte format sniffer — the analogue of KiteQR's `MultiFormatReader` dispatch.
 * Inspects a header (or the first 512 bytes of a [RandomAccessSource]) and returns
 * the [ArchiveFormat]. Brotli has no magic, so it is never auto-detected here.
 */
object Magic {

    fun sniff(header: ByteArray): ArchiveFormat {
        fun at(i: Int): Int = if (i < header.size) header[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x1F && at(1) == 0x8B -> ArchiveFormat.GZIP
            at(0) == 0x50 && at(1) == 0x4B && (at(2) == 0x03 || at(2) == 0x05 || at(2) == 0x07) ->
                ArchiveFormat.ZIP
            at(0) == 0xFD && at(1) == 0x37 && at(2) == 0x7A && at(3) == 0x58 && at(4) == 0x5A && at(5) == 0x00 ->
                ArchiveFormat.XZ
            at(0) == 0x28 && at(1) == 0xB5 && at(2) == 0x2F && at(3) == 0xFD -> ArchiveFormat.ZSTD
            at(0) == 0x04 && at(1) == 0x22 && at(2) == 0x4D && at(3) == 0x18 -> ArchiveFormat.LZ4
            at(0) == 0x42 && at(1) == 0x5A && at(2) == 0x68 -> ArchiveFormat.BZIP2 // "BZh"
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
            // .lzma-alone: properties byte (<=0xE0 for valid lc/lp/pb) then 4-byte dict size.
            // Too weak to sniff reliably from magic alone — left to explicit selection.
            // TAR: "ustar" at offset 257.
            at(257) == 0x75 && at(258) == 0x73 && at(259) == 0x74 && at(260) == 0x61 && at(261) == 0x72 ->
                ArchiveFormat.TAR
            isZlib(at(0), at(1)) -> ArchiveFormat.ZLIB
            else -> ArchiveFormat.UNKNOWN
        }
    }

    /** Sniff the first bytes of a seekable source. */
    fun sniff(source: RandomAccessSource): ArchiveFormat {
        val n = minOf(source.size, 512L).toInt()
        if (n <= 0) return ArchiveFormat.UNKNOWN
        val buf = ByteArray(n)
        source.read(0, buf, 0, n)
        return sniff(buf)
    }

    private fun isZlib(cmf: Int, flg: Int): Boolean {
        if (cmf < 0 || flg < 0) return false
        if ((cmf and 0x0F) != 8) return false              // CM must be DEFLATE
        if (((cmf ushr 4) and 0x0F) > 7) return false      // CINFO window <= 32 KiB
        return ((cmf shl 8) or flg) % 31 == 0              // header checksum
    }
}
