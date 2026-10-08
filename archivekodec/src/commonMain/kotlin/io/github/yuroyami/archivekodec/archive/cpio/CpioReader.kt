package io.github.yuroyami.archivekodec.archive.cpio

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
 * cpio reader for the two portable ASCII dialects: `newc`/`crc` (magic 070701/070702,
 * hex fields, 4-byte padding) and `odc` (magic 070707, octal fields, no padding).
 * Hard links resolve to their group's last nonempty payload. Listing reads only
 * metadata; reading verifies each crc record's unsigned byte sum, including
 * overwritten payloads. The archive ends at `TRAILER!!!`. Old binary cpio is not handled.
 * [limits] caps the listing count and supplies the default policy for each read.
 */
public class CpioReader(
    source: RandomAccessSource,
    private val nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
) : ArchiveReader {
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
    private class EntryList(private val values: List<ArchiveEntry>) : AbstractList<ArchiveEntry>() {
        override val size: Int get() = values.size
        override fun get(index: Int): ArchiveEntry = values[index]
    }
    private data class Directory(val entries: List<ArchiveEntry>, val groups: Map<ArchiveEntry, Group>)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    override fun toString(): String = "CpioReader(source=$ownedSource)"

    override val format: ArchiveFormat = ArchiveFormat.CPIO

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close(): Unit = ownedSource.close()

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray = withEntryContext(entry.name, entry.dataOffset) {
        val source = ownedSource.source
        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.size, entry.size)
        if (entry.size > Int.MAX_VALUE) {
            throw ArchiveFormatException("cpio: range of ${entry.size} bytes cannot fit in a ByteArray", entry.dataOffset)
        }
        val group = directory().groups[entry]
            ?: throw ArchiveFormatException("cpio: entry does not match this reader's metadata", entry.dataOffset)
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
        var pos = 0L
        val total = source.size
        while (pos <= total && total - pos >= 6) {
            val magic = source.readFully(pos, 6).decodeToString()
            when (magic) {
                "070701", "070702" -> pos = parseNewc(pos, magic == "070702", records, links) ?: break
                "070707" -> pos = parseOdc(pos, records, links) ?: break
                else -> break
            }
        }
        val entries = ArrayList<ArchiveEntry>(records.size)
        val groups = HashMap<ArchiveEntry, Group>()
        for ((record, group) in records) {
            val size = if (record.entry.isDirectory) 0L else group.payload.storedSize
            val entry = record.entry.copy(size = size, compressedSize = size, dataOffset = group.payload.entry.dataOffset)
            entries.add(entry)
            groups[entry] = group
        }
        return Directory(EntryList(entries), groups)
    }

    /** newc/crc: 6-byte magic + 13 × 8 hex fields = 110-byte header; 4-byte padding. */
    private fun parseNewc(pos: Long, crc: Boolean, out: ArrayList<Pair<Record, Group>>, links: MutableMap<LinkKey, Group>): Long? {
        val h = source.readFully(pos, 110)
        fun field(i: Int): Long = number(h, 6 + i * 8, 8, 16, pos)
        val inode = field(0)
        val mode = field(1)
        val nlink = field(4)
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
            return null
        }
        addEntry(out, links, nameBytes, nameStart, fileSize, mode, pos + 14, dataStart,
            if (nlink > 1) LinkKey(false, major, minor, inode) else null, checksum, pos + 102)
        return align4(dataStart + fileSize)
    }

    /** odc: 6-byte magic + octal fields (76-byte header); no padding. */
    private fun parseOdc(pos: Long, out: ArrayList<Pair<Record, Group>>, links: MutableMap<LinkKey, Group>): Long? {
        val h = source.readFully(pos, 76)
        fun field(offset: Int, width: Int = 6) = number(h, offset, width, 8, pos)
        val device = field(6)
        val inode = field(12)
        val mode = field(18)
        val nlink = field(36)
        val nameSize = field(59)
        val fileSize = field(65, 11)
        val nameStart = pos + 76
        val nameBytes = readName(nameStart, nameSize)
        val dataStart = nameStart + nameSize
        source.requireRange(dataStart, fileSize)
        if (nameBytes.contentEquals(TRAILER_BYTES)) {
            checkTrailer(fileSize, pos + 65, null, pos)
            return null
        }
        addEntry(out, links, nameBytes, nameStart, fileSize, mode, pos + 18, dataStart,
            if (nlink > 1) LinkKey(true, device, 0, inode) else null, null, pos)
        return dataStart + fileSize
    }

    private fun addEntry(
        out: ArrayList<Pair<Record, Group>>, links: MutableMap<LinkKey, Group>, nameBytes: ByteArray,
        nameStart: Long, size: Long, mode: Long, modeOffset: Long, dataStart: Long,
        key: LinkKey?, checksum: Long?, checksumOffset: Long,
    ) {
        limits.checkEntryCount(out.size + 1)
        val name = nameDecoder.decodeStoredName(nameBytes, nameStart)
        val isDir = (mode and 0xF000L) == 0x4000L // S_IFDIR
        val entry = ArchiveEntry(name = name, size = if (isDir) 0L else size, isDirectory = isDir, dataOffset = dataStart).withNameBytes(nameBytes)
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

    public companion object {
        private val TRAILER_BYTES = "TRAILER!!!".encodeToByteArray()
    }
}
