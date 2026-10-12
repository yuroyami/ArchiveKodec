package io.github.yuroyami.archivekodec.archive.iso

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.ReaderSource
import io.github.yuroyami.archivekodec.archive.decodeStoredName
import io.github.yuroyami.archivekodec.archive.issuedBy
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.archive.readFullyChecked
import io.github.yuroyami.archivekodec.archive.resolveIssued
import io.github.yuroyami.archivekodec.archive.withEntryContext
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le

/**
 * ISO 9660 (ECMA-119) reader, as used by `.iso` images. The volume descriptors start at
 * byte 32768. Names come from the best tree the image has:
 *
 * - **Rock Ridge** (SUSP entries in the primary tree): POSIX names, modes (as
 *   [ArchiveEntry.unixMode]), modification times and symbolic links, with continuation
 *   areas and relocated deep directories followed.
 * - **Joliet** (a supplementary descriptor): UTF-16 names.
 * - Otherwise the plain ISO 9660 identifiers, without their `;1` version suffix.
 *
 * Directories are listed with a trailing slash before their contents. A symbolic link is
 * listed with its link mode and reads as its target text. Files recorded in several extents
 * are joined; interleaved files are listed but refused on read. Names are decoded with
 * [nameDecoder] (UTF-8 by default), except Joliet's, which are always UTF-16. Hidden files
 * are listed; associated files are not. [limits] caps the listing count and supplies the
 * default policy for each read.
 *
 * Owns the supplied source; close this reader to release it, including after a failed
 * operation. This reader is not thread-safe.
 */
