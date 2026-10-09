package io.github.yuroyami.archivekodec.archive.ar

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.withEntryContext
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.decodeStoredName
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.ExternalMemberResolver
import io.github.yuroyami.archivekodec.archive.ReaderSource
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.archive.readFullyChecked
import io.github.yuroyami.archivekodec.archive.requireRange
import io.github.yuroyami.archivekodec.archive.issuedBy
import io.github.yuroyami.archivekodec.archive.resolveIssued
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * Unix `ar` reader (`.a` / `.deb` / `.lib`). No compression: concatenated members
 * with 60-byte ASCII headers. Handles trailing-slash short names and GNU/COFF
 * `//` long-name tables (`/offset` references) and BSD `#1/len` inline names.
 * GNU indices (`/`, `/SYM64/`), the name table (`//`) and a leading BSD ranlib
 * index are skipped as entries.
 * [limits] caps the listing count and supplies the default policy for each read.
 *
 * GNU thin archives (`!<thin>`) store only member headers and paths; their members report
 * a zero [ArchiveEntry.compressedSize] and are read through [externalMembers]. Without a
 * resolver, reading one throws [io.github.yuroyami.archivekodec.ArchiveUnsupportedException].
 *
 * Owns the supplied source; close this reader to release it, including after a
 * failed operation. This reader is not thread-safe. Keep it on one thread or
 * externally serialize all access, including [close]. The list from [entries]
 * must be treated as read-only; it may share the reader's cached metadata.
 */
