package io.github.yuroyami.archivekodec.archive.ar

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
 * Unix `ar` reader (`.a` / `.deb` / `.lib`). No compression: concatenated members
 * with 60-byte ASCII headers. Handles trailing-slash short names and GNU/COFF
 * `//` long-name tables (`/offset` references); BSD `#1/len` long names are TODO.
 * The GNU symbol tables (`/`, `/SYM64/`) and the name table (`//`) are skipped as entries.
 */
class ArReader(source: RandomAccessSource) : ArchiveReader {

    private val ownedSource = ReaderSource<List<ArchiveEntry>>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    override fun toString(): String = "ArReader(source=$ownedSource)"

    override val format = ArchiveFormat.AR

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close() = ownedSource.close()

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray = withEntryContext(entry.name, entry.dataOffset) {
        val source = ownedSource.source
        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.size, entry.size)
        return@withEntryContext source.readFullyChecked(entry.dataOffset, entry.size)
    }

    private fun parse(): List<ArchiveEntry> {
        val total = source.size
        if (total < 8 || source.readFully(0, 8).decodeToString() != MAGIC) throw ArchiveFormatException("ar: invalid archive magic", 0)

        // Remember the GNU/COFF long-name table ("//") for later "/offset" references.
        var nameTable: ByteArray? = null
        var nulDelimitedNames = false
        val out = ArrayList<ArchiveEntry>()
        var pos = 8L
        while (pos <= total && total - pos >= 60) {
            val h = source.readFully(pos, 60)
            if ((h[58].toInt() and 0xFF) != 0x60 || (h[59].toInt() and 0xFF) != 0x0A) break
            val rawName = h.decodeToString(0, 16).trimEnd()
            val sizeText = h.decodeToString(48, 58).trim()
            if (sizeText.isEmpty() || sizeText.any { it !in '0'..'9' }) {
                throw ArchiveFormatException("ar: invalid member size '$sizeText'", pos + 48, rawName)
            }
            val size = sizeText.toLongOrNull()
                ?: throw ArchiveFormatException("ar: member size overflows Long", pos + 48, rawName)
            val dataStart = pos + 60
            source.requireRange(dataStart, size)
            source.requireRange(dataStart + size, size and 1L)

            when {
                rawName == "//" -> {
                    val table = source.readFullyChecked(dataStart, size)
                    nameTable = table
                    // COFF strings end at NUL; an LF inside one remains filename data.
                    nulDelimitedNames = 0.toByte() in table
                }
                rawName == "/" || rawName == "/SYM64/" || rawName.isEmpty() -> { /* symbol table, skipped */ }
                rawName.startsWith("/") && rawName.length > 1 && rawName[1].isDigit() -> {
                    val digits = rawName.substring(1)
                    val off = digits.toIntOrNull()
                    if (off == null || digits.any { it !in '0'..'9' }) {
                        throw ArchiveFormatException("ar: invalid long-name offset '$rawName'", pos, rawName)
                    }
                    val table = nameTable ?: throw ArchiveFormatException("ar: missing long-name table", pos, rawName)
                    if (off !in table.indices) throw ArchiveFormatException("ar: long-name offset $off exceeds table", pos, rawName)
                    val name = extractLongName(table, off, nulDelimitedNames, pos, rawName)
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

    private fun extractLongName(table: ByteArray, offset: Int, nulDelimited: Boolean, memberOffset: Long, reference: String): String {
        val terminator = if (nulDelimited) 0 else 0x0A
        var end = offset
        while (end < table.size && table[end].toInt() != terminator) end++
        if (end == table.size) {
            throw ArchiveFormatException("ar: unterminated long name at table offset $offset", memberOffset, reference)
        }
        // GNU's final slash frames the LF delimiter. Before a COFF NUL it is name data.
        val nameEnd = if (!nulDelimited && end > offset && table[end - 1].toInt() == 0x2F) end - 1 else end
        return table.decodeToString(offset, nameEnd)
    }

    companion object {
        private const val MAGIC = "!<arch>\n"
    }
}