public class IsoReader(
    source: RandomAccessSource,
    private val nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
) : ArchiveReader {
    public constructor(source: RandomAccessSource, limits: ArchiveLimits) : this(source, ArchiveNameDecoder.UTF8, limits)
    public constructor(source: RandomAccessSource) : this(source, ArchiveLimits.DEFAULT)

    /** Where a listed member's bytes are: extents as (offset, length) pairs, or a link target. */
    private class Item(val extents: LongArray, val target: ByteArray?, val interleaved: Boolean)

    private class Directory(val entries: List<ArchiveEntry>, val items: List<Item>)

    private enum class Names { ROCK_RIDGE, JOLIET, PLAIN }

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "IsoReader(source=$ownedSource)"

    override val format: ArchiveFormat = ArchiveFormat.ISO9660

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close(): Unit = ownedSource.close()

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "ISO 9660")
        val item = directory.items[listed.slotIn(this)]
        return withEntryContext(listed.name, listed.dataOffset) {
            limits.checkOutputSize(listed.size)
            limits.checkRatio(listed.size, listed.size)
            when {
                listed.isDirectory -> ByteArray(0)
                item.target != null -> item.target.copyOf()
                item.interleaved -> throw ArchiveUnsupportedException("ISO 9660: interleaved files are not supported", listed.dataOffset, listed.name)
                else -> {
                    if (listed.size > Int.MAX_VALUE) throw ArchiveFormatException("ISO 9660: file of ${listed.size} bytes cannot fit in a ByteArray")
                    val out = ByteArray(listed.size.toInt())
                    var at = 0
                    for (i in item.extents.indices step 2) {
                        val part = source.readFullyChecked(item.extents[i], item.extents[i + 1])
                        part.copyInto(out, at)
                        at += part.size
                    }
                    out
                }
            }
        }
    }

    // ---- Parsing ----

    private var blockSize = 2048
    private var names = Names.PLAIN
    private val entries = ArrayList<ArchiveEntry>()
    private val items = ArrayList<Item>()
    private val visited = HashSet<Long>()

    private fun fail(message: String, offset: Long? = null): Nothing = throw ArchiveFormatException("ISO 9660: $message", offset)

    private fun parse(): Directory {
        val size = source.size
        if (size < SYSTEM_AREA + SECTOR) fail("image is smaller than its system area and first descriptor", 0)
        var primary: ByteArray? = null
        var joliet: ByteArray? = null
        var sector = 16L
        while (true) {
            val offset = sector * SECTOR
            if (offset + SECTOR > size) fail("volume descriptors run past the end of the image", offset)
            val descriptor = source.readFully(offset, SECTOR)
            if (!hasStandardId(descriptor, 1)) fail("volume descriptor $sector lacks CD001", offset + 1)
            when (descriptor[0].toInt() and 0xFF) {
                1 -> if (primary == null) primary = descriptor
                2 -> if (isJoliet(descriptor)) joliet = descriptor
                255 -> break
            }
            if (++sector > 16L + MAX_DESCRIPTORS) fail("no volume descriptor set terminator", offset)
        }
        val pvd = primary ?: fail("no primary volume descriptor", SYSTEM_AREA.toLong())
        blockSize = readU16Le(pvd, 128)
        if (blockSize !in listOf(512, 1024, 2048)) throw ArchiveUnsupportedException("ISO 9660: logical block size $blockSize is not supported", SYSTEM_AREA + 128L)

        val (rootExtent, rootLength) = rootOf(pvd)
        names = when {
            hasRockRidge(rootExtent, rootLength) -> Names.ROCK_RIDGE
            joliet != null -> Names.JOLIET
            else -> Names.PLAIN
        }
        val (extent, length) = if (names == Names.JOLIET) rootOf(joliet!!) else rootExtent to rootLength
        walk(extent, length, "", 0)
        return Directory(entries.issuedBy(this, limits), items)
    }

    private fun rootOf(descriptor: ByteArray): Pair<Long, Long> =
        readU32Le(descriptor, 156 + 2) * blockSize to readU32Le(descriptor, 156 + 10)

    private fun isJoliet(descriptor: ByteArray): Boolean {
        val escape = descriptor.copyOfRange(88, 91)
        return escape[0] == '%'.code.toByte() && escape[1] == '/'.code.toByte() &&
            (escape[2] == '@'.code.toByte() || escape[2] == 'C'.code.toByte() || escape[2] == 'E'.code.toByte())
    }

    /** Rock Ridge announces itself with a SUSP "SP" entry in the root's "." record. */
    private fun hasRockRidge(extent: Long, length: Long): Boolean {
        if (length < 34 || extent + 34 > source.size) return false
        val record = source.readFully(extent, minOf(length, blockSize.toLong()).toInt())
        val recordLength = record[0].toInt() and 0xFF
        if (recordLength < 34 || recordLength > record.size) return false
        val nameLength = record[32].toInt() and 0xFF
        val sua = 33 + nameLength + (if (nameLength % 2 == 0) 1 else 0)
        return recordLength - sua >= 7 && record[sua] == 'S'.code.toByte() && record[sua + 1] == 'P'.code.toByte() &&
            (record[sua + 4].toInt() and 0xFF) == 0xBE && (record[sua + 5].toInt() and 0xFF) == 0xEF
    }

    private fun readDirectory(extent: Long, length: Long): ByteArray {
        if (length > Int.MAX_VALUE || extent < 0 || extent + length > source.size) fail("directory extent lies outside the image", extent)
        return source.readFully(extent, length.toInt())
    }

    private class Record(
        val extent: Long, val length: Long, val flags: Int, val interleaved: Boolean,
        val identifier: ByteArray, val modified: Long?, val sua: ByteArray,
    )

    private fun walk(extent: Long, length: Long, prefix: String, depth: Int) {
        if (depth > MAX_DEPTH) fail("directories nest more than $MAX_DEPTH deep", extent)
        if (!visited.add(extent)) fail("directory at $extent is reached twice", extent)
        val data = readDirectory(extent, length)
        val records = ArrayList<Record>()
        var p = 0
        while (p < data.size) {
            val recordLength = data[p].toInt() and 0xFF
            if (recordLength == 0) {
                p = (p / blockSize + 1) * blockSize
                continue
            }
            if (recordLength < 34 || p + recordLength > data.size) fail("malformed directory record", extent + p)
            val nameLength = data[p + 32].toInt() and 0xFF
            if (33 + nameLength > recordLength) fail("directory record name overruns its record", extent + p)
            val suaStart = 33 + nameLength + (if (nameLength % 2 == 0) 1 else 0)
            records.add(Record(
                extent = readU32Le(data, p + 2) * blockSize,
                length = readU32Le(data, p + 10),
                flags = data[p + 25].toInt() and 0xFF,
                interleaved = data[p + 26].toInt() != 0 || data[p + 27].toInt() != 0,
                identifier = data.copyOfRange(p + 33, p + 33 + nameLength),
                modified = recordingTime(data, p + 18),
                sua = if (suaStart < recordLength) data.copyOfRange(p + suaStart, p + recordLength) else ByteArray(0),
            ))
            p += recordLength
        }

        var i = 0
        while (i < records.size) {
            val record = records[i]
            val id = record.identifier
            if (id.size == 1 && (id[0].toInt() == 0 || id[0].toInt() == 1)) { i++; continue } // "." and ".."
            // A multi-extent file continues in the records that follow while bit 7 is set.
            val extents = arrayListOf(record.extent, record.length)
            var last = record
            while (last.flags and 0x80 != 0 && i + 1 < records.size) {
                last = records[++i]
                extents.add(last.extent); extents.add(last.length)
            }
            i++
            if (record.flags and 0x04 != 0) continue // associated file
            val rock = if (names == Names.ROCK_RIDGE) RockRidge.parse(record.sua) { block, offset, size -> continuation(block, offset, size) } else null
            if (rock?.relocated == true) continue // shown where its "CL" entry points
            val name = rock?.name ?: plainName(id, record.flags and 0x02 != 0, extent)
            if (name.isEmpty() || name == "." || name == "..") continue
            val childLink = rock?.childLink
            val isDirectory = record.flags and 0x02 != 0 || childLink != null
            val path = prefix + name
            limits.checkEntryCount(entries.size + 1)
            val total = (0 until extents.size step 2).sumOf { extents[it + 1] }
            val target = rock?.symlinkTarget?.encodeToByteArray()
            val entry = ArchiveEntry(if (isDirectory) "$path/" else path, if (isDirectory) 0 else target?.size?.toLong() ?: total, isDirectory) {
                lastModifiedEpochSeconds = rock?.modified ?: record.modified
                compressedSize = if (isDirectory) 0 else target?.size?.toLong() ?: total
                unixMode = rock?.mode
                if (target != null) {
                    isSymbolicLink = true
                    linkTarget = rock?.symlinkTarget
                }
            }.locatedAt(record.extent)
            entries.add(entry)
            items.add(Item(extents.toLongArray(), target, record.interleaved))
            if (isDirectory && target == null) {
                if (childLink != null) {
                    val relocated = childLink * blockSize
                    walk(relocated, dotLength(relocated), "$path/", depth + 1)
                } else {
                    walk(record.extent, record.length, "$path/", depth + 1)
                }
            }
        }
    }

    /** The data length a directory's "." record gives itself. */
    private fun dotLength(extent: Long): Long {
        if (extent + 34 > source.size) fail("relocated directory lies outside the image", extent)
        return readU32Le(source.readFully(extent, 34), 10)
    }

    private fun continuation(block: Long, offset: Long, size: Long): ByteArray {
        val start = block * blockSize + offset
        if (size > blockSize || start < 0 || start + size > source.size) fail("Rock Ridge continuation area lies outside the image", start)
        return source.readFully(start, size.toInt())
    }

    private fun plainName(id: ByteArray, directory: Boolean, offset: Long): String {
        if (names == Names.JOLIET) {
            var text = CharArray(id.size / 2) { (((id[2 * it].toInt() and 0xFF) shl 8) or (id[2 * it + 1].toInt() and 0xFF)).toChar() }.concatToString()
            if (!directory) text = text.substringBefore(';')
            return text
        }
        var text = nameDecoder.decodeStoredName(id, offset)
        if (!directory) {
            text = text.substringBefore(';')
            if (text.endsWith('.')) text = text.dropLast(1)
        }
        return text
    }

    private companion object {
        const val SECTOR = 2048
        const val SYSTEM_AREA = 16 * SECTOR
        const val MAX_DESCRIPTORS = 256
        const val MAX_DEPTH = 255

        fun hasStandardId(bytes: ByteArray, at: Int): Boolean =
            bytes[at] == 'C'.code.toByte() && bytes[at + 1] == 'D'.code.toByte() && bytes[at + 2] == '0'.code.toByte() &&
                bytes[at + 3] == '0'.code.toByte() && bytes[at + 4] == '1'.code.toByte()

        /** A directory record's 7-byte recording time, or null when it is all zero. */
        fun recordingTime(bytes: ByteArray, at: Int): Long? {
            if ((0 until 6).all { bytes[at + it].toInt() == 0 }) return null
            return isoTime(
                1900 + (bytes[at].toInt() and 0xFF), bytes[at + 1].toInt() and 0xFF, bytes[at + 2].toInt() and 0xFF,
                bytes[at + 3].toInt() and 0xFF, bytes[at + 4].toInt() and 0xFF, bytes[at + 5].toInt() and 0xFF, bytes[at + 6].toInt(),
            )
        }
    }
}

