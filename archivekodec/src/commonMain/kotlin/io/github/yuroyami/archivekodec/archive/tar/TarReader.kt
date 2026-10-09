package io.github.yuroyami.archivekodec.archive.tar

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
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
import io.github.yuroyami.archivekodec.archive.issuedBy
import io.github.yuroyami.archivekodec.archive.resolveIssued
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * TAR reader (v7 / ustar / GNU). TAR has no compression of its own — it is a
 * concatenation of 512-byte-record entries — so this reader needs no codec; a
 * `.tar.gz` is this reader composed over [io.github.yuroyami.archivekodec.wrapper.Gzip].
 *
 * Supports: ustar `prefix` + `name` (long-path), GNU `L` long-name pseudo-entries,
 * octal and GNU base-256 numeric fields, normal files / directories. pax `x` records
 * apply to the next member and `g` records to every later one; their `path`, `size`
 * and `mtime` override the ustar fields (pax names are UTF-8 unless `hdrcharset` is
 * `BINARY`). Sparse members (GNU `S`, and pax GNU sparse 0.0, 0.1 and 1.0) list their
 * expanded size, with [ArchiveEntry.compressedSize] holding the stored bytes, and read
 * with zero-filled holes. Holes count toward the output limit but not the ratio limit.
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

    /** Listed entries with the sparse map, if any, at the same index. */
    private class Directory(val entries: List<ArchiveEntry>, val sparse: List<SparseMap?>)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "TarReader(source=$ownedSource)"

    override val format: ArchiveFormat = ArchiveFormat.TAR

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata {
        val (entries, sparse) = parse()
        Directory(entries.issuedBy(this, limits), sparse)
    }

    @Throws(ArchiveException::class)
    override fun close(): Unit = ownedSource.close()

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "TAR")
        return readListed(listed, directory.sparse[listed.slotIn(this)], limits)
    }

    private fun readListed(entry: ArchiveEntry, sparse: SparseMap?, limits: ArchiveLimits): ByteArray = withEntryContext(entry.name, entry.dataOffset) {
        val source = ownedSource.source
        limits.checkOutputSize(entry.size)
        // Holes cost no input, so only the stored regions take part in the ratio check.
        limits.checkRatio(entry.compressedSize, entry.compressedSize)
        if (entry.isDirectory || entry.size == 0L) return@withEntryContext ByteArray(0)
        if (sparse == null) return@withEntryContext source.readFullyChecked(entry.dataOffset, entry.size)
        if (entry.size > Int.MAX_VALUE) {
            throw ArchiveFormatException("TAR: sparse file of ${entry.size} bytes cannot fit in a ByteArray", entry.dataOffset)
        }
        val out = ByteArray(entry.size.toInt())
        var stored = entry.dataOffset
        for (i in sparse.offsets.indices) {
            val length = sparse.lengths[i].toInt()
            var got = 0
            while (got < length) {
                val n = source.read(stored + got, out, sparse.offsets[i].toInt() + got, length - got)
                if (n <= 0 || n > length - got) {
                    throw ArchiveFormatException("TAR: unexpected read at ${stored + got} (wanted ${length - got}, got $n)", stored + got)
                }
                got += n
            }
            stored += length
        }
        return@withEntryContext out
    }

    private fun parse(): Pair<List<ArchiveEntry>, List<SparseMap?>> {
        val out = ArrayList<ArchiveEntry>()
        val maps = ArrayList<SparseMap?>()
        var pos = 0L
        val total = source.size
        if (total < 0) throw ArchiveFormatException("TAR: negative source size", 0)
        var pendingLongName: ByteArray? = null
        var pendingNameOffset = 0L
        var global = PaxRecords.EMPTY
        var local: PaxRecords? = null

        while (pos < total) {
            if (total - pos < BLOCK) throw ArchiveFormatException("TAR: truncated header", pos)
            val hdr = source.readFully(pos, BLOCK)
            if (TarHeader.isZeroRecord(hdr)) break // two zero blocks mark EOF; one is enough to stop
            if (!TarHeader.hasValidChecksum(hdr)) {
                throw ArchiveFormatException("TAR: bad header checksum", pos)
            }

            val typeFlag = hdr[156].toInt().toChar()
            val pax = if (typeFlag == 'x' || typeFlag == 'g' || typeFlag == 'L' || typeFlag == 'K') PaxRecords.EMPTY
                else (local ?: PaxRecords.EMPTY).under(global)
            // Device, directory and FIFO headers have no stored payload records.
            val size = when (typeFlag) {
                '3', '4', '5', '6' -> 0L
                else -> pax.text("size", pos)?.let { paxNumber(it, "size", pos) } ?: numeric(hdr, 124, 12, pos)
            }
            // Old GNU sparse headers continue in extension records before the data.
            var extensions = 0L
            var oldSparse: SparseMap? = null
            if (typeFlag == 'S') {
                val offsets = ArrayList<Long>()
                val lengths = ArrayList<Long>()
                fun regions(block: ByteArray, start: Int, count: Int, blockOffset: Long) {
                    for (i in 0 until count) {
                        val at = start + i * 24
                        if (block[at].toInt() == 0) continue
                        offsets.add(numeric(block, at, 12, blockOffset)); lengths.add(numeric(block, at + 12, 12, blockOffset))
                    }
                }
                regions(hdr, 386, 4, pos)
                var more = hdr[482].toInt() != 0
                while (more) {
                    val at = pos + BLOCK * (1 + extensions)
                    if (total - at < BLOCK) throw ArchiveFormatException("TAR: truncated sparse extension header", at)
                    val block = source.readFully(at, BLOCK)
                    regions(block, 0, 21, at)
                    more = block[504].toInt() != 0
                    extensions++
                }
                oldSparse = SparseMap.of(numeric(hdr, 483, 12, pos), offsets, lengths, size, pos)
            }
            val dataStart = pos + BLOCK * (1 + extensions)
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
                'K' -> {
                    // GNU long link target: links are not listed, so the target is not needed.
                }
                'x' -> local = PaxRecords.parse(source, dataStart, size)
                'g' -> global = PaxRecords.parse(source, dataStart, size).under(global)
                else -> {
                    val paxName = pax.values["GNU.sparse.name"] ?: pax.values["path"]
                    val nameOffset = if (paxName == null && pendingLongName != null) pendingNameOffset else pos
                    val nameBytes = paxName ?: pendingLongName ?: run {
                        val base = cString(hdr, 0, 100)
                        val prefix = if (TarHeader.usesUstarPrefix(hdr)) cString(hdr, 345, 155) else byteArrayOf()
                        if (prefix.isNotEmpty()) prefix + byteArrayOf(47) + base else base
                    }
                    pendingLongName = null
                    local = null

                    val isFileLike = typeFlag == '0' || typeFlag == '\u0000' ||
                        typeFlag == '5' || typeFlag == '7' || typeFlag == 'S'
                    if (isFileLike && nameBytes.isNotEmpty()) {
                        limits.checkEntryCount(out.size + 1)
                        val decoder = if (paxName != null && pax.text("hdrcharset", pos) != "BINARY") ArchiveNameDecoder.UTF8 else nameDecoder
                        val name = decoder.decodeStoredName(nameBytes, nameOffset)
                        val isDir = typeFlag == '5' || name.endsWith("/")
                        val sparse = if (isDir) null else oldSparse ?: paxSparse(pax, dataStart, size, pos)
                        val storedStart = dataStart + (sparse?.let { size - it.storedSize } ?: 0L)
                        val mtime = pax.text("mtime", pos)?.let { paxSeconds(it, pos) } ?: optionalNumeric(hdr, 136, 12, pos)
                        // The ustar mode field holds permission bits; the type flag supplies the file type.
                        val mode = optionalNumeric(hdr, 100, 8, pos)?.let {
                            (it and 0xFFF).toInt() or if (isDir) 0x4000 else 0x8000
                        }
                        val logicalSize = if (isDir) 0L else sparse?.realSize ?: size
                        out.add(
                            ArchiveEntry(name, logicalSize, isDir) {
                                lastModifiedEpochSeconds = mtime
                                unixMode = mode
                                compressedSize = if (isDir) 0L else sparse?.storedSize ?: size
                            }.withNameBytes(nameBytes).locatedAt(storedStart),
                        )
                        maps.add(sparse)
                    }
                }
            }

            pos = dataStart + size + padding
        }
        return out to maps
    }

    /**
     * The sparse map of a pax member, or null when it has none. Format 1.0 stores the map
     * as decimal lines at the start of the data, padded to a record; 0.1 and 0.0 keep it
     * in the extended header.
     */
    private fun paxSparse(pax: PaxRecords, dataStart: Long, size: Long, headerOffset: Long): SparseMap? {
        val major = pax.text("GNU.sparse.major", headerOffset)
        val map = pax.text("GNU.sparse.map", headerOffset)
        if (major == null && map == null && pax.sparseOffsets.isEmpty() && pax.sparseSizes.isEmpty()) return null
        fun number(key: String): Long? = pax.text(key, headerOffset)?.let { paxNumber(it, key, headerOffset) }
        return when {
            major != null -> {
                val minor = pax.text("GNU.sparse.minor", headerOffset)
                if (major != "1" || minor != "0") {
                    throw ArchiveUnsupportedException("TAR: GNU sparse format $major.$minor is not supported", headerOffset)
                }
                val realSize = number("GNU.sparse.realsize")
                    ?: throw ArchiveFormatException("TAR: GNU sparse 1.0 member has no realsize", headerOffset)
                val (offsets, lengths, mapBytes) = sparseMapBlock(dataStart, size)
                SparseMap.of(realSize, offsets, lengths, size - mapBytes, headerOffset)
            }
            else -> {
                val realSize = number("GNU.sparse.size")
                    ?: throw ArchiveFormatException("TAR: GNU sparse 0.x member has no size", headerOffset)
                val numbers = if (map != null) {
                    if (map.isEmpty()) emptyList() else map.split(',').map { paxNumber(it, "GNU.sparse.map", headerOffset) }
                } else {
                    if (pax.sparseOffsets.size != pax.sparseSizes.size) {
                        throw ArchiveFormatException("TAR: GNU sparse 0.0 offsets and sizes are unpaired", headerOffset)
                    }
                    pax.sparseOffsets.indices.flatMap {
                        listOf(paxNumber(pax.sparseOffsets[it], "GNU.sparse.offset", headerOffset),
                            paxNumber(pax.sparseSizes[it], "GNU.sparse.numbytes", headerOffset))
                    }
                }
                if (numbers.size % 2 != 0) throw ArchiveFormatException("TAR: GNU sparse map has an odd number of values", headerOffset)
                number("GNU.sparse.numblocks")?.let {
                    if (it != numbers.size / 2L) throw ArchiveFormatException("TAR: GNU sparse map has ${numbers.size / 2} regions, not $it", headerOffset)
                }
                SparseMap.of(realSize, numbers.filterIndexed { i, _ -> i % 2 == 0 }, numbers.filterIndexed { i, _ -> i % 2 == 1 }, size, headerOffset)
            }
        }
    }

    /** Read a GNU sparse 1.0 map: a count, then offset and size pairs, one decimal per line. */
    private fun sparseMapBlock(dataStart: Long, size: Long): Triple<List<Long>, List<Long>, Long> {
        val values = ArrayList<Long>()
        var expected = -1L
        var consumed = 0L
        var current = -1L
        var block = ByteArray(0)
        var blockStart = 0L
        while (expected < 0 || values.size < expected * 2) {
            if (consumed >= size) throw ArchiveFormatException("TAR: GNU sparse map runs past the member data", dataStart + consumed)
            if (consumed - blockStart >= block.size) {
                blockStart = consumed
                block = source.readFullyChecked(dataStart + consumed, minOf(BLOCK.toLong(), size - consumed))
            }
            val c = block[(consumed - blockStart).toInt()].toInt()
            consumed++
            when {
                c in '0'.code..'9'.code -> {
                    if (current > (Long.MAX_VALUE - 9) / 10) throw ArchiveFormatException("TAR: GNU sparse map value overflows Long", dataStart + consumed)
                    current = maxOf(current, 0L) * 10 + (c - '0'.code)
                }
                c == '\n'.code && current >= 0 -> {
                    if (expected < 0) {
                        // Every region needs at least four map bytes, so a count beyond that is impossible.
                        if (current > (size - consumed) / 4) throw ArchiveFormatException("TAR: GNU sparse map claims $current regions", dataStart)
                        expected = current
                    } else values.add(current)
                    current = -1L
                }
                else -> throw ArchiveFormatException("TAR: malformed GNU sparse map", dataStart + consumed - 1)
            }
        }
        val mapBytes = (consumed + BLOCK - 1) / BLOCK * BLOCK
        if (mapBytes > size) throw ArchiveFormatException("TAR: GNU sparse map padding runs past the member data", dataStart)
        return Triple(values.filterIndexed { i, _ -> i % 2 == 0 }, values.filterIndexed { i, _ -> i % 2 == 1 }, mapBytes)
    }

    /** Remove NUL framing while preserving all stored filename bytes. */
    private fun cString(b: ByteArray, off: Int, len: Int): ByteArray {
        var end = off
        val max = off + len
        while (end < max && b[end].toInt() != 0) end++
        return b.copyOfRange(off, end)
    }

    /** Like [numeric], but null for a field holding only NUL and space padding. */
    private fun optionalNumeric(b: ByteArray, off: Int, len: Int, headerOffset: Long): Long? {
        for (i in off until off + len) if (b[i].toInt() != 0 && b[i].toInt() != SPACE) return numeric(b, off, len, headerOffset)
        return null
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

    /** Holds the reader's internal format constants; exposes no public operations. */
    public companion object {
        private const val BLOCK = TarHeader.SIZE
        private const val SPACE = ' '.code
        private const val ZERO = '0'.code
        private const val SEVEN = '7'.code
    }
}
