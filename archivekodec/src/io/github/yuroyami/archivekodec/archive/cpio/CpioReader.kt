package io.github.yuroyami.archivekodec.archive.cpio

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
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.jvm.JvmSynthetic

/**
 * cpio reader for the two portable ASCII dialects: `newc`/`crc` (magic 070701/070702,
 * hex fields, 4-byte padding) and `odc` (magic 070707, octal fields, no padding).
 * Hard links resolve to their group's last nonempty payload. Listing reads only
 * metadata; reading verifies each crc record's unsigned byte sum, including
 * overwritten payloads. The archive ends at `TRAILER!!!`. Old binary cpio is
 * recognized in either byte order and refused with [ArchiveUnsupportedException].
 * Empty input, unknown magic and incomplete magic before a trailer throw
 * [ArchiveFormatException], rather than returning an empty or partial listing.
 * [limits] caps the listing count and supplies the default policy for each read.
 *
 * Owns the supplied source; close this reader to release it, including after a
 * failed operation. This reader is not thread-safe. Keep it on one thread or
 * externally serialize all access, including [close]. The list from [entries]
 * must be treated as read-only; it may share the reader's cached metadata.
 */
public class CpioReader private constructor(
    source: RandomAccessSource,
    private val nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
    private val initramfs: Boolean,
) : ArchiveReader {
    public constructor(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder, limits: ArchiveLimits) :
        this(source, nameDecoder, limits, false)
    public constructor(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder) : this(source, nameDecoder, ArchiveLimits.DEFAULT)
    public constructor(source: RandomAccessSource) : this(source, ArchiveNameDecoder.UTF8)
    public constructor(source: RandomAccessSource, limits: ArchiveLimits) : this(source, ArchiveNameDecoder.UTF8, limits)

    private data class LinkKey(val odc: Boolean, val major: Long, val minor: Long, val inode: Long)
    private data class Record(
        val entry: ArchiveEntry, val storedSize: Long, val type: Long, val modeOffset: Long,
        val checksum: Long?, val checksumOffset: Long,
    )
    private class Group(val first: Record) {
        val records = arrayListOf(first)
        var payload = first
    }
    private class ReadOnlyList<T>(private val values: List<T>) : AbstractList<T>() {
        override val size: Int get() = values.size
        override fun get(index: Int): T = values[index]
    }
    private data class Directory(
        val entries: List<ArchiveEntry>, val groups: List<Group>, val segments: List<InitramfsSegment>,
    )
    private data class ParsedRecord(val endOffset: Long, val trailer: Boolean)
    private data class SegmentBounds(val start: Long, val end: Long, val first: Int, val last: Int, val trailer: Boolean)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "CpioReader(source=$ownedSource)"

    override val format: ArchiveFormat = ArchiveFormat.CPIO

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    @JvmSynthetic
    internal fun segments(): List<InitramfsSegment> = directory().segments

    private fun directory(): Directory = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close(): Unit = ownedSource.close()

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "cpio")
        return readListed(listed, directory.groups[listed.slotIn(this)], limits)
    }

    private fun readListed(entry: ArchiveEntry, group: Group, limits: ArchiveLimits): ByteArray = withEntryContext(entry.name, entry.dataOffset) {
        val source = ownedSource.source
        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.size, entry.size)
        if (entry.size > Int.MAX_VALUE) {
            throw ArchiveFormatException("cpio: range of ${entry.size} bytes cannot fit in a ByteArray", entry.dataOffset)
        }
        val payload = group.payload
        val bytes = if (entry.isDirectory || entry.size == 0L) ByteArray(0)
            else source.readFullyChecked(payload.entry.dataOffset, payload.storedSize)
        var buffer: ByteArray? = null
        for (record in group.records) {
            if (record.checksum == null) continue
            val sum = if (record === payload && record.storedSize == bytes.size.toLong()) {
                byteSum(bytes, bytes.size)
            } else if (record.storedSize == 0L) {
                0L
            } else {
                val scratch = buffer ?: ByteArray(8192).also { buffer = it }
                storedSum(source, record, scratch)
            }
            checkSum(record.entry.name, record.checksumOffset, record.checksum, sum)
        }
        bytes
    }

    private fun parse(): Directory {
        val records = ArrayList<Pair<Record, Group>>()
        val links = HashMap<LinkKey, Group>()
        val bounds = ArrayList<SegmentBounds>()
        val paddingBuffer = if (initramfs) ByteArray(110) else null
        var pos = 0L
        var segmentStart: Long? = null
        var segmentFirst = 0
        var recordEnd = 0L
        val total = source.size
        if (total < 0) throw ArchiveFormatException("cpio: negative source size $total", 0)
        if (!initramfs && total == 0L) throw ArchiveFormatException("cpio: missing header", 0)
        while (pos < total) {
            if (initramfs) {
                pos = skipZeros(pos, total, paddingBuffer!!)
                if (pos == total) break
            }
            val prefix = source.readFully(pos, minOf(6L, total - pos).toInt())
            if (initramfs) rejectCompression(prefix, pos)
            if (isBinaryCpioMagic(prefix)) {
                throw ArchiveUnsupportedException("cpio: old binary cpio is not supported", pos)
            }
            if (prefix.size < 6) throw ArchiveFormatException("cpio: truncated header magic", pos)
            val magic = prefix.decodeToString()
            if (initramfs) {
                if (magic == "070707") throw ArchiveUnsupportedException("initramfs: odc cpio is not supported; expected newc/crc", pos)
                if ((magic == "070701" || magic == "070702") && pos and 3L != 0L) {
                    throw ArchiveFormatException("initramfs: cpio header is not four-byte aligned", pos)
                }
                if (segmentStart == null) {
                    if (bounds.size >= limits.maxSegments) {
                        throw DecompressionBombException("initramfs: segment count exceeds limit ${limits.maxSegments}", pos, null, null)
                    }
                    segmentStart = pos
                    segmentFirst = records.size
                }
            }
            val parsed = when (magic) {
                "070701", "070702" -> parseNewc(pos, magic == "070702", records, links)
                "070707" -> parseOdc(pos, records, links)
                else -> throw ArchiveFormatException("cpio: unrecognized header magic", pos)
            }
            pos = parsed.endOffset
            recordEnd = pos
            if (parsed.trailer) {
                if (!initramfs) break
                bounds.add(SegmentBounds(segmentStart!!, pos, segmentFirst, records.size, true))
                segmentStart = null
                links.clear()
            }
        }
        if (segmentStart != null) bounds.add(SegmentBounds(segmentStart, recordEnd, segmentFirst, records.size, false))
        val entries = ArrayList<ArchiveEntry>(records.size)
        val groups = ArrayList<Group>(records.size)
        for ((record, group) in records) {
            val size = if (record.entry.isDirectory) 0L else group.payload.storedSize
            entries.add(record.entry.copy { this.size = size; compressedSize = size }.locatedAt(group.payload.entry.dataOffset))
            groups.add(group)
        }
        val issued = entries.issuedBy(this, limits)
        val segments = bounds.map { b ->
            InitramfsSegment(b.start, b.end, b.trailer, ReadOnlyList(issued.subList(b.first, b.last)))
        }
        return Directory(issued, groups, ReadOnlyList(segments))
    }

    private fun skipZeros(start: Long, total: Long, buffer: ByteArray): Long {
        var pos = start
        while (pos < total) {
            // Reuse one header-sized buffer, never reading a valid record's body.
            val wanted = minOf(buffer.size.toLong(), total - pos).toInt()
            val count = source.read(pos, buffer, 0, wanted)
            if (count <= 0 || count > wanted) {
                throw ArchiveFormatException("initramfs: unexpected padding read (wanted $wanted, got $count)", pos)
            }
            for (index in 0 until count) if (buffer[index].toInt() != 0) return pos + index
            pos += count
        }
        return pos
    }

    private fun rejectCompression(prefix: ByteArray, offset: Long) {
        fun starts(vararg bytes: Int): Boolean = prefix.size >= bytes.size &&
            bytes.indices.all { prefix[it].toInt() and 255 == bytes[it] }
        val name = when {
            starts(0x1f, 0x8b) || starts(0x1f, 0x9e) -> "gzip"
            starts(0x42, 0x5a, 0x68) -> "bzip2"
            starts(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0) -> "xz"
            starts(0x5d, 0) -> "LZMA"
            starts(0x89, 0x4c, 0x5a, 0x4f) -> "LZO"
            starts(0x02, 0x21, 0x4c, 0x18) || starts(0x04, 0x22, 0x4d, 0x18) -> "LZ4"
            starts(0x28, 0xb5, 0x2f, 0xfd) -> "Zstandard"
            else -> return
        }
        throw ArchiveUnsupportedException("initramfs: $name compressed segments are not supported", offset)
    }

    /** newc/crc: 6-byte magic + 13 × 8 hex fields = 110-byte header; 4-byte padding. */
    private fun parseNewc(pos: Long, crc: Boolean, out: ArrayList<Pair<Record, Group>>, links: MutableMap<LinkKey, Group>): ParsedRecord {
        val h = source.readFully(pos, 110)
        fun field(i: Int): Long = number(h, 6 + i * 8, 8, 16, pos)
        val inode = field(0)
        val mode = field(1)
        val nlink = field(4)
        val mtime = field(5)
        val fileSize = field(6)
        val major = field(7)
        val minor = field(8)
        val nameSize = field(11)
        val checksum = if (crc) field(12) else null
        val nameStart = pos + 110
        val nameBytes = readName(nameStart, nameSize)
        val dataStart = align4(nameStart + nameSize)
        source.requireRange(dataStart, fileSize)
        if (nameBytes.contentEquals(TRAILER_BYTES)) {
            checkTrailer(fileSize, pos + 54, checksum, pos + 102)
            return ParsedRecord(dataStart, true)
        }
        addEntry(out, links, nameBytes, nameStart, fileSize, mode, mtime, pos + 14, dataStart,
            if (nlink > 1) LinkKey(false, major, minor, inode) else null, checksum, pos + 102)
        return ParsedRecord(align4(dataStart + fileSize), false)
    }

    /** odc: 6-byte magic + octal fields (76-byte header); no padding. */
    private fun parseOdc(pos: Long, out: ArrayList<Pair<Record, Group>>, links: MutableMap<LinkKey, Group>): ParsedRecord {
        val h = source.readFully(pos, 76)
        fun field(offset: Int, width: Int = 6) = number(h, offset, width, 8, pos)
        val device = field(6)
        val inode = field(12)
        val mode = field(18)
        val nlink = field(36)
        val mtime = field(48, 11)
        val nameSize = field(59)
        val fileSize = field(65, 11)
        val nameStart = pos + 76
        val nameBytes = readName(nameStart, nameSize)
        val dataStart = nameStart + nameSize
        source.requireRange(dataStart, fileSize)
        if (nameBytes.contentEquals(TRAILER_BYTES)) {
            checkTrailer(fileSize, pos + 65, null, pos)
            return ParsedRecord(dataStart, true)
        }
        addEntry(out, links, nameBytes, nameStart, fileSize, mode, mtime, pos + 18, dataStart,
            if (nlink > 1) LinkKey(true, device, 0, inode) else null, null, pos)
        return ParsedRecord(dataStart + fileSize, false)
    }

    private fun addEntry(
        out: ArrayList<Pair<Record, Group>>, links: MutableMap<LinkKey, Group>, nameBytes: ByteArray,
        nameStart: Long, size: Long, mode: Long, mtime: Long, modeOffset: Long, dataStart: Long,
        key: LinkKey?, checksum: Long?, checksumOffset: Long,
    ) {
        try {
            limits.checkEntryCount(out.size + 1)
        } catch (error: DecompressionBombException) {
            throw error.withEntryContext(nameBytes.decodeToString(), nameStart)
        }
        val name = nameDecoder.decodeStoredName(nameBytes, nameStart)
        val isDir = (mode and 0xF000L) == 0x4000L // S_IFDIR
        val entry = ArchiveEntry(name, if (isDir) 0L else size, isDir) {
            lastModifiedEpochSeconds = mtime
            unixMode = mode.toInt()
        }.withNameBytes(nameBytes).locatedAt(dataStart)
        if (size == 0L && checksum != null) checkSum(name, checksumOffset, checksum, 0)
        val record = Record(entry, size, mode and 0xF000L, modeOffset, checksum, checksumOffset)
        val linkKey = if (isDir) null else key
        val group = linkKey?.let { links[it] }
        if (group == null) {
            val fresh = Group(record)
            if (linkKey != null) links[linkKey] = fresh
            out.add(record to fresh)
        } else {
            if (group.first.type != record.type) {
                throw ArchiveFormatException("cpio: conflicting hard-link file types", record.modeOffset, name)
            }
            group.records.add(record)
            if (size > 0) group.payload = record
            out.add(record to group)
        }
    }

    private fun checkTrailer(size: Long, sizeOffset: Long, checksum: Long?, checksumOffset: Long) {
        if (size != 0L) throw ArchiveFormatException("cpio: trailer has a data body", sizeOffset, "TRAILER!!!")
        if (checksum != null) checkSum("TRAILER!!!", checksumOffset, checksum, 0)
    }

    private fun checkSum(name: String, offset: Long, expected: Long, actual: Long) {
        if (actual != expected) throw ArchiveFormatException("cpio: byte-sum checksum mismatch (expected $expected, got $actual)", offset, name)
    }

    private fun byteSum(bytes: ByteArray, length: Int): Long {
        var sum = 0L
        for (i in 0 until length) sum += bytes[i].toLong() and 255L
        return sum and 0xffffffffL
    }

    private fun storedSum(source: RandomAccessSource, record: Record, buffer: ByteArray): Long =
        withEntryContext(record.entry.name, record.entry.dataOffset) {
            source.requireRange(record.entry.dataOffset, record.storedSize)
            var read = 0L
            var sum = 0L
            while (read < record.storedSize) {
                val wanted = minOf(buffer.size.toLong(), record.storedSize - read).toInt()
                val offset = record.entry.dataOffset + read
                val count = source.read(offset, buffer, 0, wanted)
                if (count <= 0 || count > wanted) {
                    throw ArchiveFormatException("cpio: unexpected read at $offset (wanted $wanted, got $count)", offset)
                }
                sum = (sum + byteSum(buffer, count)) and 0xffffffffL
                read += count
            }
            sum
        }

    private fun readName(offset: Long, length: Long): ByteArray {
        if (length < 1) throw ArchiveFormatException("cpio: empty name field", offset)
        val bytes = source.readFullyChecked(offset, length)
        if (bytes.last().toInt() != 0) throw ArchiveFormatException("cpio: missing name terminator", offset)
        return bytes.copyOf(bytes.indexOf(0))
    }

    private fun align4(v: Long): Long {
        val padding = (4 - (v and 3L)) and 3L
        source.requireRange(v, padding)
        return v + padding
    }

    private fun number(b: ByteArray, off: Int, len: Int, radix: Int, headerOffset: Long): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt() and 255
            val d = when (c) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> -1
            }
            if (d < 0 || d >= radix) throw ArchiveFormatException("cpio: invalid base-$radix digit", headerOffset + i)
            v = v * radix + d
        }
        return v
    }

    /** Holds the reader's internal format constants; exposes no public operations. */
    public companion object {
        private val TRAILER_BYTES = "TRAILER!!!".encodeToByteArray()

        @JvmSynthetic
        internal fun initramfs(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder, limits: ArchiveLimits): CpioReader =
            CpioReader(source, nameDecoder, limits, true)
    }
}