public class ArReader(
    source: RandomAccessSource,
    private val nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
    /** Supplies thin-archive members, which are stored outside the archive; null refuses them. */
    public val externalMembers: ExternalMemberResolver?,
) : ArchiveReader {
    public constructor(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder, limits: ArchiveLimits) :
        this(source, nameDecoder, limits, null)
    public constructor(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder) : this(source, nameDecoder, ArchiveLimits.DEFAULT)
    public constructor(source: RandomAccessSource) : this(source, ArchiveNameDecoder.UTF8)
    public constructor(source: RandomAccessSource, limits: ArchiveLimits) : this(source, ArchiveNameDecoder.UTF8, limits)

    /** Listed entries, with whether each is stored outside the archive. */
    private class Directory(val entries: List<ArchiveEntry>, val external: BooleanArray)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "ArReader(source=$ownedSource)"

    override val format: ArchiveFormat = ArchiveFormat.AR

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata {
        val external = ArrayList<Boolean>()
        val entries = parse(external)
        Directory(entries.issuedBy(this, limits), external.toBooleanArray())
    }

    @Throws(ArchiveException::class)
    override fun close(): Unit = ownedSource.close()

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "ar")
        if (!directory.external[listed.slotIn(this)]) return readListed(listed, limits)
        limits.checkOutputSize(listed.size)
        val resolver = externalMembers ?: throw ArchiveUnsupportedException(
            "ar: thin archive member is stored outside the archive; supply an ExternalMemberResolver", entryName = listed.name)
        val bytes = resolver.resolve(listed.name)
            ?: throw ArchiveFormatException("ar: external member was not found", entryName = listed.name)
        if (bytes.size.toLong() != listed.size) {
            throw ArchiveFormatException("ar: external member has ${bytes.size} bytes, the archive says ${listed.size}", entryName = listed.name)
        }
        return bytes.copyOf()
    }

    private fun readListed(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray = withEntryContext(entry.name, entry.dataOffset) {
        val source = ownedSource.source
        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.size, entry.size)
        return@withEntryContext source.readFullyChecked(entry.dataOffset, entry.size)
    }

    private fun parse(external: MutableList<Boolean>): List<ArchiveEntry> {
        val total = source.size
        val magic = if (total < 8) "" else source.readFully(0, 8).decodeToString()
        if (magic != MAGIC && magic != THIN_MAGIC) throw ArchiveFormatException("ar: invalid archive magic", 0)
        // Thin archives store index and name tables inline, but no member data.
        val thin = magic == THIN_MAGIC

        // Remember the GNU/COFF long-name table ("//") for later "/offset" references.
        var nameTable: ByteArray? = null
        var nulDelimitedNames = false
        val out = ArrayList<ArchiveEntry>()
        var pos = 8L
        while (pos <= total && total - pos >= 60) {
            val h = source.readFully(pos, 60)
            if ((h[58].toInt() and 0xFF) != 0x60 || (h[59].toInt() and 0xFF) != 0x0A) break
            var nameEnd = 16
            while (nameEnd > 0 && h[nameEnd - 1].toInt() == 32) nameEnd--
            val nameField = h.copyOf(nameEnd)
            val rawName = nameField.decodeToString() // ASCII format markers, never the configured decoder
            val sizeText = h.decodeToString(48, 58).trim()
            if (sizeText.isEmpty() || sizeText.any { it !in '0'..'9' }) {
                throw ArchiveFormatException("ar: invalid member size '$sizeText'", pos + 48, rawName)
            }
            val size = sizeText.toLongOrNull()
                ?: throw ArchiveFormatException("ar: member size overflows Long", pos + 48, rawName)
            val dataStart = pos + 60
            // Optional metadata: unparseable fields are reported as absent rather than failing the listing.
            val mtime = h.decodeToString(16, 28).trim().takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
            val mode = h.decodeToString(40, 48).trim().takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'7' } }
                ?.toIntOrNull(8)?.let { if (it and 0xF000 == 0) it or 0x8000 else it }
            val special = rawName == "/" || rawName == "//" || rawName == "/SYM64/"
            val inline = !thin || special
            if (inline) {
                source.requireRange(dataStart, size)
                source.requireRange(dataStart + size, size and 1L)
            } else if (!rawName.startsWith("/") || rawName.length < 2 || !rawName[1].isDigit()) {
                throw ArchiveFormatException("ar: thin archive member does not use the name table", pos, rawName)
            }
            val countBefore = out.size

            when {
                rawName == "//" -> {
                    val table = source.readFullyChecked(dataStart, size)
                    nameTable = table
                    // COFF strings end at NUL; an LF inside one remains filename data.
                    nulDelimitedNames = 0.toByte() in table
                }
                rawName == "/" || rawName == "/SYM64/" || rawName.isEmpty() -> { /* symbol table, skipped */ }
                pos == 8L && isBsdSymbolTable(rawName.removeSuffix("/")) -> { /* leading BSD ranlib index */ }
                rawName.startsWith("#1/") -> {
                    val digits = rawName.substring(3)
                    val nameLength = digits.toLongOrNull()
                    if (nameLength == null || digits.isEmpty() || digits.any { it !in '0'..'9' }) {
                        throw ArchiveFormatException("ar: invalid BSD name length '$rawName'", pos, rawName)
                    }
                    if (nameLength > size) {
                        throw ArchiveFormatException("ar: BSD name length $nameLength exceeds member size $size", pos, rawName)
                    }
                    if (nameLength > Int.MAX_VALUE) {
                        throw ArchiveFormatException("ar: BSD name length $nameLength cannot fit in a ByteArray", pos, rawName)
                    }
                    val storedName = withEntryContext(rawName, dataStart) { source.readFullyChecked(dataStart, nameLength) }
                    var end = storedName.size
                    while (end > 0 && storedName[end - 1].toInt() == 0) end--
                    val nameBytes = storedName.copyOf(end)
                    if (pos != 8L || !isBsdSymbolTable(nameBytes.decodeToString())) {
                        limits.checkEntryCount(out.size + 1)
                        val name = nameDecoder.decodeStoredName(nameBytes, dataStart)
                        out.add(ArchiveEntry(name, size - nameLength, false) { lastModifiedEpochSeconds = mtime; unixMode = mode }.withNameBytes(nameBytes).locatedAt(dataStart + nameLength))
                    }
                }
                rawName.startsWith("/") && rawName.length > 1 && rawName[1].isDigit() -> {
                    val digits = rawName.substring(1)
                    val off = digits.toIntOrNull()
                    if (off == null || digits.any { it !in '0'..'9' }) {
                        throw ArchiveFormatException("ar: invalid long-name offset '$rawName'", pos, rawName)
                    }
                    val table = nameTable ?: throw ArchiveFormatException("ar: missing long-name table", pos, rawName)
                    if (off !in table.indices) throw ArchiveFormatException("ar: long-name offset $off exceeds table", pos, rawName)
                    val nameBytes = extractLongName(table, off, nulDelimitedNames, pos, rawName)
                    limits.checkEntryCount(out.size + 1)
                    val name = nameDecoder.decodeStoredName(nameBytes, pos)
                    out.add(ArchiveEntry(name, size, false) {
                        lastModifiedEpochSeconds = mtime
                        unixMode = mode
                        if (!inline) compressedSize = 0
                    }.withNameBytes(nameBytes).locatedAt(if (inline) dataStart else -1))
                }
                else -> {
                    limits.checkEntryCount(out.size + 1)
                    val nameBytes = if (nameField.lastOrNull()?.toInt() == 47) nameField.copyOf(nameField.size - 1) else nameField
                    val name = nameDecoder.decodeStoredName(nameBytes, pos)
                    out.add(ArchiveEntry(name, size, false) { lastModifiedEpochSeconds = mtime; unixMode = mode }.withNameBytes(nameBytes).locatedAt(dataStart))
                }
            }
            if (out.size > countBefore) external.add(!inline)
            pos = if (inline) dataStart + size + (size and 1L) else dataStart // members are 2-byte aligned
        }
        return out
    }

    private fun isBsdSymbolTable(name: String): Boolean = name == "__.SYMDEF" ||
        name == "__.SYMDEF SORTED" || name == "__.SYMDEF_64" || name == "__.SYMDEF_64 SORTED"

    private fun extractLongName(table: ByteArray, offset: Int, nulDelimited: Boolean, memberOffset: Long, reference: String): ByteArray {
        val terminator = if (nulDelimited) 0 else 0x0A
        var end = offset
        while (end < table.size && table[end].toInt() != terminator) end++
        if (end == table.size) {
            throw ArchiveFormatException("ar: unterminated long name at table offset $offset", memberOffset, reference)
        }
        // GNU's final slash frames the LF delimiter. Before a COFF NUL it is name data.
        val nameEnd = if (!nulDelimited && end > offset && table[end - 1].toInt() == 0x2F) end - 1 else end
        return table.copyOfRange(offset, nameEnd)
    }

    /** Holds the reader's internal format constants; exposes no public operations. */
    public companion object {
        private const val MAGIC = "!<arch>\n"
        private const val THIN_MAGIC = "!<thin>\n"
    }
}
