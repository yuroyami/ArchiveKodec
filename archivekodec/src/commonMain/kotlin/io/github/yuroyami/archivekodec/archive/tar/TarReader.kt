package io.github.yuroyami.archivekodec.archive.tar

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.withEntryContext
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.archive.readFullyChecked
import io.github.yuroyami.archivekodec.archive.requireRange
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * TAR reader (v7 / ustar / GNU). TAR has no compression of its own — it is a
 * concatenation of 512-byte-record entries — so this reader needs no codec; a
 * `.tar.gz` is this reader composed over [io.github.yuroyami.archivekodec.wrapper.Gzip].
 *
 * Supports: ustar `prefix` + `name` (long-path), GNU `L` long-name pseudo-entries,
 * octal and GNU base-256 numeric fields, normal files / directories. The full pax
 * `x`/`g` extended-header and sparse-file handling are not implemented (issue #35).
 */
class TarReader(private val source: RandomAccessSource) : ArchiveReader {

    override fun toString(): String = "TarReader(source=$source)"

    override val format = ArchiveFormat.TAR

    private val parsed: List<ArchiveEntry> by lazy { parse() }

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = parsed

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray = withEntryContext(entry.name, entry.dataOffset) {
        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.size, entry.size)
        if (entry.isDirectory || entry.size == 0L) return@withEntryContext ByteArray(0)
        return@withEntryContext source.readFullyChecked(entry.dataOffset, entry.size)
    }

    private fun parse(): List<ArchiveEntry> {
        val out = ArrayList<ArchiveEntry>()
        var pos = 0L
        val total = source.size
        var pendingLongName: String? = null

        while (pos <= total && total - pos >= BLOCK) {
            val hdr = source.readFully(pos, BLOCK)
            if (isZeroBlock(hdr)) break // two zero blocks mark EOF; one is enough to stop

            val typeFlag = hdr[156].toInt().toChar()
            // Device, directory and FIFO headers have no stored payload records.
            val size = when (typeFlag) {
                '3', '4', '5', '6' -> 0L
                else -> numeric(hdr, 124, 12, pos)
            }
            val dataStart = pos + BLOCK
            source.requireRange(dataStart, size)
            val padding = (BLOCK - size % BLOCK) % BLOCK
            source.requireRange(dataStart + size, padding)

            when (typeFlag) {
                'L' -> {
                    // GNU long name: the full path is stored as this entry's data.
                    val nameBytes = source.readFullyChecked(dataStart, size)
                    pendingLongName = nameBytes.decodeToString().trimEnd('\u0000')
                }
                'x', 'g' -> {
                    // pax extended headers — not parsed yet; skip the data block.
                }
                else -> {
                    val name = pendingLongName ?: run {
                        val base = cString(hdr, 0, 100)
                        val prefix = cString(hdr, 345, 155)
                        if (prefix.isNotEmpty()) "$prefix/$base" else base
                    }
                    pendingLongName = null

                    val isDir = typeFlag == '5' || name.endsWith("/")
                    val isFileLike = typeFlag == '0' || typeFlag == '\u0000' ||
                        typeFlag == '5' || typeFlag == '7'
                    if (isFileLike && name.isNotEmpty()) {
                        out.add(
                            ArchiveEntry(
                                name = if (isDir) name.trimEnd('/') else name,
                                size = if (isDir) 0L else size,
                                isDirectory = isDir,
                                lastModifiedEpochSeconds = numeric(hdr, 136, 12, pos),
                                dataOffset = dataStart,
                            ),
                        )
                    }
                }
            }

            pos = dataStart + size + padding
        }
        return out
    }

    private fun isZeroBlock(b: ByteArray): Boolean {
        for (x in b) if (x.toInt() != 0) return false
        return true
    }

    /** Read a NUL/space-terminated ASCII string field. */
    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && b[end].toInt() != 0) end++
        return b.decodeToString(off, end)
    }

    /**
     * Parse a numeric header field: octal ASCII normally, GNU base-256 binary when
     * the field's high bit is set (used when a value overflows the octal width).
     */
    private fun numeric(b: ByteArray, off: Int, len: Int, headerOffset: Long): Long {
        if (len > 0 && (b[off].toInt() and 0x80) != 0) {
            val first = b[off].toInt() and 0x7F
            var v = if (first and 0x40 != 0) first.toLong() - 128 else first.toLong()
            for (i in 1 until len) {
                if (v < (Long.MIN_VALUE shr 8) || v > (Long.MAX_VALUE shr 8)) {
                    throw ArchiveFormatException("TAR: numeric field overflows Long", headerOffset + off)
                }
                v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            }
            return v
        }
        var v = 0L
        var i = off
        val max = off + len
        while (i < max) {
            val c = b[i].toInt()
            if (c == 0 || c == SPACE) { i++; continue }
            if (c < ZERO || c > SEVEN) break
            v = v * 8 + (c - ZERO)
            i++
        }
        return v
    }

    companion object {
        private const val BLOCK = 512
        private const val SPACE = ' '.code
        private const val ZERO = '0'.code
        private const val SEVEN = '7'.code
    }
}
