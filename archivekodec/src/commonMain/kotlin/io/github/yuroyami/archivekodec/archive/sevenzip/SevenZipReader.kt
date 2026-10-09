package io.github.yuroyami.archivekodec.archive.sevenzip

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.ReaderSource
import io.github.yuroyami.archivekodec.archive.issuedBy
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.archive.readFullyChecked
import io.github.yuroyami.archivekodec.archive.resolveIssued
import io.github.yuroyami.archivekodec.archive.withEntryContext
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.bzip2.Bzip2Decoder
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.lzma.Bcj
import io.github.yuroyami.archivekodec.codec.lzma.Lzma2
import io.github.yuroyami.archivekodec.codec.ppmd.Ppmd7z
import io.github.yuroyami.archivekodec.codec.lzma.LzOutput
import io.github.yuroyami.archivekodec.codec.lzma.LzmaModel
import io.github.yuroyami.archivekodec.codec.lzma.LzmaProperties
import io.github.yuroyami.archivekodec.codec.lzma.RangeDecoder
import io.github.yuroyami.archivekodec.codec.lzma.decodeLzma
import io.github.yuroyami.archivekodec.codec.lzma.lzma2DictionarySize
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.readI32Le
import io.github.yuroyami.archivekodec.util.readI64Le
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.jvm.JvmSynthetic

/**
 * 7z reader. Lists files from the header, decoding an LZMA-compressed header first, and
 * reads each file from its folder. A folder is one compressed stream holding several files
 * (a solid block): reading a file decodes its whole folder, and the most recently decoded
 * folder is cached, so reading the files of one folder in turn decodes it once. The read
 * limits apply to the folder's decoded size, since a file cannot be reached without it.
 *
 * Supported coders: copy, LZMA, LZMA2, PPMd, Deflate, Deflate64, bzip2, delta and the x86 BCJ filter,
 * chained through bind pairs. AES encryption, BCJ2 and other branch filters are refused with
 * [ArchiveUnsupportedException] naming the method. Folder and file CRC-32s are verified.
 * Entries report UTC modification times, Unix modes from p7zip's attribute extension, and
 * for solid folders a share of the packed size proportional to each file's size.
 *
 * Owns the supplied source; close this reader to release it, including after a failed
 * operation. This reader is not thread-safe.
 */
