package io.github.yuroyami.archivekodec.archive.ar

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.DuplicateNames
import io.github.yuroyami.archivekodec.archive.decodeStoredName
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.SourceBuffer
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.PathGuard
import kotlin.jvm.JvmOverloads

/**
 * Writes a Unix `ar` archive to a [ByteSink] one member at a time, so the archive and its
 * members never sit in memory whole. Names longer than 16 bytes, or with a space, are stored
 * inline after the header as BSD and macOS `ar` write them, which GNU `ar` also reads; a GNU
 * `//` name table would have to come before every member. Not thread-safe.
 */
public class ArStreamWriter(private val sink: ByteSink) {
    private var started = false

    /**
     * Write a member of [size] bytes read from [data], which must hold exactly that many.
     *
     * @throws ArchiveFormatException for a name with a path, ASCII controls or a reserved name,
     *   or when [data] holds a different number of bytes than [size].
     * @throws ArchiveException for a failure of [data] or the sink.
     */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun putFile(name: String, size: Long, data: ByteSource, mode: Int = 0x81A4, modificationTime: Long = 0, uid: Int = 0, gid: Int = 0) {
        require(size >= 0) { "ar: size must be nonnegative" }
        // The same field limits as ArWriter.FileSpec.
        require(modificationTime in 0..999_999_999_999L) { "ar: modificationTime must fit 12 decimal digits" }
        require(uid in 0..999_999 && gid in 0..999_999) { "ar: uid and gid must fit six decimal digits" }
        require(mode in 0..0xFFFFFF) { "ar: mode must fit eight octal digits" }
        val encoded = ArWriter.encodeName(name)
        val inline = encoded.size > 16 || ' ' in name || name.startsWith("#1/")
        val stored = (if (inline) encoded.size else 0) + size
        require(stored <= 9_999_999_999L) { "ar: a member of $stored bytes does not fit the 10-digit size field" }
        if (!started) {
            started = true
            val magic = "!<arch>\n".encodeToByteArray()
            sink.write(magic, 0, magic.size)
        }
        val header = ArWriter.header(if (inline) "#1/${encoded.size}".encodeToByteArray() else encoded, stored, modificationTime, uid, gid, mode)
        sink.write(header, 0, header.size)
        if (inline) sink.write(encoded, 0, encoded.size)
        val buffer = ByteArray(minOf(size, 64L * 1024).toInt().coerceAtLeast(1))
        var copied = 0L
        while (true) {
            val n = data.read(buffer, 0, buffer.size)
            if (n < 0) break
            if (copied + n > size) throw ArchiveFormatException("ar: '$name' holds more than its declared $size bytes", entryName = name)
            sink.write(buffer, 0, n)
            copied += n
        }
        if (copied != size) throw ArchiveFormatException("ar: '$name' holds $copied bytes, not its declared $size", entryName = name)
        if (stored and 1 != 0L) sink.write(byteArrayOf(10), 0, 1)
    }

    /** Write the archive magic if no member was written, so an empty archive is still valid. Later calls do nothing. */
    @Throws(ArchiveException::class)
    public fun finish() {
        if (started) return
        started = true
        val magic = "!<arch>\n".encodeToByteArray()
        sink.write(magic, 0, magic.size)
    }
}

/**
 * Reads a Unix `ar` archive forward from a [ByteSource], one member at a time, without
 * seeking: [next] moves to the next member, skipping whatever of the current one was not
 * read, and [read] streams the current member's data. It lists what [ArReader] lists: short,
 * GNU `//` table and BSD `#1/` names, skipping symbol indexes. GNU thin archives, whose data
 * lives outside the archive, are refused with [ArchiveUnsupportedException]. Names are checked
 * as [ArchiveLimits.rejectUnsafeNames] says. Not thread-safe.
 *
 * Decoders write to a [ByteSink] rather than offer a source, so a compressed archive must be
 * decoded first, to a file for example.
 */