/** UTC epoch seconds of an ISO 9660 date; [offset] is the zone in 15-minute steps east of UTC. */
internal fun isoTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, offset: Int): Long? {
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146_097L + doe - 719_468
    return days * 86_400 + hour * 3600 + minute * 60 + second - offset * 900L
}

/** The Rock Ridge facts of one directory record, from its SUSP entries. */
internal class RockRidge private constructor() {
    var name: String? = null
    var mode: Int? = null
    var modified: Long? = null
    var symlinkTarget: String? = null
    var childLink: Long? = null
    var relocated = false

    companion object {
        private const val MAX_CONTINUATIONS = 32

        /** Parse [area], following "CE" continuation areas that [read] fetches (block, offset, length). */
        fun parse(area: ByteArray, read: (Long, Long, Long) -> ByteArray): RockRidge {
            val result = RockRidge()
            val name = StringBuilder()
            var hasName = false
            val link = StringBuilder()
            var linkComponentOpen = false
            var hasLink = false
            var pending: ByteArray? = area
            var hops = 0
            while (pending != null) {
                val data: ByteArray = pending
                pending = null
                var p = 0
                while (data.size - p >= 4) {
                    val signature = "${data[p].toInt().toChar()}${data[p + 1].toInt().toChar()}"
                    val length = data[p + 2].toInt() and 0xFF
                    if (length < 4 || p + length > data.size) break
                    when (signature) {
                        "NM" -> if (length >= 5) {
                            val flags = data[p + 4].toInt()
                            if (flags and 0x06 == 0) {
                                name.append(data.copyOfRange(p + 5, p + length).decodeToString())
                                hasName = true
                            }
                        }
                        "PX" -> if (length >= 12) result.mode = readU32Le(data, p + 4).toInt()
                        "SL" -> if (length >= 5) {
                            hasLink = true
                            var c = p + 5
                            while (c + 2 <= p + length) {
                                val flags = data[c].toInt() and 0xFF
                                val size = data[c + 1].toInt() and 0xFF
                                if (c + 2 + size > p + length) break
                                if (!linkComponentOpen && link.isNotEmpty() && link.last() != '/') link.append('/')
                                when {
                                    flags and 0x02 != 0 -> link.append('.')
                                    flags and 0x04 != 0 -> link.append("..")
                                    flags and 0x08 != 0 -> link.append('/')
                                    else -> link.append(data.copyOfRange(c + 2, c + 2 + size).decodeToString())
                                }
                                linkComponentOpen = flags and 0x01 != 0
                                c += 2 + size
                            }
                        }
                        "TF" -> if (length >= 5) {
                            val flags = data[p + 4].toInt() and 0xFF
                            val long = flags and 0x80 != 0
                            val width = if (long) 17 else 7
                            // Stamps appear in the order creation, modify, access, attributes.
                            if (flags and 0x02 != 0) {
                                val at = p + 5 + (if (flags and 0x01 != 0) width else 0)
                                if (at + width <= p + length) result.modified = stamp(data, at, long)
                            }
                        }
                        "CL" -> if (length >= 12) result.childLink = readU32Le(data, p + 4)
                        "RE" -> result.relocated = true
                        "CE" -> if (length >= 28 && hops++ < MAX_CONTINUATIONS) {
                            pending = read(readU32Le(data, p + 4), readU32Le(data, p + 12), readU32Le(data, p + 20))
                        }
                        "ST" -> break
                    }
                    p += length
                }
            }
            if (hasName) result.name = name.toString()
            if (hasLink) result.symlinkTarget = link.toString()
            return result
        }

        private fun stamp(data: ByteArray, at: Int, long: Boolean): Long? {
            if (!long) {
                if ((0 until 6).all { data[at + it].toInt() == 0 }) return null
                return isoTime(1900 + (data[at].toInt() and 0xFF), data[at + 1].toInt() and 0xFF, data[at + 2].toInt() and 0xFF,
                    data[at + 3].toInt() and 0xFF, data[at + 4].toInt() and 0xFF, data[at + 5].toInt() and 0xFF, data[at + 6].toInt())
            }
            val digits = data.copyOfRange(at, at + 16).decodeToString()
            if (digits.any { it !in '0'..'9' } || digits.all { it == '0' }) return null
            fun field(from: Int, to: Int) = digits.substring(from, to).toInt()
            return isoTime(field(0, 4), field(4, 6), field(6, 8), field(8, 10), field(10, 12), field(12, 14), data[at + 16].toInt())
        }
    }
}
