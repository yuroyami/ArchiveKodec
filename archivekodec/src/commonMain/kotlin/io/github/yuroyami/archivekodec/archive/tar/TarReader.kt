package io.github.yuroyami.archivekodec.archive.tar

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.withEntryContext
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.decodeStoredName
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.ReaderSource
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
 * Ustar prefixes require the complete `ustar\u0000` marker; old GNU/v7 tails are not paths.
 * Every nonzero header must have a valid unsigned or legacy signed-byte checksum.
 * Complete records may end without a trailer; nonempty partial headers are rejected.
 * [limits] caps the listing count and supplies the default policy for each read.
 *
 * Owns the supplied source; close this reader to release it, including after a
 * failed operation. This reader is not thread-safe. Keep it on one thread or
 * externally serialize all access, including [close]. The list from [entries]
 * must be treated as read-only; it may share the reader's cached metadata.
 */
public class TarReader(
    source: RandomAccessSource,
    private val nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
) : ArchiveReader {
    public constructor(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder) : this(source, nameDecoder, ArchiveLimits.DEFAULT)
    public constructor(source: RandomAccessSource) : this(source, ArchiveNameDecoder.UTF8)
    public constructor(source: RandomAccessSource, limits: ArchiveLimits) : this(source, ArchiveNameDecoder.UTF8, limits)

    private val ownedSource = ReaderSource<List<ArchiveEntry>>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    override fun toString(): String = "TarReader(source=$ownedSource)"

    override val format: ArchiveFormat = ArchiveFormat.TAR

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close(): Unit = ownedSource.close()

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray = withEntryContext(entry.name, entry.dataOffset) {
        val source = ownedSource.source
        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.size, entry.size)
        if (entry.isDirectory || entry.size == 0L) return@withEntryContext ByteArray(0)
        return@withEntryContext source.readFullyChecked(entry.dataOffset, entry.size)
    }

    private fun parse(): List<ArchiveEntry> {
        val out = ArrayList<ArchiveEntry>()
        var pos = 0L
        val total = source.size
        if (total < 0) throw ArchiveFormatException("TAR: negative source size", 0)
        var pendingLongName: ByteArray? = null
        var pendingNameOffset = 0L

        while (pos < total) {
            if (total - pos < BLOCK) throw ArchiveFormatException("TAR: truncated header", pos)
            val hdr = source.readFully(pos, BLOCK)
            if (TarHeader.isZeroRecord(hdr)) break // two zero blocks mark EOF; one is enough to stop
            if (!TarHeader.hasValidChecksum(hdr)) {
                throw ArchiveFormatException("TAR: bad header checksum", pos)
            }

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
                    var end = nameBytes.size
                    while (end > 0 && nameBytes[end - 1].toInt() == 0) end--
                    pendingLongName = nameBytes.copyOf(end)
                    pendingNameOffset = dataStart
                }
                'x', 'g' -> {
                    // pax extended headers — not parsed yet; skip the data block.
                }
                else -> {
                    val nameOffset = if (pendingLongName != null) pendingNameOffset else pos
                    val nameBytes = pendingLongName ?: run {
                        val base = cString(hdr, 0, 100)
                        val prefix = if (TarHeader.usesUstarPrefix(hdr)) cString(hdr, 345, 155) else byteArrayOf()
                        if (prefix.isNotEmpty()) prefix + byteArrayOf(47) + base else base
                    }
                    pendingLongName = null

                    val isFileLike = typeFlag == '0' || typeFlag == '\u0000' ||
                        typeFlag == '5' || typeFlag == '7'
                    if (isFileLike && nameBytes.isNotEmpty()) {
                        limits.checkEntryCount(out.size + 1)
                        val name = nameDecoder.decodeStoredName(nameBytes, nameOffset)
                        val isDir = typeFlag == '5' || name.endsWith("/")
                        out.add(
                            ArchiveEntry(
                                name = name,
                                size = if (isDir) 0L else size,
                                isDirectory = isDir,
                                lastModifiedEpochSeconds = numeric(hdr, 136, 12, pos),
                                dataOffset = dataStart,
                            ).withNameBytes(nameBytes),
                        )
                    }
                }
            }

            pos = dataStart + size + padding
        }
        return out
    }

    /** Remove NUL framing while preserving all stored filename bytes. */
    private fun cString(b: ByteArray, off: Int, len: Int): ByteArray {
        var end = off
        val max = off + len
        while (end < max && b[end].toInt() != 0) end++
        return b.copyOfRange(off, end)
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

    public companion object {
        private const val BLOCK = TarHeader.SIZE
        private const val SPACE = ' '.code
        private const val ZERO = '0'.code
        private const val SEVEN = '7'.code
    }
}