public class ArStreamReader @JvmOverloads public constructor(
    source: ByteSource,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    private val nameDecoder: ArchiveNameDecoder = ArchiveNameDecoder.UTF8,
) {
    private val input = SourceBuffer(source)
    private var remaining = 0L
    private var padding = 0
    private var started = false
    private var ended = false
    private var count = 0
    private var nameTable: ByteArray? = null
    private var nulDelimitedNames = false
    private val duplicates = if (limits.rejectDuplicateNames) DuplicateNames() else null

    /** The entry [next] returned last, or null before the first and after the end. */
    public var current: ArchiveEntry? = null
        private set

    /**
     * Move to the next member and return it, or null at the end of the archive.
     *
     * @throws ArchiveFormatException for a damaged or truncated header.
     * @throws ArchiveUnsupportedException for a thin archive.
     * @throws io.github.yuroyami.archivekodec.security.ArchivePathException for an unsafe name, when the limits reject them.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException past [ArchiveLimits.maxEntries].
     */
    @Throws(ArchiveException::class)
    public fun next(): ArchiveEntry? {
        skip(remaining + padding)
        remaining = 0
        padding = 0
        current = null
        if (ended) return null
        if (!started) {
            started = true
            val magic = ByteArray(8)
            if (!input.ensure(8)) throw ArchiveFormatException("ar: invalid archive magic", 0)
            input.readFully(magic, 0, 8, "ar magic")
            when (magic.decodeToString()) {
                "!<arch>\n" -> {}
                "!<thin>\n" -> throw ArchiveUnsupportedException("ar: a thin archive's members are outside it; read it with ArReader", 0)
                else -> throw ArchiveFormatException("ar: invalid archive magic", 0)
            }
        }
        val h = ByteArray(60)
        while (true) {
            val pos = input.streamPosition
            // Like ArReader, a missing or damaged header ends the listing.
            if (!input.ensure(60)) { ended = true; return null }
            input.buffer.copyInto(h, 0, input.position, input.position + 60)
            if ((h[58].toInt() and 0xFF) != 0x60 || (h[59].toInt() and 0xFF) != 0x0A) { ended = true; return null }
            input.position += 60
            var nameEnd = 16
            while (nameEnd > 0 && h[nameEnd - 1].toInt() == 32) nameEnd--
            val nameField = h.copyOf(nameEnd)
            val rawName = nameField.decodeToString()
            val sizeText = h.decodeToString(48, 58).trim()
            if (sizeText.isEmpty() || sizeText.any { it !in '0'..'9' }) {
                throw ArchiveFormatException("ar: invalid member size '$sizeText'", pos + 48, rawName)
            }
            val size = sizeText.toLongOrNull() ?: throw ArchiveFormatException("ar: member size overflows Long", pos + 48, rawName)
            val dataStart = pos + 60
            val mtime = h.decodeToString(16, 28).trim().takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
            val mode = h.decodeToString(40, 48).trim().takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'7' } }
                ?.toIntOrNull(8)?.let { if (it and 0xF000 == 0) it or 0x8000 else it }
            val pad = (size and 1L).toInt()
            fun entry(name: String, nameBytes: ByteArray, dataSize: Long): ArchiveEntry {
                val entry = ArchiveEntry(name, dataSize, false) { lastModifiedEpochSeconds = mtime; unixMode = mode }.withNameBytes(nameBytes)
                if (limits.rejectUnsafeNames && !PathGuard.isRootDirectory(entry)) PathGuard.sanitize(entry.name)
                duplicates?.add(entry)
                return entry
            }
            when {
                rawName == "//" -> {
                    if (size > Int.MAX_VALUE) throw ArchiveFormatException("ar: name table of $size bytes cannot fit in a ByteArray", pos, rawName)
                    limits.checkOutputSize(size)
                    val table = ByteArray(size.toInt())
                    input.readFully(table, 0, table.size, "ar name table")
                    skip(pad.toLong())
                    nameTable = table
                    nulDelimitedNames = 0.toByte() in table
                }
                rawName == "/" || rawName == "/SYM64/" || rawName.isEmpty() || (pos == 8L && isBsdSymbolTable(rawName.removeSuffix("/"))) ->
                    skip(size + pad)
                rawName.startsWith("#1/") -> {
                    val digits = rawName.substring(3)
                    val nameLength = digits.toLongOrNull()
                    if (nameLength == null || digits.isEmpty() || digits.any { it !in '0'..'9' }) {
                        throw ArchiveFormatException("ar: invalid BSD name length '$rawName'", pos, rawName)
                    }
                    if (nameLength > size) throw ArchiveFormatException("ar: BSD name length $nameLength exceeds member size $size", pos, rawName)
                    if (nameLength > 64 * 1024) throw ArchiveFormatException("ar: BSD name length $nameLength is too long", pos, rawName)
                    val stored = ByteArray(nameLength.toInt())
                    input.readFully(stored, 0, stored.size, "ar member name")
                    var end = stored.size
                    while (end > 0 && stored[end - 1].toInt() == 0) end--
                    val nameBytes = stored.copyOf(end)
                    if (pos == 8L && isBsdSymbolTable(nameBytes.decodeToString())) {
                        skip(size - nameLength + pad)
                        continue
                    }
                    limits.checkEntryCount(++count)
                    return start(entry(nameDecoder.decodeStoredName(nameBytes, dataStart), nameBytes, size - nameLength), size - nameLength, pad)
                }
                rawName.startsWith("/") && rawName.length > 1 && rawName[1].isDigit() -> {
                    val digits = rawName.substring(1)
                    val off = digits.toIntOrNull()
                    if (off == null || digits.any { it !in '0'..'9' }) throw ArchiveFormatException("ar: invalid long-name offset '$rawName'", pos, rawName)
                    val table = nameTable ?: throw ArchiveFormatException("ar: missing long-name table", pos, rawName)
                    if (off !in table.indices) throw ArchiveFormatException("ar: long-name offset $off exceeds table", pos, rawName)
                    val nameBytes = longName(table, off, pos, rawName)
                    limits.checkEntryCount(++count)
                    return start(entry(nameDecoder.decodeStoredName(nameBytes, pos), nameBytes, size), size, pad)
                }
                else -> {
                    val nameBytes = if (nameField.lastOrNull()?.toInt() == 47) nameField.copyOf(nameField.size - 1) else nameField
                    limits.checkEntryCount(++count)
                    return start(entry(nameDecoder.decodeStoredName(nameBytes, pos), nameBytes, size), size, pad)
                }
            }
        }
    }

    private fun start(entry: ArchiveEntry, size: Long, pad: Int): ArchiveEntry {
        remaining = size
        padding = pad
        current = entry
        return entry
    }

    /**
     * Stream the current member's data, or the part not read yet, to [sink]; return the bytes
     * written. [limits] bound the member's size.
     * @throws ArchiveException for a truncated archive or a sink failure.
     */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun read(sink: ByteSink, limits: ArchiveLimits = this.limits): Long {
        val entry = current ?: throw IllegalStateException("no current entry; call next() first")
        limits.checkOutputSize(remaining)
        var written = 0L
        while (remaining > 0) {
            if (!input.ensure(1)) throw ArchiveFormatException("ar: '${entry.name}' is truncated", input.streamPosition, entry.name)
            val n = minOf(remaining, input.available.toLong()).toInt()
            sink.write(input.buffer, input.position, n)
            input.position += n
            remaining -= n
            written += n
        }
        return written
    }

    /** The current member's data as a source, for a decoder or another writer. */
    public fun data(): ByteSource = ByteSource { into, offset, length ->
        val entry = current ?: throw IllegalStateException("no current entry; call next() first")
        when {
            remaining == 0L -> -1
            !input.ensure(1) -> throw ArchiveFormatException("ar: '${entry.name}' is truncated", input.streamPosition, entry.name)
            else -> {
                val n = minOf(remaining, input.available.toLong(), length.toLong()).toInt()
                input.buffer.copyInto(into, offset, input.position, input.position + n)
                input.position += n
                remaining -= n
                n
            }
        }
    }

    private fun skip(count: Long) {
        var left = count
        while (left > 0) {
            if (!input.ensure(1)) throw ArchiveFormatException("ar: archive is truncated", input.streamPosition)
            val n = minOf(left, input.available.toLong()).toInt()
            input.position += n
            left -= n
        }
    }

    private fun isBsdSymbolTable(name: String): Boolean = name == "__.SYMDEF" ||
        name == "__.SYMDEF SORTED" || name == "__.SYMDEF_64" || name == "__.SYMDEF_64 SORTED"

    private fun longName(table: ByteArray, offset: Int, memberOffset: Long, reference: String): ByteArray {
        val terminator = if (nulDelimitedNames) 0 else 0x0A
        var end = offset
        while (end < table.size && table[end].toInt() != terminator) end++
        if (end == table.size) throw ArchiveFormatException("ar: unterminated long name at table offset $offset", memberOffset, reference)
        val nameEnd = if (!nulDelimitedNames && end > offset && table[end - 1].toInt() == 0x2F) end - 1 else end
        return table.copyOfRange(offset, nameEnd)
    }
}
