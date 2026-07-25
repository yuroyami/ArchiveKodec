package io.github.yuroyami.kitearchive.archive.ar

import io.github.yuroyami.kitearchive.archive.ArchiveEntry
import io.github.yuroyami.kitearchive.archive.ArchiveFormat
import io.github.yuroyami.kitearchive.archive.ArchiveReader
import io.github.yuroyami.kitearchive.archive.RandomAccessSource
import io.github.yuroyami.kitearchive.archive.readFully
import io.github.yuroyami.kitearchive.security.ArchiveLimits

/**
 * Unix `ar` reader (`.a` / `.deb` / `.lib`). No compression: concatenated members
 * with 60-byte ASCII headers. Handles GNU trailing-slash short names and the GNU
 * `//` long-name table (`/offset` references); BSD `#1/len` long names are TODO.
 * The GNU symbol table (`/`) and the name table (`//`) are skipped as entries.
 */
class ArReader(private val source: RandomAccessSource) : ArchiveReader {

    override val format = ArchiveFormat.AR

    private val parsed: List<ArchiveEntry> by lazy { parse() }

    override fun entries(): List<ArchiveEntry> = parsed

    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        limits.checkOutputSize(entry.size)
        return source.readFully(entry.dataOffset, entry.size.toInt())
    }

    private fun parse(): List<ArchiveEntry> {
        val total = source.size
        if (total < 8 || source.readFully(0, 8).decodeToString() != MAGIC) error("not an ar archive")

        // First pass picks up the GNU long-name table ("//") so "/offset" names resolve.
        var nameTable: ByteArray? = null
        val out = ArrayList<ArchiveEntry>()
        var pos = 8L
        while (pos + 60 <= total) {
            val h = source.readFully(pos, 60)
            if ((h[58].toInt() and 0xFF) != 0x60 || (h[59].toInt() and 0xFF) != 0x0A) break
            val rawName = h.decodeToString(0, 16).trimEnd()
            val size = h.decodeToString(48, 58).trim().toLongOrNull() ?: break
            val dataStart = pos + 60

            when {
                rawName == "//" -> nameTable = source.readFully(dataStart, size.toInt())
                rawName == "/" || rawName.isEmpty() -> { /* symbol table, skipped */ }
                rawName.startsWith("/") && rawName.length > 1 && rawName[1].isDigit() -> {
                    val off = rawName.substring(1).toInt()
                    val name = nameTable?.let { extractLongName(it, off) } ?: rawName
                    out.add(ArchiveEntry(name = name, size = size, isDirectory = false, dataOffset = dataStart))
                }
                else -> {
                    out.add(ArchiveEntry(name = rawName.removeSuffix("/"), size = size, isDirectory = false, dataOffset = dataStart))
                }
            }
            pos = dataStart + size + (size and 1L) // members are 2-byte aligned
        }
        return out
    }

    private fun extractLongName(table: ByteArray, offset: Int): String {
        var end = offset
        while (end < table.size && table[end].toInt() != 0x0A) end++ // names end with '\n'
        return table.decodeToString(offset, end).removeSuffix("/")
    }

    companion object {
        private const val MAGIC = "!<arch>\n"
    }
}
