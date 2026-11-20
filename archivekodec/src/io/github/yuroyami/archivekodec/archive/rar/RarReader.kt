package io.github.yuroyami.archivekodec.archive.rar

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveTime
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
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.rar.Rar29Unpacker
import io.github.yuroyami.archivekodec.codec.rar.Rar5Unpacker
import io.github.yuroyami.archivekodec.codec.rar.RarDecoder
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.DosTime
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le

/**
 * RAR reader for both container versions: RAR 1.5 to 4.x ("RAR4", signature
 * `Rar!\x1A\x07\x00`) and RAR 5.0 and later ("RAR5", `Rar!\x1A\x07\x01\x00`).
 *
 * It lists every file and directory, with sizes, modification times, Unix modes for
 * archives made on Unix, and encryption. Stored members, RAR 2.9 compressed members (what RAR
 * 3.x and 4.x write: LZ77 and PPMd blocks, and filters, run natively or in RAR's virtual
 * machine) and RAR5 compressed members (including the delta, x86 and ARM filters) are read and
 * checked against their CRC-32, solid archives too; a solid member decodes the members before
 * it, and reading in listing order decodes each once. RAR 1.5 and 2.0 compression, RAR 7
 * compression with dictionaries over 4 GB,
 * encrypted members and members split across volumes are listed but refused on read with an
 * [ArchiveUnsupportedException] that names the reason, as are archives whose headers are
 * encrypted. The RAR5 window is refused above [ArchiveLimits.maxDecoderMemory]. RAR5 symbolic links read as their target text, and
 * hard links and file copies read as the member they point to. RAR4 names use `/` for the
 * stored `\` separator; RAR4 names without a Unicode form are
 * decoded with [nameDecoder] (UTF-8 by default). DOS times are read as UTC.
 *
 * Owns the supplied source; close this reader to release it, including after a failed
 * operation. This reader is not thread-safe.
 */
