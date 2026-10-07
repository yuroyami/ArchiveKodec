package io.github.yuroyami.archivekodec.archive.cpio

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.withEntryContext
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
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
 * hex fields, 4-byte padding) and `odc` (magic 070707, octal fields, no padding). The
 * archive ends at the `TRAILER!!!` entry. Old binary cpio is not handled.
 */
class CpioReader(source: RandomAccessSource) : ArchiveReader {

    private val ownedSource = ReaderSource<List<ArchiveEntry>>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    override fun toString(): String = "CpioReader(source=$ownedSource)"

    override val format = ArchiveFormat.CPIO

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close() = ownedSource.close()

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
        while (pos <= total && total - pos >= 6) {
            val magic = source.readFully(pos, 6).decodeToString()
            when (magic) {
                "070701", "070702" -> pos = parseNewc(pos, out) ?: break
                "070707" -> pos = parseOdc(pos, out) ?: break
                else -> break
            }
        }
        return out
    }

    /** newc/crc: 6-byte magic + 13 × 8 hex fields = 110-byte header; 4-byte padding. */
    private fun parseNewc(pos: Long, out: ArrayList<ArchiveEntry>): Long? {
        val h = source.readFully(pos, 110)
        fun field(i: Int): Long = hex(h, 6 + i * 8, 8)
        val mode = field(1)
        val fileSize = field(6)
        val nameSize = field(11)
        val nameStart = pos + 110
        val name = readName(nameStart, nameSize) // strip NUL
        val dataStart = align4(nameStart + nameSize)
        source.requireRange(dataStart, fileSize)
        if (name == TRAILER) return null
        addEntry(out, name, fileSize, mode, dataStart)
        return align4(dataStart + fileSize)
    }

    /** odc: 6-byte magic + octal fields (76-byte header); no padding. */
    private fun parseOdc(pos: Long, out: ArrayList<ArchiveEntry>): Long? {
        val h = source.readFully(pos, 76)
        val mode = oct(h, 18, 6)
        val nameSize = oct(h, 59, 6)
        val fileSize = oct(h, 65, 11)
        val nameStart = pos + 76
        val name = readName(nameStart, nameSize)
        val dataStart = nameStart + nameSize
        source.requireRange(dataStart, fileSize)
        if (name == TRAILER) return null
        addEntry(out, name, fileSize, mode, dataStart)
        return dataStart + fileSize
    }

    private fun addEntry(out: ArrayList<ArchiveEntry>, name: String, size: Long, mode: Long, dataStart: Long) {
        val isDir = (mode and 0xF000L) == 0x4000L // S_IFDIR
        out.add(ArchiveEntry(name = name, size = if (isDir) 0L else size, isDirectory = isDir, dataOffset = dataStart))
    }

    private fun readName(offset: Long, length: Long): String {
        if (length < 1) throw ArchiveFormatException("cpio: empty name field", offset)
        val bytes = source.readFullyChecked(offset, length)
        if (bytes.last().toInt() != 0) throw ArchiveFormatException("cpio: missing name terminator", offset)
        return bytes.decodeToString(0, bytes.indexOf(0))
    }

    private fun align4(v: Long): Long {
        val padding = (4 - (v and 3L)) and 3L
        source.requireRange(v, padding)
        return v + padding
    }

    private fun hex(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            val d = when (c) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> 0
            }
            v = (v shl 4) or d.toLong()
        }
        return v
    }

    private fun oct(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c < '0'.code || c > '7'.code) continue
            v = v * 8 + (c - '0'.code)
        }
        return v
    }

    companion object {
        private const val TRAILER = "TRAILER!!!"
    }
}
