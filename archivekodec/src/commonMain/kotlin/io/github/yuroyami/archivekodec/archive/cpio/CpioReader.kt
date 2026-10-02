package io.github.yuroyami.archivekodec.archive.cpio

import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * cpio reader for the two portable ASCII dialects: `newc`/`crc` (magic 070701/070702,
 * hex fields, 4-byte padding) and `odc` (magic 070707, octal fields, no padding). The
 * archive ends at the `TRAILER!!!` entry. Old binary cpio is not handled.
 */
class CpioReader(private val source: RandomAccessSource) : ArchiveReader {

    override val format = ArchiveFormat.CPIO

    private val parsed: List<ArchiveEntry> by lazy { parse() }

    override fun entries(): List<ArchiveEntry> = parsed

    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        limits.checkOutputSize(entry.size)
        if (entry.isDirectory || entry.size == 0L) return ByteArray(0)
        return source.readFully(entry.dataOffset, entry.size.toInt())
    }

    private fun parse(): List<ArchiveEntry> {
        val out = ArrayList<ArchiveEntry>()
        var pos = 0L
        val total = source.size
        while (pos + 6 <= total) {
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
        val nameSize = field(11).toInt()
        val nameStart = pos + 110
        val name = source.readFully(nameStart, nameSize).decodeToString(0, nameSize - 1) // strip NUL
        val dataStart = align4(nameStart + nameSize)
        if (name == TRAILER) return null
        addEntry(out, name, fileSize, mode, dataStart)
        return align4(dataStart + fileSize)
    }

    /** odc: 6-byte magic + octal fields (76-byte header); no padding. */
    private fun parseOdc(pos: Long, out: ArrayList<ArchiveEntry>): Long? {
        val h = source.readFully(pos, 76)
        val mode = oct(h, 18, 6)
        val nameSize = oct(h, 59, 6).toInt()
        val fileSize = oct(h, 65, 11)
        val nameStart = pos + 76
        val name = source.readFully(nameStart, nameSize).decodeToString(0, nameSize - 1)
        val dataStart = nameStart + nameSize
        if (name == TRAILER) return null
        addEntry(out, name, fileSize, mode, dataStart)
        return dataStart + fileSize
    }

    private fun addEntry(out: ArrayList<ArchiveEntry>, name: String, size: Long, mode: Long, dataStart: Long) {
        val isDir = (mode and 0xF000L) == 0x4000L // S_IFDIR
        out.add(ArchiveEntry(name = name, size = if (isDir) 0L else size, isDirectory = isDir, dataOffset = dataStart))
    }

    private fun align4(v: Long): Long = (v + 3) and 3L.inv()

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