public class SevenZipReader(
    source: RandomAccessSource,
    override val limits: ArchiveLimits,
) : ArchiveReader {
    public constructor(source: RandomAccessSource) : this(source, ArchiveLimits.DEFAULT)

    private class Coder(val method: String, val inputs: Int, val outputs: Int, val properties: ByteArray)

    private class Folder(
        val coders: List<Coder>,
        val bindPairs: List<IntArray>,
        val packedStreams: IntArray,
        val unpackSizes: LongArray,
        var crc: Long? = null,
    ) {
        var firstPackStream = 0
        val mainOutput: Int = unpackSizes.indices.first { out -> bindPairs.none { it[1] == out } }
        val size: Long get() = unpackSizes[mainOutput]
    }

    private class Streams(
        val packPosition: Long,
        val packSizes: LongArray,
        val folders: List<Folder>,
        val unpackStreams: IntArray,
        val substreamSizes: LongArray,
        val substreamCrcs: Array<Long?>,
    ) {
        fun packOffset(stream: Int): Long = SIGNATURE_SIZE + packPosition + (0 until stream).sumOf { packSizes[it] }
    }

    /** Where a listed file's bytes are: a folder, an offset in it, and a CRC. */
    private class Location(val folder: Int, val offset: Long, val crc: Long?)

    private class Directory(val entries: List<ArchiveEntry>, val locations: List<Location?>, val streams: Streams?)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source
    private var cachedFolder = -1
    private var cachedBytes: ByteArray? = null

    /** File-data folder decodes so far; tests use it to show that a solid folder is decoded once. */
    @get:JvmSynthetic
    internal var folderDecodes: Int = 0
        private set

    override val format: ArchiveFormat = ArchiveFormat.SEVEN_ZIP

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "SevenZipReader(source=$ownedSource)"

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
        val listed = directory.entries.resolveIssued(this, entry, "7z")
        val location = directory.locations[listed.slotIn(this)]
        val contextOffset = location?.let { directory.streams?.run { packOffset(folders[it.folder].firstPackStream) } } ?: -1L
        return withEntryContext(listed.name, contextOffset) {
            limits.checkOutputSize(listed.size)
            if (location == null || listed.size == 0L) return@withEntryContext ByteArray(0)
            val streams = directory.streams!!
            val folder = streams.folders[location.folder]
            if (listed.isEncrypted) throw ArchiveUnsupportedException("7z: AES encryption is not supported", entryName = listed.name)
            limits.checkOutputSize(folder.size)
            val bytes = if (cachedFolder == location.folder) cachedBytes!! else {
                folderDecodes++
                decodeFolder(streams, location.folder, limits).also { cachedFolder = location.folder; cachedBytes = it }
            }
            val out = bytes.copyOfRange(location.offset.toInt(), (location.offset + listed.size).toInt())
            if (location.crc != null && Crc32.of(out) != location.crc) {
                throw ArchiveFormatException("7z: file CRC-32 mismatch", entryName = listed.name)
            }
            out
        }
    }

    private fun parse(): Directory {
        val total = source.size
        if (total < SIGNATURE_SIZE) throw ArchiveFormatException("7z: file is too small", 0)
        val start = source.readFully(0, SIGNATURE_SIZE.toInt())
        for (i in SIGNATURE.indices) if (start[i] != SIGNATURE[i]) throw ArchiveFormatException("7z: missing signature", 0)
        if (start[6].toInt() != 0) throw ArchiveUnsupportedException("7z: format version ${start[6]}.${start[7]} is not supported", 6)
        if (Crc32().apply { update(start, 12, 20) }.value() != readU32Le(start, 8)) {
            throw ArchiveFormatException("7z: start header CRC mismatch", 8)
        }
        val nextOffset = readI64Le(start, 12)
        val nextSize = readI64Le(start, 20)
        val nextCrc = readU32Le(start, 28)
        if (nextSize == 0L) return Directory(emptyList<ArchiveEntry>().issuedBy(this, limits), emptyList(), null)
        if (nextOffset < 0 || nextSize < 0 || nextOffset > total - SIGNATURE_SIZE || nextSize > total - SIGNATURE_SIZE - nextOffset) {
            throw ArchiveFormatException("7z: next header lies outside the file", 12)
        }
        var header = source.readFullyChecked(SIGNATURE_SIZE + nextOffset, nextSize)
        if (Crc32.of(header) != nextCrc) throw ArchiveFormatException("7z: header CRC mismatch", SIGNATURE_SIZE + nextOffset)
        // An encoded header is a streams description whose single folder decodes to the real header.
        var depth = 0
        while (header.isNotEmpty() && header[0].toInt() == ENCODED_HEADER) {
            if (++depth > 4) throw ArchiveFormatException("7z: header encoded too many times", SIGNATURE_SIZE + nextOffset)
            val reader = HeaderReader(header, 1)
            val streams = reader.streamsInfo()
            if (streams.folders.isEmpty()) throw ArchiveFormatException("7z: encoded header has no folder", SIGNATURE_SIZE + nextOffset)
            header = decodeFolder(streams, 0, limits)
        }
        val reader = HeaderReader(header, 0)
        if (reader.byte() != HEADER) throw ArchiveFormatException("7z: header does not start with a header record", SIGNATURE_SIZE + nextOffset)
        var id = reader.byte()
        if (id == ARCHIVE_PROPERTIES) { reader.skipArchiveProperties(); id = reader.byte() }
        if (id == ADDITIONAL_STREAMS) { reader.streamsInfo(); id = reader.byte() }
        var streams: Streams? = null
        if (id == MAIN_STREAMS) { streams = reader.streamsInfo(); id = reader.byte() }
        val (entries, locations) = if (id == FILES_INFO) {
            reader.filesInfo(streams).also { reader.expect(END) }
        } else {
            if (id != END) throw ArchiveFormatException("7z: unexpected header property $id")
            Pair(emptyList(), emptyList())
        }
        return Directory(entries.issuedBy(this, limits), locations, streams)
    }

    /** Decode a folder's main output, checking its CRC. */
    private fun decodeFolder(streams: Streams, index: Int, limits: ArchiveLimits): ByteArray {
        val folder = streams.folders[index]
        val packedTotal = folder.packedStreams.indices.sumOf { streams.packSizes[folder.firstPackStream + it] }
        limits.checkOutputSize(folder.size)
        limits.checkRatio(packedTotal, folder.size)
        val out = decodeOutput(streams, folder, folder.mainOutput, limits, 0)
        folder.crc?.let { if (Crc32.of(out) != it) throw ArchiveFormatException("7z: folder CRC-32 mismatch") }
        return out
    }

    private fun decodeOutput(streams: Streams, folder: Folder, output: Int, limits: ArchiveLimits, depth: Int): ByteArray {
        if (depth > folder.coders.size) throw ArchiveFormatException("7z: coder bindings form a cycle")
        var outputBase = 0
        var inputBase = 0
        var coderIndex = 0
        while (output >= outputBase + folder.coders[coderIndex].outputs) {
            outputBase += folder.coders[coderIndex].outputs
            inputBase += folder.coders[coderIndex].inputs
            coderIndex++
        }
        val coder = folder.coders[coderIndex]
        if (coder.inputs != 1 || coder.outputs != 1) throw unsupported(coder.method)
        val input = inputBase
        val bound = folder.bindPairs.firstOrNull { it[0] == input }
        val data = if (bound != null) {
            decodeOutput(streams, folder, bound[1], limits, depth + 1)
        } else {
            val packed = folder.packedStreams.indexOf(input)
            if (packed < 0) throw ArchiveFormatException("7z: coder input is neither bound nor packed")
            val stream = folder.firstPackStream + packed
            source.readFullyChecked(streams.packOffset(stream), streams.packSizes[stream])
        }
        return runCoder(coder, data, folder.unpackSizes[output], limits)
    }

    private fun runCoder(coder: Coder, input: ByteArray, size: Long, limits: ArchiveLimits): ByteArray {
        limits.checkOutputSize(size)
        val out = when (coder.method) {
            COPY -> input
            LZMA -> {
                if (coder.properties.size < 5) throw ArchiveFormatException("7z: LZMA properties are too short")
                val properties = LzmaProperties.of(coder.properties[0].toInt() and 0xFF, 0)
                val dictionary = maxOf(4096L, readI32Le(coder.properties, 1).toLong() and 0xFFFFFFFFL)
                val output = LzOutput(limits, input.size)
                decodeLzma(RangeDecoder(input, 0, input.size), LzmaModel(properties), output, size, dictionary, markerAllowed = true)
                output.take()
            }
            LZMA2 -> {
                if (coder.properties.isEmpty() || coder.properties[0] > 40) throw ArchiveFormatException("7z: invalid LZMA2 properties")
                val output = LzOutput(limits, input.size)
                Lzma2.decode(input, 0, input.size, output, lzma2DictionarySize(coder.properties[0].toInt()))
                output.take()
            }
            DEFLATE -> Inflate.inflateExact(input, limits, size)
            DEFLATE64 -> Inflate.inflateExact(input, limits, size, deflate64 = true)
            BZIP2 -> Bzip2Decoder.decompress(input, limits)
            PPMD -> Ppmd7z.decompress(input, coder.properties, size, limits)
            BCJ_X86 -> input.copyOf().also { Bcj.x86Decode(it) }
            DELTA -> {
                val distance = (coder.properties.firstOrNull()?.toInt()?.and(0xFF) ?: 0) + 1
                val out = input.copyOf()
                for (i in distance until out.size) out[i] = (out[i] + out[i - distance]).toByte()
                out
            }
            else -> throw unsupported(coder.method)
        }
        if (out.size.toLong() != size) throw ArchiveFormatException("7z: coder produced ${out.size} bytes, expected $size")
        return out
    }

    private fun unsupported(method: String): ArchiveUnsupportedException {
        val name = when (method) {
            AES -> "AES encryption"
            "0303011b" -> "BCJ2 filter"
            "03030205", "03030401", "03030501", "03030701", "03030805", "05", "06", "07", "08", "09", "0a", "0b" -> "branch filter $method"
            else -> "method $method"
        }
        return ArchiveUnsupportedException("7z: $name is not supported")
    }

    /** Cursor over a decoded header, with 7z's variable-length numbers. */
    private inner class HeaderReader(private val bytes: ByteArray, private var pos: Int) {
        fun fail(message: String): Nothing = throw ArchiveFormatException("7z: $message (header byte $pos)")

        fun byte(): Int {
            if (pos >= bytes.size) fail("truncated header")
            return bytes[pos++].toInt() and 0xFF
        }

        fun expect(id: Int) { if (byte() != id) fail("expected property $id") }

        fun number(): Long {
            val first = byte()
            var mask = 0x80
            var value = 0L
            for (i in 0 until 8) {
                if (first and mask == 0) return value or ((first and (mask - 1)).toLong() shl (8 * i))
                value = value or (byte().toLong() shl (8 * i))
                mask = mask ushr 1
            }
            return value
        }

        /** A count bounded by the header size, since each item needs at least one byte. */
        fun count(): Int {
            val n = number()
            if (n < 0 || n > bytes.size) fail("count $n exceeds the header")
            return n.toInt()
        }

        fun size(): Long = number().also { if (it < 0) fail("size exceeds Long") }

        fun skip(n: Long) {
            if (n < 0 || n > bytes.size - pos) fail("property runs past the header")
            pos += n.toInt()
        }

        fun bits(n: Int): BooleanArray {
            val out = BooleanArray(n)
            var mask = 0
            var current = 0
            for (i in 0 until n) {
                if (mask == 0) { current = byte(); mask = 0x80 }
                out[i] = current and mask != 0
                mask = mask ushr 1
            }
            return out
        }

        fun definedBits(n: Int): BooleanArray = if (byte() != 0) BooleanArray(n) { true } else bits(n)

        fun u32(): Long {
            if (bytes.size - pos < 4) fail("truncated header")
            return readU32Le(bytes, pos).also { pos += 4 }
        }

        fun u64(): Long {
            if (bytes.size - pos < 8) fail("truncated header")
            return readI64Le(bytes, pos).also { pos += 8 }
        }

        fun digests(n: Int): Array<Long?> {
            val defined = definedBits(n)
            return Array(n) { if (defined[it]) u32() else null }
        }

        fun skipArchiveProperties() {
            while (true) {
                if (byte() == END) return
                skip(size())
            }
        }

        fun streamsInfo(): Streams {
            var packPosition = 0L
            var packSizes = LongArray(0)
            var folders = emptyList<Folder>()
            var id = byte()
            if (id == PACK_INFO) {
                packPosition = size()
                val count = count()
                packSizes = LongArray(count)
                id = byte()
                if (id == SIZE) { for (i in 0 until count) packSizes[i] = size(); id = byte() }
                if (id == CRC) { digests(count); id = byte() }
                if (id != END) fail("unexpected pack info property $id")
                id = byte()
            }
            if (id == UNPACK_INFO) {
                expect(FOLDER)
                val count = count()
                if (byte() != 0) throw ArchiveUnsupportedException("7z: external folder records are not supported")
                folders = List(count) { folder() }
                expect(CODERS_UNPACK_SIZE)
                for (folder in folders) for (i in folder.unpackSizes.indices) folder.unpackSizes[i] = size()
                id = byte()
                if (id == CRC) {
                    val crcs = digests(count)
                    folders.forEachIndexed { i, folder -> folder.crc = crcs[i] }
                    id = byte()
                }
                if (id != END) fail("unexpected unpack info property $id")
                id = byte()
            }
            var packStream = 0
            for (folder in folders) {
                folder.firstPackStream = packStream
                packStream += folder.packedStreams.size
            }
            if (packStream > packSizes.size) fail("folders use more packed streams than exist")
            val unpackStreams = IntArray(folders.size) { 1 }
            var substreamSizes = LongArray(folders.size) { folders[it].size }
            var substreamCrcs = Array(folders.size) { folders[it].crc }
            if (id == SUBSTREAMS_INFO) {
                id = byte()
                if (id == NUM_UNPACK_STREAM) {
                    for (i in folders.indices) unpackStreams[i] = count()
                    id = byte()
                }
                val totalStreams = unpackStreams.sum()
                substreamSizes = LongArray(totalStreams)
                var k = 0
                val hasSizes = id == SIZE
                for (i in folders.indices) {
                    if (unpackStreams[i] == 0) continue
                    var sum = 0L
                    for (j in 0 until unpackStreams[i] - 1) {
                        val s = if (hasSizes) size() else 0L
                        substreamSizes[k++] = s
                        sum += s
                        if (sum < 0 || sum > folders[i].size) fail("substream sizes exceed their folder")
                    }
                    substreamSizes[k++] = folders[i].size - sum
                }
                if (hasSizes) id = byte()
                substreamCrcs = arrayOfNulls(totalStreams)
                var needed = 0
                for (i in folders.indices) if (!(unpackStreams[i] == 1 && folders[i].crc != null)) needed += unpackStreams[i]
                if (id == CRC) {
                    val crcs = digests(needed)
                    var c = 0
                    k = 0
                    for (i in folders.indices) {
                        if (unpackStreams[i] == 1 && folders[i].crc != null) substreamCrcs[k++] = folders[i].crc
                        else repeat(unpackStreams[i]) { substreamCrcs[k++] = crcs[c++] }
                    }
                    id = byte()
                } else {
                    k = 0
                    for (i in folders.indices) {
                        if (unpackStreams[i] == 1) substreamCrcs[k++] = folders[i].crc else k += unpackStreams[i]
                    }
                }
                if (id != END) fail("unexpected substreams property $id")
                id = byte()
            }
            if (id != END) fail("unexpected streams property $id")
            return Streams(packPosition, packSizes, folders, unpackStreams, substreamSizes, substreamCrcs)
        }

        private fun folder(): Folder {
            val coderCount = count()
            if (coderCount !in 1..64) fail("folder has $coderCount coders")
            val coders = List(coderCount) {
                val flags = byte()
                if (flags and 0x80 != 0) throw ArchiveUnsupportedException("7z: alternative coder methods are not supported")
                val idSize = flags and 0x0F
                if (bytes.size - pos < idSize) fail("truncated coder id")
                val method = (0 until idSize).joinToString("") { (bytes[pos + it].toInt() and 0xFF).toString(16).padStart(2, '0') }
                pos += idSize
                val inputs: Int
                val outputs: Int
                if (flags and 0x10 != 0) { inputs = count(); outputs = count() } else { inputs = 1; outputs = 1 }
                if (inputs !in 1..64 || outputs !in 1..64) fail("coder has $inputs inputs and $outputs outputs")
                val properties = if (flags and 0x20 != 0) {
                    val n = size()
                    skip(n)
                    bytes.copyOfRange(pos - n.toInt(), pos)
                } else ByteArray(0)
                Coder(method, inputs, outputs, properties)
            }
            val totalOutputs = coders.sumOf { it.outputs }
            val totalInputs = coders.sumOf { it.inputs }
            val bindPairs = List(totalOutputs - 1) {
                val input = count(); val output = count()
                if (input >= totalInputs || output >= totalOutputs) fail("bind pair out of range")
                intArrayOf(input, output)
            }
            val packedCount = totalInputs - bindPairs.size
            if (packedCount < 1) fail("folder has no packed stream")
            val packed = if (packedCount == 1) {
                intArrayOf((0 until totalInputs).first { input -> bindPairs.none { it[0] == input } })
            } else IntArray(packedCount) { count().also { if (it >= totalInputs) fail("packed stream index out of range") } }
            if (bindPairs.map { it[1] }.toSet().size != bindPairs.size) fail("duplicate bind pair output")
            return Folder(coders, bindPairs, packed, LongArray(totalOutputs))
        }

        fun filesInfo(streams: Streams?): Pair<List<ArchiveEntry>, List<Location?>> {
            val count = count()
            limits.checkEntryCount(count)
            var emptyStream = BooleanArray(count)
            var emptyFile = BooleanArray(0)
            var anti = BooleanArray(0)
            var names: List<Pair<String, ByteArray>>? = null
            var modified: Array<Long?> = arrayOfNulls(count)
            var attributes: Array<Long?> = arrayOfNulls(count)
            while (true) {
                val type = byte()
                if (type == END) break
                val size = size()
                val end = pos + size
                if (size > bytes.size - pos) fail("file property runs past the header")
                when (type) {
                    EMPTY_STREAM -> emptyStream = bits(count)
                    EMPTY_FILE -> emptyFile = bits(emptyStream.count { it })
                    ANTI -> anti = bits(emptyStream.count { it })
                    NAME -> {
                        if (byte() != 0) throw ArchiveUnsupportedException("7z: external names are not supported")
                        names = List(count) {
                            val start = pos
                            while (true) {
                                if (end - pos < 2) fail("unterminated file name")
                                if (bytes[pos].toInt() == 0 && bytes[pos + 1].toInt() == 0) break
                                pos += 2
                            }
                            val raw = bytes.copyOfRange(start, pos)
                            pos += 2
                            CharArray(raw.size / 2) { i -> ((raw[2 * i].toInt() and 0xFF) or (raw[2 * i + 1].toInt() and 0xFF shl 8)).toChar() }
                                .concatToString() to raw
                        }
                    }
                    MODIFIED_TIME -> {
                        val defined = definedBits(count)
                        if (byte() != 0) throw ArchiveUnsupportedException("7z: external times are not supported")
                        modified = Array(count) { if (defined[it]) fileTimeToEpochSeconds(u64()) else null }
                    }
                    ATTRIBUTES -> {
                        val defined = definedBits(count)
                        if (byte() != 0) throw ArchiveUnsupportedException("7z: external attributes are not supported")
                        attributes = Array(count) { if (defined[it]) u32() else null }
                    }
                    else -> {}
                }
                pos = end.toInt()
            }
            val entries = ArrayList<ArchiveEntry>(count)
            val locations = ArrayList<Location?>(count)
            var emptyIndex = 0
            var folderIndex = 0
            var inFolder = 0
            var offset = 0L
            var substream = 0
            for (i in 0 until count) {
                val (name, raw) = names?.get(i) ?: fail("files have no names")
                val attribute = attributes[i]
                val hasStream = !emptyStream[i]
                var location: Location? = null
                var size = 0L
                var packedShare = 0L
                var codec = CodecId.STORE
                var encrypted = false
                val isDirectory: Boolean
                if (hasStream) {
                    if (streams == null) fail("file has data but the archive has no streams")
                    while (folderIndex < streams.folders.size && inFolder >= streams.unpackStreams[folderIndex]) {
                        folderIndex++; inFolder = 0; offset = 0
                    }
                    if (folderIndex >= streams.folders.size || substream >= streams.substreamSizes.size) fail("more files than streams")
                    val folder = streams.folders[folderIndex]
                    size = streams.substreamSizes[substream]
                    location = Location(folderIndex, offset, streams.substreamCrcs[substream])
                    val packed = folder.packedStreams.indices.sumOf { streams.packSizes[folder.firstPackStream + it] }
                    packedShare = if (folder.size == 0L) 0 else (packed.toDouble() * size / folder.size).toLong()
                    codec = codecOf(folder)
                    encrypted = folder.coders.any { it.method == AES }
                    offset += size
                    inFolder++
                    substream++
                    isDirectory = attribute?.let { it and 0x10L != 0L } ?: false
                } else {
                    val emptyIsFile = emptyFile.getOrElse(emptyIndex) { false }
                    val isAnti = anti.getOrElse(emptyIndex) { false }
                    emptyIndex++
                    if (isAnti) continue
                    isDirectory = !emptyIsFile || attribute?.let { it and 0x10L != 0L } == true
                }
                if (offset > Int.MAX_VALUE) fail("folder exceeds the ByteArray range")
                val mode = attribute?.takeIf { it and 0x8000L != 0L }?.let { (it ushr 16).toInt() }
                entries.add(ArchiveEntry(name, size, isDirectory) {
                    lastModifiedEpochSeconds = modified[i]
                    compressedSize = packedShare
                    this.codec = codec
                    isEncrypted = encrypted
                    crc32 = location?.crc
                    unixMode = mode
                }.withNameBytes(raw))
                locations.add(location)
            }
            return entries to locations
        }
    }

    /** The folder's compression method, ignoring encryption and filters such as BCJ and delta. */
    private fun codecOf(folder: Folder): CodecId {
        val methods = folder.coders.map { it.method }
        return when {
            LZMA2 in methods -> CodecId.LZMA2
            LZMA in methods -> CodecId.LZMA
            BZIP2 in methods -> CodecId.BZIP2
            DEFLATE64 in methods -> CodecId.DEFLATE64
            DEFLATE in methods -> CodecId.DEFLATE
            PPMD in methods -> CodecId.PPMD
            methods.all { it == COPY || it == AES || it == DELTA || it == BCJ_X86 } -> CodecId.STORE
            else -> CodecId("7z-${methods.first()}")
        }
    }

    /** Holds the reader's internal format constants; exposes no public operations. */
    public companion object {
        private val SIGNATURE = byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        private const val SIGNATURE_SIZE = 32L

        private const val END = 0x00
        private const val HEADER = 0x01
        private const val ARCHIVE_PROPERTIES = 0x02
        private const val ADDITIONAL_STREAMS = 0x03
        private const val MAIN_STREAMS = 0x04
        private const val FILES_INFO = 0x05
        private const val PACK_INFO = 0x06
        private const val UNPACK_INFO = 0x07
        private const val SUBSTREAMS_INFO = 0x08
        private const val SIZE = 0x09
        private const val CRC = 0x0A
        private const val FOLDER = 0x0B
        private const val CODERS_UNPACK_SIZE = 0x0C
        private const val NUM_UNPACK_STREAM = 0x0D
        private const val EMPTY_STREAM = 0x0E
        private const val EMPTY_FILE = 0x0F
        private const val ANTI = 0x10
        private const val NAME = 0x11
        private const val MODIFIED_TIME = 0x14
        private const val ATTRIBUTES = 0x15
        private const val ENCODED_HEADER = 0x17

        private const val COPY = "00"
        private const val DELTA = "03"
        private const val LZMA = "030101"
        private const val LZMA2 = "21"
        private const val DEFLATE = "040108"
        private const val DEFLATE64 = "040109"
        private const val BZIP2 = "040202"
        private const val PPMD = "030401"
        private const val AES = "06f10701"
        private const val BCJ_X86 = "03030103"

        /** Windows FILETIME (100 ns ticks since 1601) to Unix seconds, rounded down. */
        private fun fileTimeToEpochSeconds(fileTime: Long): Long =
            Math.floorDiv(fileTime, 10_000_000L) - 11_644_473_600L
    }
}

private object Math {
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q
    }
}
