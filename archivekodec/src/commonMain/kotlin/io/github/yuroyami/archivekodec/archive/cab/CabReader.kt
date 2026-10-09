package io.github.yuroyami.archivekodec.archive.cab

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
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.deflate.InflateException
import io.github.yuroyami.archivekodec.codec.lzx.Lzx
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.DosTime
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.jvm.JvmSynthetic

/**
 * Microsoft cabinet (`.cab`) reader. A cabinet stores its files end to end in folders, each
 * one compressed stream, so reading the files of one folder in turn decodes it once: the
 * last decoded folder is cached until another folder is read or the reader is closed.
 *
 * Stored, MSZIP and LZX folders are read; Quantum folders are listed but refused on read.
 * Data block checksums are verified when present. Names use `/` in place of the
 * stored `\` and are decoded as UTF-8 when the file's UTF-8 attribute is set, otherwise with
 * [nameDecoder] (UTF-8 by default; cabinets made on Windows use its ANSI code page). Dates
 * are zoneless DOS times read as UTC.
 *
 * A cabinet in a multi-cabinet set lists its own files; files in a folder that continues
 * from or into another cabinet are refused on read. [limits] caps the listing count and
 * supplies the default policy for each read.
 *
 * Owns the supplied source; close this reader to release it, including after a failed
 * operation. This reader is not thread-safe.
 */