public class RarReader(
    source: RandomAccessSource,
    private val nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
) : ArchiveReader {
    public constructor(source: RandomAccessSource, limits: ArchiveLimits) : this(source, ArchiveNameDecoder.UTF8, limits)
    public constructor(source: RandomAccessSource) : this(source, ArchiveLimits.DEFAULT)

    /** Where and how a listed member is stored, or why it cannot be read. */
    private class Item(
        val dataOffset: Long, val packed: Long, val crc: Long?,
        val refusal: String? = null, val target: ByteArray? = null, val linkTo: String? = null,
        /** RAR5 compression information when the member is compressed, else -1; for RAR4, only the solid flag. */
        val compression: Long = -1, val size: Long = 0,
        /** Whether the member is RAR 2.9 compressed, which RAR 3.x and 4.x write. */
        val rar29: Boolean = false,
    )

    private class Directory(val entries: List<ArchiveEntry>, val items: List<Item>)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    override val format: ArchiveFormat = ArchiveFormat.RAR

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "RarReader(source=$ownedSource)"

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close() {
        unpacker = null
        ownedSource.close()
    }

    /** The solid stream decoded so far: its first member and the last member fed to it. */
    private var unpacker: RarDecoder? = null
    private var unpackerStart = -1
    private var unpackerLast = -1
    private var unpackerWindow = 0L

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "RAR")
        var item = directory.items[listed.slotIn(this)]
        return withEntryContext(listed.name, listed.dataOffset) {
            limits.checkOutputSize(listed.size)
            if (item.linkTo != null) {
                val index = directory.entries.indices.firstOrNull { directory.entries[it].name == item.linkTo && directory.items[it].linkTo == null } ?: -1
                if (index < 0) throw ArchiveFormatException("RAR: link target '${item.linkTo}' is not in the archive")
                item = directory.items[index]
                // A link produces its target's data, so the limits apply to the target's size.
                limits.checkOutputSize(item.size)
            }
            when {
                item.target != null -> item.target!!.copyOf()
                item.refusal != null -> throw ArchiveUnsupportedException("RAR: ${item.refusal}", item.dataOffset)
                listed.isDirectory -> ByteArray(0)
                item.compression >= 0 -> {
                    // A member without data has no bytes to measure against: count one.
                    limits.checkRatio(maxOf(item.packed, 1L), item.size)
                    val data = unpack(directory.items, directory.items.indexOf(item), limits)
                    if (item.crc != null && Crc32.of(data) != item.crc) throw ArchiveFormatException("RAR: CRC-32 mismatch", item.dataOffset)
                    data
                }
                else -> {
                    limits.checkOutputSize(item.packed)
                    limits.checkRatio(item.packed, item.packed)
                    val data = source.readFullyChecked(item.dataOffset, item.packed)
                    if (item.crc != null && Crc32.of(data) != item.crc) throw ArchiveFormatException("RAR: CRC-32 mismatch", item.dataOffset)
                    data
                }
            }
        }
    }

    private fun fail(message: String, offset: Long? = null): Nothing = throw ArchiveFormatException("RAR: $message", offset)

    /**
     * Decode compressed RAR5 member [index]. A solid member needs every compressed member
     * before it back to the start of its stream, so the unpacker is kept: reading members in
     * order decodes each once.
     */
    private fun unpack(items: List<Item>, index: Int, limits: ArchiveLimits): ByteArray {
        fun solid(i: Int) = items[i].compression and SOLID_FLAG != 0L
        var start = index
        if (solid(index)) {
            var j = index - 1
            while (j >= 0) {
                if (items[j].compression >= 0) {
                    start = j
                    if (!solid(j)) break
                } else if (items[j].refusal != null && items[j].packed > 0) {
                    throw ArchiveUnsupportedException("RAR: this solid member follows one that cannot be decoded: ${items[j].refusal}", items[index].dataOffset)
                }
                j--
            }
        }
        val current = unpacker
        // A kept window is reused only while this call's limits would allow it too.
        val resume = current != null && unpackerStart == start && unpackerLast < index && unpackerWindow <= limits.maxDecoderMemory
        val target = items[index]
        limits.checkOutputSize(target.size)
        if (target.size > Int.MAX_VALUE) throw ArchiveFormatException("RAR: member is too large for a ByteArray", target.dataOffset)
        val worker = if (resume) current!! else if (items[start].rar29) {
            // RAR 2.9 always uses a 4 MiB window; its PPMd model is checked when a block starts one.
            if (RAR29_WINDOW > limits.maxDecoderMemory) {
                throw DecompressionBombException("RAR: a $RAR29_WINDOW B window exceeds the decoder memory limit ${limits.maxDecoderMemory} B")
            }
            unpackerWindow = RAR29_WINDOW
            Rar29Unpacker(limits.maxDecoderMemory)
        } else {
            var total = 0L
            var j = start
            while (j < items.size && (j == start || items[j].compression < 0 || solid(j))) {
                if (items[j].compression >= 0) total += items[j].size
                j++
            }
            val dictionary = DICTIONARY_UNIT shl ((items[start].compression ushr 10) and 15).toInt()
            val needed = maxOf(1L, minOf(dictionary, total))
            var window = 1L
            while (window < needed) window = window shl 1
            if (window > limits.maxDecoderMemory || window > MAX_WINDOW) {
                throw DecompressionBombException(
                    "RAR: a $window B window exceeds the decoder memory limit ${minOf(limits.maxDecoderMemory, MAX_WINDOW)} B")
            }
            Rar5Unpacker(window.toInt(), dictionary).also { unpackerWindow = window }
        }
        unpacker = worker
        unpackerStart = start
        var result: ByteArray? = null
        val first = if (resume) unpackerLast + 1 else start
        // The window holds the members up to unpackerLast only once each decode returned. Any
        // failure part way leaves it out of step with the stream, so it is dropped.
        unpackerLast = first - 1
        try {
            for (j in first..index) {
                val item = items[j]
                if (item.compression < 0) continue
                if (item.size > Int.MAX_VALUE || item.packed > Int.MAX_VALUE) throw ArchiveFormatException("RAR: member is too large for a ByteArray", item.dataOffset)
                // An empty RAR 2.9 member has no data and leaves the stream untouched, as RAR writes it.
                if (item.rar29 && item.size == 0L) {
                    if (j == index) result = ByteArray(0)
                    unpackerLast = j
                    continue
                }
                val data = source.readFullyChecked(item.dataOffset, item.packed)
                result = worker.unpack(data, 0, data.size, item.size.toInt(), keep = j == index)
                unpackerLast = j
            }
        } catch (error: Throwable) {
            unpacker = null
            unpackerStart = -1
            unpackerLast = -1
            throw error
        }
        return result ?: throw ArchiveFormatException("RAR: member has no compressed data", items[index].dataOffset)
    }

    private fun parse(): Directory {
        val start = source.readFully(0, minOf(source.size, 8L).toInt())
        val entries = ArrayList<ArchiveEntry>()
        val items = ArrayList<Item>()
        when {
            start.size >= 8 && start.copyOf(8).contentEquals(RAR5_SIGNATURE) -> Rar5(entries, items).parse()
            start.size >= 7 && start.copyOf(7).contentEquals(RAR4_SIGNATURE) -> Rar4(entries, items).parse()
            else -> fail("missing Rar! signature", 0)
        }
        return Directory(entries.issuedBy(this, limits), items)
    }

    private fun add(entries: MutableList<ArchiveEntry>, items: MutableList<Item>, entry: ArchiveEntry, item: Item) {
        limits.checkEntryCount(entries.size + 1)
        entries.add(entry)
        items.add(item)
    }

    // ---- RAR 1.5 to 4.x ----

    private inner class Rar4(private val entries: MutableList<ArchiveEntry>, private val items: MutableList<Item>) {
        fun parse() {
            var position = RAR4_SIGNATURE.size.toLong()
            val size = source.size
            while (position + 7 <= size) {
                val base = source.readFully(position, 7)
                val crc = readU16Le(base, 0)
                val type = base[2].toInt() and 0xFF
                val flags = readU16Le(base, 3)
                val headerSize = readU16Le(base, 5)
                if (headerSize < 7 || position + headerSize > size) fail("block header at $position has size $headerSize", position)
                val header = source.readFully(position, headerSize)
                if (type in CHECKED_BLOCKS && (Crc32().apply { update(header, 2, headerSize - 2) }.value() and 0xFFFF) != crc.toLong()) {
                    fail("block header CRC mismatch", position)
                }
                var dataSize = if (flags and LONG_BLOCK != 0 && headerSize >= 11) readU32Le(header, 7) else 0L
                when (type) {
                    MAIN_HEADER -> if (flags and MAIN_ENCRYPTED_HEADERS != 0) {
                        throw ArchiveUnsupportedException("RAR: the headers are encrypted", position)
                    }
                    FILE_HEADER -> dataSize = file(header, flags, position + headerSize)
                    END_HEADER -> return
                }
                position += headerSize + dataSize
            }
        }

        /** Read one file header; return its data size. */
        private fun file(h: ByteArray, flags: Int, dataOffset: Long): Long {
            if (h.size < 32) fail("file header is too short", dataOffset - h.size)
            val large = flags and LARGE != 0
            var packed = readU32Le(h, 7)
            var unpacked = readU32Le(h, 11)
            val host = h[15].toInt() and 0xFF
            val crc = readU32Le(h, 16)
            val time = readU32Le(h, 20).toInt()
            val method = h[25].toInt() and 0xFF
            val nameSize = readU16Le(h, 26)
            val attributes = readU32Le(h, 28).toInt()
            var at = 32
            if (large) {
                if (h.size < 40) fail("file header is too short for its 64-bit sizes", dataOffset - h.size)
                packed = packed or (readU32Le(h, 32) shl 32)
                unpacked = unpacked or (readU32Le(h, 36) shl 32)
                at = 40
            }
            if (packed < 0 || unpacked < 0) fail("file sizes exceed the supported range", dataOffset - h.size)
            if (at + nameSize > h.size) fail("file name runs past its header", dataOffset - h.size)
            val raw = h.copyOfRange(at, at + nameSize)
            at += nameSize
            if (flags and SALT != 0) at += 8
            var modified = DosTime.toEpochSeconds(time ushr 16, time and 0xFFFF)
            if (flags and EXT_TIME != 0 && at + 2 <= h.size && modified != null) {
                val timeFlags = readU16Le(h, at)
                // Only the modification time's extra precision is kept: its last bit adds a second.
                if ((timeFlags ushr 12) and 8 != 0 && (timeFlags ushr 12) and 4 != 0) modified += 1
            }
            // RAR4 separates directories with '\\' whatever system made the archive.
            val name = name(raw, flags, dataOffset - h.size).replace('\\', '/')
            val isDirectory = flags and DIRECTORY_MASK == DIRECTORY_MASK
            val unix = host == HOST_UNIX || host == HOST_BEOS
            val refusal = when {
                flags and (SPLIT_BEFORE or SPLIT_AFTER) != 0 -> "the member continues in another volume; multi-volume archives are not supported"
                flags and ENCRYPTED != 0 -> "the member is encrypted; RAR encryption is not supported"
                !isDirectory && method != METHOD_STORE && h[24].toInt() != UNPACK_29 && h[24].toInt() != UNPACK_36 ->
                    "compression method ${method - METHOD_STORE} (${METHOD_NAMES.getOrElse(method - METHOD_STORE) { "unknown" }}) " +
                        "of RAR ${h[24] / 10}.${h[24] % 10} is not supported; RAR 2.9 compression and stored members are read"
                !isDirectory && method != METHOD_STORE && method > METHOD_STORE + 5 -> "unknown compression method $method"
                !isDirectory && method == METHOD_STORE && packed != unpacked -> "a stored member holds $packed bytes for $unpacked"
                else -> null
            }
            val entry = ArchiveEntry(if (isDirectory) "$name/" else name, if (isDirectory) 0 else unpacked, isDirectory) {
                lastModifiedEpochSeconds = modified
                compressedSize = if (isDirectory) 0 else packed
                codec = if (method == METHOD_STORE || isDirectory) CodecId.STORE else CodecId("rar")
                isEncrypted = flags and ENCRYPTED != 0
                unixMode = if (unix) attributes and 0xFFFF else null
                // MS-DOS, OS/2 and Win32 hosts store Windows attributes.
                windowsAttributes = if (host == HOST_MSDOS || host == HOST_OS2 || host == HOST_WIN32) attributes else null
            }.withNameBytes(raw).locatedAt(dataOffset)
            val compressed = !isDirectory && method != METHOD_STORE && refusal == null
            add(entries, items, entry, Item(
                dataOffset, packed, crc, refusal,
                compression = if (compressed) (if (flags and SOLID4 != 0) SOLID_FLAG else 0L) else -1,
                size = unpacked, rar29 = compressed,
            ))
            return packed
        }

        /** A name, in its Unicode form when the header has one. */
        private fun name(raw: ByteArray, flags: Int, offset: Long): String {
            if (flags and UNICODE_NAME == 0) return nameDecoder.decodeStoredName(raw, offset)
            val zero = raw.indexOf(0)
            if (zero < 0) return ArchiveNameDecoder.UTF8.decodeStoredName(raw, offset)
            return decodeUnicode(raw, zero) ?: fail("malformed Unicode file name", offset)
        }
    }

    // ---- RAR 5.0 and later ----

    private inner class Rar5(private val entries: MutableList<ArchiveEntry>, private val items: MutableList<Item>) {
        private lateinit var h: ByteArray
        private var p = 0

        private fun vint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                if (p >= h.size) fail("header field runs past its header")
                val b = h[p++].toInt() and 0xFF
                if (shift > 63) fail("variable-length number is too long")
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        private fun u32(): Long {
            if (p + 4 > h.size) fail("header field runs past its header")
            return readU32Le(h, p).also { p += 4 }
        }

        fun parse() {
            var position = RAR5_SIGNATURE.size.toLong()
            val size = source.size
            while (position < size) {
                // CRC-32, then the header size as a variable-length number, then the header.
                val head = source.readFully(position, minOf(size - position, 7L).toInt())
                h = head; p = 4
                if (head.size < 5) fail("block header at $position is truncated", position)
                val headerSize = vint()
                val sizeBytes = p - 4
                if (headerSize < 2 || headerSize > MAX_HEADER || position + 4 + sizeBytes + headerSize > size) {
                    fail("block header at $position has size $headerSize", position)
                }
                val block = source.readFully(position, (4 + sizeBytes + headerSize).toInt())
                if (Crc32().apply { update(block, 4, block.size - 4) }.value() != readU32Le(block, 0)) fail("block header CRC mismatch", position)
                h = block; p = 4 + sizeBytes
                val type = vint().toInt()
                val flags = vint().toInt()
                val extraSize = if (flags and HAS_EXTRA != 0) vint() else 0
                val dataSize = if (flags and HAS_DATA != 0) vint() else 0
                val end = position + block.size
                if (dataSize < 0 || dataSize > size - end) fail("block data at $end runs past the end of the archive", position)
                when (type) {
                    TYPE_FILE -> file(flags, extraSize, end, dataSize)
                    TYPE_ENCRYPTION -> throw ArchiveUnsupportedException("RAR: the headers are encrypted", position)
                    TYPE_END -> return
                }
                position = end + dataSize
            }
        }

        private fun file(flags: Int, extraSize: Long, dataOffset: Long, dataSize: Long) {
            val headerStart = dataOffset - h.size
            val fileFlags = vint().toInt()
            var unpacked = vint()
            val attributes = vint()
            var modified = if (fileFlags and FILE_HAS_TIME != 0) ArchiveTime(u32()) else null
            var created: ArchiveTime? = null
            var accessed: ArchiveTime? = null
            val crc = if (fileFlags and FILE_HAS_CRC != 0) u32() else null
            val compression = vint()
            val host = vint().toInt()
            val nameLength = vint()
            if (nameLength > h.size - p) fail("file name runs past its header", headerStart)
            val raw = h.copyOfRange(p, p + nameLength.toInt())
            p += nameLength.toInt()
            val name = ArchiveNameDecoder.UTF8.decodeStoredName(raw, headerStart)

            var encrypted = false
            var target: ByteArray? = null
            var linkTo: String? = null
            if (extraSize > 0) {
                if (extraSize > h.size) fail("extra area is larger than its header", headerStart)
                var at = h.size - extraSize.toInt()
                while (at < h.size) {
                    p = at
                    val recordSize = vint()
                    val recordStart = p
                    if (recordSize > h.size - recordStart) fail("extra record runs past its header", headerStart)
                    val recordEnd = recordStart + recordSize.toInt()
                    when (vint().toInt()) {
                        EXTRA_ENCRYPTION -> encrypted = true
                        EXTRA_TIME -> {
                            // Modified, created and accessed, each when flagged, as Unix seconds or FILETIME;
                            // Unix times may be followed by their nanoseconds, in the same order.
                            val timeFlags = vint().toInt()
                            val unixTime = timeFlags and TIME_UNIX != 0
                            val present = listOf(TIME_MODIFIED, TIME_CREATED, TIME_ACCESSED).filter { timeFlags and it != 0 }
                            if (recordEnd - p < present.size * (if (unixTime) 4 else 8)) fail("time record runs past its record", headerStart)
                            val times = present.map { if (unixTime) ArchiveTime(u32()) else ArchiveTime.fromFileTime(u32() or (u32() shl 32)) }.toMutableList()
                            if (unixTime && timeFlags and TIME_UNIX_NANOS != 0 && recordEnd - p >= present.size * 4) {
                                for (i in times.indices) {
                                    val nanos = u32()
                                    if (nanos < 1_000_000_000L) times[i] = ArchiveTime(times[i].epochSeconds, nanos.toInt())
                                }
                            }
                            for ((i, flag) in present.withIndex()) when (flag) {
                                TIME_MODIFIED -> modified = times[i]
                                TIME_CREATED -> created = times[i]
                                else -> accessed = times[i]
                            }
                        }
                        EXTRA_REDIRECTION -> {
                            val kind = vint().toInt()
                            vint() // flags
                            val length = vint()
                            if (length > recordEnd - p) fail("link target runs past its record", headerStart)
                            val bytes = h.copyOfRange(p, p + length.toInt())
                            when (kind) {
                                REDIRECT_UNIX_SYMLINK, REDIRECT_WINDOWS_SYMLINK, REDIRECT_JUNCTION -> target = bytes
                                REDIRECT_HARD_LINK, REDIRECT_FILE_COPY -> linkTo = ArchiveNameDecoder.UTF8.decodeStoredName(bytes, headerStart)
                            }
                        }
                    }
                    at = recordEnd
                }
            }
            val isDirectory = fileFlags and FILE_DIRECTORY != 0
            val method = ((compression ushr 7) and 7).toInt()
            if (fileFlags and FILE_UNKNOWN_SIZE != 0) unpacked = if (method == 0) dataSize else 0
            if (target != null) unpacked = target.size.toLong()
            val refusal = when {
                flags and (SPLIT_BEFORE5 or SPLIT_AFTER5) != 0 -> "the member continues in another volume; multi-volume archives are not supported"
                encrypted -> "the member is encrypted; RAR encryption is not supported"
                isDirectory || target != null || linkTo != null -> null
                method != 0 && compression and 0x3F != 0L -> "RAR 7 compression (algorithm version ${compression and 0x3F}) is not supported"
                method != 0 -> null
                dataSize != unpacked -> "a stored member holds $dataSize bytes for $unpacked"
                else -> null
            }
            val entry = ArchiveEntry(if (isDirectory) "$name/" else name, if (isDirectory) 0 else unpacked, isDirectory) {
                lastModified = modified
                this.created = created
                lastAccessed = accessed
                windowsAttributes = if (host == HOST5_WINDOWS) attributes.toInt() else null
                compressedSize = if (isDirectory) 0 else dataSize
                codec = if (method == 0 || isDirectory) CodecId.STORE else CodecId("rar5")
                isEncrypted = encrypted
                unixMode = if (host == HOST5_UNIX) attributes.toInt() and 0xFFFF else null
                isSymbolicLink = target != null
                isHardLink = linkTo != null
                linkTarget = target?.decodeToString() ?: linkTo
            }.withNameBytes(raw).locatedAt(dataOffset)
            val compressed = refusal == null && method != 0 && !isDirectory && target == null && linkTo == null
            add(entries, items, entry, Item(dataOffset, dataSize, crc, refusal, target, linkTo, if (compressed) compression else -1, unpacked))
        }
    }

    private companion object {
        val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

        // RAR4 blocks and flags
        const val MAIN_HEADER = 0x73
        const val FILE_HEADER = 0x74
        const val SERVICE_HEADER = 0x7A
        const val END_HEADER = 0x7B
        val CHECKED_BLOCKS = setOf(MAIN_HEADER, FILE_HEADER, SERVICE_HEADER)
        const val LONG_BLOCK = 0x8000
        const val MAIN_ENCRYPTED_HEADERS = 0x0080
        const val SPLIT_BEFORE = 0x0001
        const val SPLIT_AFTER = 0x0002
        const val ENCRYPTED = 0x0004
        const val DIRECTORY_MASK = 0x00E0
        const val LARGE = 0x0100
        const val UNICODE_NAME = 0x0200
        const val SALT = 0x0400
        const val EXT_TIME = 0x1000
        const val METHOD_STORE = 0x30
        const val SOLID4 = 0x0010
        const val RAR29_WINDOW = 0x400000L
        const val UNPACK_29 = 29
        const val UNPACK_36 = 36
        val METHOD_NAMES = listOf("store", "fastest", "fast", "normal", "good", "best")
        const val HOST_MSDOS = 0
        const val HOST_OS2 = 1
        const val HOST_WIN32 = 2
        const val HOST_UNIX = 3
        const val HOST_BEOS = 5

        // RAR5 blocks and flags
        const val MAX_HEADER = 2L * 1024 * 1024
        const val TYPE_FILE = 2
        const val TYPE_ENCRYPTION = 4
        const val TYPE_END = 5
        const val HAS_EXTRA = 0x0001
        const val HAS_DATA = 0x0002
        const val SPLIT_BEFORE5 = 0x0008
        const val SPLIT_AFTER5 = 0x0010
        const val FILE_DIRECTORY = 0x0001
        const val FILE_HAS_TIME = 0x0002
        const val FILE_HAS_CRC = 0x0004
        const val FILE_UNKNOWN_SIZE = 0x0008
        const val HOST5_WINDOWS = 0
        const val HOST5_UNIX = 1
        const val EXTRA_ENCRYPTION = 1
        const val EXTRA_TIME = 3
        const val EXTRA_REDIRECTION = 5
        const val TIME_UNIX = 0x0001
        const val TIME_MODIFIED = 0x0002
        const val TIME_CREATED = 0x0004
        const val TIME_ACCESSED = 0x0008
        const val TIME_UNIX_NANOS = 0x0010
        const val REDIRECT_UNIX_SYMLINK = 1
        const val REDIRECT_WINDOWS_SYMLINK = 2
        const val REDIRECT_JUNCTION = 3
        const val REDIRECT_HARD_LINK = 4
        const val REDIRECT_FILE_COPY = 5
        const val SOLID_FLAG = 0x40L
        const val DICTIONARY_UNIT = 128L * 1024
        const val MAX_WINDOW = 1L shl 30

        /**
         * RAR4's compact Unicode name, after the NUL that ends the 8-bit name: a high byte for
         * the name, then two-bit opcodes that take a low byte, a low byte with that high byte,
         * a whole character, or a run copied from the 8-bit name with an optional correction.
         */
        fun decodeUnicode(raw: ByteArray, zero: Int): String? {
            val ansi = raw.copyOf(zero)
            var p = zero + 1
            if (p >= raw.size) return null
            fun next(): Int? = if (p < raw.size) raw[p++].toInt() and 0xFF else null
            val high = next() ?: return null
            val out = StringBuilder()
            var flags = 0
            var flagBits = 0
            while (p < raw.size) {
                if (flagBits == 0) {
                    flags = next() ?: break
                    flagBits = 8
                }
                flagBits -= 2
                when ((flags ushr flagBits) and 3) {
                    0 -> out.append((next() ?: return null).toChar())
                    1 -> out.append(((high shl 8) or (next() ?: return null)).toChar())
                    2 -> {
                        val low = next() ?: return null
                        out.append((((next() ?: return null) shl 8) or low).toChar())
                    }
                    else -> {
                        var length = next() ?: return null
                        if (length and 0x80 != 0) {
                            val correction = next() ?: return null
                            length = (length and 0x7F) + 2
                            repeat(length) {
                                val i = out.length
                                if (i >= ansi.size) return null
                                out.append(((high shl 8) or (((ansi[i].toInt() and 0xFF) + correction) and 0xFF)).toChar())
                            }
                        } else {
                            repeat(length + 2) {
                                val i = out.length
                                if (i >= ansi.size) return null
                                out.append((ansi[i].toInt() and 0xFF).toChar())
                            }
                        }
                    }
                }
            }
            return out.toString()
        }
    }
}