public class CabReader(
    source: RandomAccessSource,
    private val nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
) : ArchiveReader {
    public constructor(source: RandomAccessSource, limits: ArchiveLimits) : this(source, ArchiveNameDecoder.UTF8, limits)
    public constructor(source: RandomAccessSource) : this(source, ArchiveLimits.DEFAULT)

    /** One folder: its compression, its data blocks, and how far its files reach. */
    private class Folder(
        val start: Long, val method: Int, val windowBits: Int, val spans: Boolean,
        val blockOffsets: LongArray, val packedSizes: IntArray, val unpackedSizes: IntArray, val sums: LongArray,
    ) {
        val packed: Long = packedSizes.sumOf { it.toLong() }
        val unpacked: Long = unpackedSizes.sumOf { it.toLong() }
        var reach: Long = 0
    }

    /** Where a listed file's bytes are: a folder index (or -1) and an offset inside it. */
    private class Location(val folder: Int, val offset: Long)

    private class Directory(val entries: List<ArchiveEntry>, val locations: List<Location>, val folders: List<Folder>)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source
    private var cachedFolder = -1
    private var cachedBytes: ByteArray? = null

    /** Folder decodes so far; tests use it to show that a folder is decoded once. */
    @get:JvmSynthetic
    internal var folderDecodes: Int = 0
        private set

    override val format: ArchiveFormat = ArchiveFormat.CAB

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "CabReader(source=$ownedSource)"

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close() {
        cachedBytes = null
        cachedFolder = -1
        ownedSource.close()
    }

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "CAB")
        val location = directory.locations[listed.slotIn(this)]
        return withEntryContext(listed.name, listed.dataOffset) {
            limits.checkOutputSize(listed.size)
            if (listed.size == 0L) return@withEntryContext ByteArray(0)
            val folder = directory.folders.getOrNull(location.folder)
                ?: throw ArchiveUnsupportedException("CAB: file continues in another cabinet; cabinet sets are not supported")
            if (folder.spans) throw ArchiveUnsupportedException("CAB: folder ${location.folder} continues in another cabinet; cabinet sets are not supported")
            if (location.offset + listed.size > folder.unpacked) fail("file extends past the end of its folder's data")
            val bytes = if (cachedFolder == location.folder) cachedBytes!! else {
                folderDecodes++
                cachedBytes = null
                decode(folder, limits).also { cachedFolder = location.folder; cachedBytes = it }
            }
            bytes.copyOfRange(location.offset.toInt(), (location.offset + listed.size).toInt())
        }
    }

    private fun fail(message: String, offset: Long? = null): Nothing = throw ArchiveFormatException("CAB: $message", offset)

    // ---- Parsing ----

    private fun parse(): Directory {
        val size = source.size
        if (size < HEADER_SIZE) fail("file is shorter than the cabinet header", 0)
        val header = source.readFully(0, HEADER_SIZE)
        if (readU32Le(header, 0) != SIGNATURE) fail("missing MSCF signature", 0)
        // The version (1.3 in every known cabinet) is not checked, as cabextract and 7-Zip do not.
        val filesOffset = readU32Le(header, 16)
        val folderCount = readU16Le(header, 26)
        val fileCount = readU16Le(header, 28)
        val flags = readU16Le(header, 30)
        limits.checkEntryCount(fileCount)

        var position = HEADER_SIZE.toLong()
        var folderReserve = 0
        var dataReserve = 0
        if (flags and RESERVE_PRESENT != 0) {
            if (position + 4 > size) fail("reserve sizes run past the end of the file", position)
            val sizes = source.readFully(position, 4)
            val headerReserve = readU16Le(sizes, 0)
            folderReserve = sizes[2].toInt() and 0xFF
            dataReserve = sizes[3].toInt() and 0xFF
            position += 4 + headerReserve
        }
        // The previous and next cabinet's file and disk names, each NUL-terminated.
        repeat((if (flags and PREVIOUS_CABINET != 0) 2 else 0) + (if (flags and NEXT_CABINET != 0) 2 else 0)) {
            position = afterString(position)
        }

        val folders = ArrayList<Folder>(folderCount)
        for (i in 0 until folderCount) {
            val recordSize = 8 + folderReserve
            if (position + recordSize > size) fail("folder table runs past the end of the file", position)
            val record = source.readFully(position, 8)
            val type = readU16Le(record, 6)
            val spans = (i == 0 && flags and PREVIOUS_CABINET != 0) || (i == folderCount - 1 && flags and NEXT_CABINET != 0)
            folders.add(blocks(readU32Le(record, 0), readU16Le(record, 4), type, dataReserve, spans, position))
            position += recordSize
        }

        if (filesOffset > size) fail("file table lies outside the file", 16)
        val table = source.readFully(filesOffset, minOf(size - filesOffset, fileCount * (FILE_HEADER + MAX_NAME + 1L)).toInt())
        val entries = ArrayList<ArchiveEntry>(fileCount)
        val locations = ArrayList<Location>(fileCount)
        var p = 0
        for (i in 0 until fileCount) {
            if (p + FILE_HEADER > table.size) fail("file table runs past the end of the file", filesOffset + p)
            val fileSize = readU32Le(table, p)
            val offset = readU32Le(table, p + 4)
            val folderIndex = readU16Le(table, p + 8)
            val date = readU16Le(table, p + 10)
            val time = readU16Le(table, p + 12)
            val attributes = readU16Le(table, p + 14)
            var end = p + FILE_HEADER
            while (end < table.size && table[end].toInt() != 0) end++
            if (end >= table.size) fail("file name is not terminated", filesOffset + p + FILE_HEADER)
            val raw = table.copyOfRange(p + FILE_HEADER, end)
            if (raw.isEmpty()) fail("empty file name", filesOffset + p + FILE_HEADER)
            val decoder = if (attributes and NAME_IS_UTF8 != 0) ArchiveNameDecoder.UTF8 else nameDecoder
            val name = decoder.decodeStoredName(raw, filesOffset + p + FILE_HEADER).replace('\\', '/')
            val folder = when (folderIndex) {
                CONTINUED_FROM_PREVIOUS, CONTINUED_PREVIOUS_AND_NEXT -> -1
                CONTINUED_TO_NEXT -> -1
                else -> if (folderIndex < folders.size) folderIndex else fail("file names folder $folderIndex of ${folders.size}", filesOffset + p + 8)
            }
            val owner = folders.getOrNull(folder)
            if (owner != null) owner.reach = maxOf(owner.reach, offset + fileSize)
            entries.add(ArchiveEntry(name, fileSize, false) {
                lastModifiedEpochSeconds = DosTime.toEpochSeconds(date, time)
                compressedSize = when {
                    owner == null -> 0
                    owner.method == METHOD_NONE -> fileSize
                    owner.unpacked == 0L -> 0
                    else -> (owner.packed.toDouble() * fileSize / owner.unpacked).toLong()
                }
                codec = when (owner?.method) {
                    METHOD_NONE -> CodecId.STORE
                    METHOD_MSZIP -> CodecId.DEFLATE
                    METHOD_QUANTUM -> CodecId("quantum")
                    METHOD_LZX -> CodecId("lzx")
                    else -> CodecId.STORE
                }
            }.withNameBytes(raw).let { if (owner != null) it.locatedAt(owner.start) else it })
            locations.add(Location(folder, offset))
            p = end + 1
        }
        return Directory(entries.issuedBy(this, limits), locations, folders)
    }

    private fun afterString(start: Long): Long {
        val chunk = source.readFully(start, minOf(source.size - start, MAX_NAME + 1L).toInt())
        val end = chunk.indexOf(0)
        if (end < 0) fail("cabinet set name is not terminated", start)
        return start + end + 1
    }

    /** Walk a folder's data block headers, so sizes are known and the blocks lie inside the file. */
    private fun blocks(start: Long, count: Int, type: Int, reserve: Int, spans: Boolean, at: Long): Folder {
        val offsets = LongArray(count)
        val packed = IntArray(count)
        val unpacked = IntArray(count)
        val sums = LongArray(count)
        var position = start
        for (i in 0 until count) {
            if (position + 8 + reserve > source.size) fail("data block $i lies outside the file", position)
            val header = source.readFully(position, 8)
            sums[i] = readU32Le(header, 0)
            packed[i] = readU16Le(header, 4)
            unpacked[i] = readU16Le(header, 6)
            offsets[i] = position + 8 + reserve
            if (offsets[i] + packed[i] > source.size) fail("data block $i runs past the end of the file", position)
            if (unpacked[i] > MAX_BLOCK) fail("data block $i expands to ${unpacked[i]} bytes, above $MAX_BLOCK", position + 6)
            position = offsets[i] + packed[i]
        }
        val method = type and 0x0F
        if (method > METHOD_LZX) throw ArchiveUnsupportedException("CAB: unknown compression type $method", at + 6)
        return Folder(start, method, (type ushr 8) and 0x1F, spans, offsets, packed, unpacked, sums)
    }

    // ---- Decoding ----

    private fun decode(folder: Folder, limits: ArchiveLimits): ByteArray {
        val wanted = folder.reach
        limits.checkOutputSize(wanted)
        return when (folder.method) {
            METHOD_NONE -> {
                limits.checkRatio(wanted, wanted)
                val out = ByteArray(wanted.toInt())
                var at = 0
                var i = 0
                while (at < out.size) {
                    val block = block(folder, i)
                    if (block.size != folder.unpackedSizes[i]) fail("stored data block $i holds ${block.size} bytes but expands to ${folder.unpackedSizes[i]}", folder.blockOffsets[i])
                    val n = minOf(block.size, out.size - at)
                    block.copyInto(out, at, 0, n)
                    at += n
                    i++
                }
                out
            }
            METHOD_MSZIP -> {
                val output = Inflate.Output(limits, folder.packed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), sizeHint = wanted)
                var i = 0
                while (output.size < wanted) {
                    val block = block(folder, i)
                    if (block.size < 2 || block[0] != 'C'.code.toByte() || block[1] != 'K'.code.toByte()) {
                        fail("MSZIP data block $i lacks its CK signature", folder.blockOffsets[i])
                    }
                    val before = output.size
                    val end = try {
                        Inflate.inflateInto(block, 2, block.size, output, keepHistory = true)
                    } catch (error: InflateException) {
                        throw InflateException(error.error, folder.blockOffsets[i] + (error.offset ?: 0), null, error)
                    }
                    if (end != block.size) fail("MSZIP data block $i has ${block.size - end} bytes after its stream", folder.blockOffsets[i] + end)
                    if (output.size - before != folder.unpackedSizes[i]) {
                        fail("MSZIP data block $i expands to ${output.size - before} bytes, not ${folder.unpackedSizes[i]}", folder.blockOffsets[i])
                    }
                    i++
                }
                output.takeByteArray()
            }
            METHOD_LZX -> {
                limits.checkRatio(folder.packed, wanted)
                // One LZX stream runs through the folder; its blocks are 32 KiB frames.
                val frames = minOf(folder.unpacked, (wanted + MAX_BLOCK - 1) / MAX_BLOCK * MAX_BLOCK)
                val parts = ArrayList<ByteArray>()
                var covered = 0L
                var i = 0
                while (covered < frames) {
                    parts.add(block(folder, i))
                    covered += folder.unpackedSizes[i]
                    i++
                }
                val input = ByteArray(parts.sumOf { it.size })
                var at = 0
                for (part in parts) { part.copyInto(input, at); at += part.size }
                try {
                    Lzx.decodeCabinet(input, folder.windowBits, folder.unpacked, wanted)
                } catch (error: ArchiveFormatException) {
                    throw ArchiveFormatException("CAB: ${error.message}", folder.start, cause = error)
                }
            }
            else -> throw ArchiveUnsupportedException("CAB: Quantum compression is not supported", folder.start)
        }
    }

    /** Data block [i] of [folder], checked against its checksum when it has one. */
    private fun block(folder: Folder, i: Int): ByteArray {
        if (i >= folder.blockOffsets.size) fail("folder data ends before its files do", folder.start)
        val data = source.readFullyChecked(folder.blockOffsets[i], folder.packedSizes[i].toLong())
        val stored = folder.sums[i]
        if (stored != 0L) {
            val sizes = (folder.packedSizes[i].toLong() or (folder.unpackedSizes[i].toLong() shl 16)).toInt()
            if ((checksum(data, 0) xor sizes.toLong()) and 0xFFFFFFFFL != stored) fail("data block $i checksum mismatch", folder.blockOffsets[i])
        }
        return data
    }

    private companion object {
        const val SIGNATURE = 0x4643534DL // "MSCF"
        const val HEADER_SIZE = 36
        const val FILE_HEADER = 16
        const val MAX_NAME = 256
        const val MAX_BLOCK = 32768

        const val PREVIOUS_CABINET = 0x0001
        const val NEXT_CABINET = 0x0002
        const val RESERVE_PRESENT = 0x0004
        const val NAME_IS_UTF8 = 0x80

        const val CONTINUED_FROM_PREVIOUS = 0xFFFD
        const val CONTINUED_TO_NEXT = 0xFFFE
        const val CONTINUED_PREVIOUS_AND_NEXT = 0xFFFF

        const val METHOD_NONE = 0
        const val METHOD_MSZIP = 1
        const val METHOD_QUANTUM = 2
        const val METHOD_LZX = 3

        /**
         * The cabinet checksum: little-endian words XORed together, with a final partial word
         * taken most significant byte first, as Microsoft's code does.
         */
        fun checksum(data: ByteArray, seed: Long): Long {
            var sum = seed.toInt()
            val words = data.size / 4
            for (w in 0 until words) sum = sum xor readU32Le(data, 4 * w).toInt()
            var tail = 0
            val rest = data.size and 3
            var at = 4 * words
            if (rest == 3) tail = tail or ((data[at++].toInt() and 0xFF) shl 16)
            if (rest >= 2) tail = tail or ((data[at++].toInt() and 0xFF) shl 8)
            if (rest >= 1) tail = tail or (data[at].toInt() and 0xFF)
            return (sum xor tail).toLong() and 0xFFFFFFFFL
        }
    }
}
