package io.github.yuroyami.archivekodec.archive.wim

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
import io.github.yuroyami.archivekodec.checksum.Sha1
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.lzx.Lzx
import io.github.yuroyami.archivekodec.codec.xpress.XpressHuffman
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le

/**
 * Windows Imaging (`.wim`) reader. File data lives in resources found by their SHA-1, so
 * identical files share one resource; each image's directory tree is a metadata resource.
 * Resources are stored whole or in independently compressed chunks of XPRESS Huffman or LZX,
 * as the header says. Everything read is checked against its SHA-1.
 *
 * A WIM with one image lists its files by path; with several, each image's files sit under
 * its 1-based index (`1/Windows/...`, `2/...`), with a directory entry per image. Times are
 * the last-write times. Only unnamed data streams are listed: alternate data streams are
 * skipped, and a reparse point (such as a symbolic link) lists its unnamed data, usually
 * empty. Solid resources (LZMS, `.esd`), LZMS compression and resources in other parts of a
 * split WIM are refused on read. [limits] caps the listing count and supplies the default
 * policy for each read.
 *
 * Owns the supplied source; close this reader to release it, including after a failed
 * operation. This reader is not thread-safe.
 */
public class WimReader(
    source: RandomAccessSource,
    override val limits: ArchiveLimits,
) : ArchiveReader {
    public constructor(source: RandomAccessSource) : this(source, ArchiveLimits.DEFAULT)

    /** A stored resource: where it is, how big it is stored and expanded, and how. */
    private class Resource(val offset: Long, val stored: Long, val size: Long, val flags: Int, val part: Int, val hash: ByteArray)

    /** A listed file's data: its resource (null when empty) and its expected hash. */
    private class Item(val resource: Resource?, val hash: ByteArray?)

    private class Directory(val entries: List<ArchiveEntry>, val items: List<Item>)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    override val format: ArchiveFormat = ArchiveFormat.WIM

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "WimReader(source=$ownedSource)"

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close(): Unit = ownedSource.close()

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "WIM")
        val item = directory.items[listed.slotIn(this)]
        return withEntryContext(listed.name, listed.dataOffset) {
            limits.checkOutputSize(listed.size)
            val resource = item.resource ?: return@withEntryContext ByteArray(0)
            resourceBytes(resource, limits)
        }
    }

    // ---- Header and resources ----

    private var compression = NONE
    private var chunkSize = DEFAULT_CHUNK
    private var partNumber = 1

    private fun fail(message: String, offset: Long? = null): Nothing = throw ArchiveFormatException("WIM: $message", offset)

    private fun parse(): Directory {
        if (source.size < HEADER_SIZE) fail("file is shorter than the WIM header", 0)
        val header = source.readFully(0, HEADER_SIZE)
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) fail("missing MSWIM signature", 0)
        if (readU32Le(header, 8) < HEADER_SIZE) fail("header size ${readU32Le(header, 8)} is below $HEADER_SIZE", 8)
        val flags = readU32Le(header, 16).toInt()
        compression = when {
            flags and FLAG_COMPRESSION == 0 -> NONE
            flags and FLAG_XPRESS != 0 -> XPRESS
            flags and FLAG_LZX != 0 -> LZX
            flags and FLAG_LZMS != 0 -> LZMS
            else -> throw ArchiveUnsupportedException("WIM: unknown compression in header flags 0x${flags.toString(16)}", 16)
        }
        val declaredChunk = readU32Le(header, 20)
        chunkSize = if (declaredChunk == 0L) DEFAULT_CHUNK else declaredChunk.toInt()
        if (declaredChunk > MAX_CHUNK || chunkSize and (chunkSize - 1) != 0) fail("chunk size $declaredChunk is not a power of two up to $MAX_CHUNK", 20)
        partNumber = readU16Le(header, 40)
        val imageCount = readU32Le(header, 44)

        val table = resource(header, 48)
        if (table.flags and FLAG_COMPRESSED == 0 && table.stored % ENTRY_SIZE != 0L) fail("resource table size is not a multiple of $ENTRY_SIZE", 48)
        val tableBytes = resourceBytes(table, limits, verify = false)
        if (tableBytes.size % ENTRY_SIZE != 0) fail("resource table size is not a multiple of $ENTRY_SIZE", table.offset)
        val byHash = HashMap<String, Resource>()
        val metadata = ArrayList<Resource>()
        for (at in 0 until tableBytes.size step ENTRY_SIZE) {
            val hash = tableBytes.copyOfRange(at + 30, at + 50)
            val resource = resource(tableBytes, at, readU16Le(tableBytes, at + 24), hash)
            if (resource.flags and FLAG_FREE != 0) continue
            if (resource.flags and FLAG_METADATA != 0) metadata.add(resource) else byHash.getOrPut(hash.toKey()) { resource }
        }
        if (metadata.size.toLong() != imageCount) fail("header names $imageCount images but the resource table has ${metadata.size}", 44)

        val entries = ArrayList<ArchiveEntry>()
        val items = ArrayList<Item>()
        metadata.forEachIndexed { index, resource ->
            val prefix = if (metadata.size > 1) "${index + 1}/" else ""
            if (prefix.isNotEmpty()) {
                limits.checkEntryCount(entries.size + 1)
                entries.add(ArchiveEntry(prefix, 0, true))
                items.add(Item(null, null))
            }
            Tree(resourceBytes(resource, limits), prefix, byHash, entries, items).walk()
        }
        return Directory(entries.issuedBy(this, limits), items)
    }

    /** Read a 24-byte resource header at [at] of [bytes]. */
    private fun resource(bytes: ByteArray, at: Int, part: Int = partNumber, hash: ByteArray = ByteArray(0)): Resource {
        var stored = 0L
        for (i in 6 downTo 0) stored = (stored shl 8) or (bytes[at + i].toLong() and 0xFF)
        val offset = readI64(bytes, at + 8)
        val size = readI64(bytes, at + 16)
        if (offset < 0 || size < 0) fail("resource header has a negative offset or size")
        return Resource(offset, stored, size, bytes[at + 7].toInt() and 0xFF, part, hash)
    }

    /** Expand a resource, checking its SHA-1 when [verify] and it has one. */
    private fun resourceBytes(resource: Resource, limits: ArchiveLimits, verify: Boolean = true): ByteArray {
        if (resource.flags and FLAG_SOLID != 0) throw ArchiveUnsupportedException("WIM: solid resources are not supported", resource.offset)
        if (resource.part != partNumber) throw ArchiveUnsupportedException("WIM: data is in part ${resource.part} of a split WIM", resource.offset)
        if (resource.size > Int.MAX_VALUE) fail("resource of ${resource.size} bytes cannot fit in a ByteArray", resource.offset)
        limits.checkOutputSize(resource.size)
        limits.checkRatio(resource.stored, resource.size)
        if (resource.offset > source.size || resource.stored > source.size - resource.offset) fail("resource lies outside the file", resource.offset)
        val out = if (resource.flags and FLAG_COMPRESSED == 0) {
            if (resource.stored != resource.size) fail("uncompressed resource stores ${resource.stored} bytes for ${resource.size}", resource.offset)
            source.readFullyChecked(resource.offset, resource.size)
        } else {
            chunks(resource)
        }
        if (verify && resource.hash.isNotEmpty() && !Sha1.of(out).contentEquals(resource.hash)) {
            fail("SHA-1 mismatch", resource.offset)
        }
        return out
    }

    /** Expand a compressed resource: a chunk offset table, then chunks compressed alone. */
    private fun chunks(resource: Resource): ByteArray {
        if (compression == NONE) fail("compressed resource in an uncompressed WIM", resource.offset)
        if (compression == LZMS) throw ArchiveUnsupportedException("WIM: LZMS compression is not supported", resource.offset)
        val size = resource.size.toInt()
        val count = ((resource.size + chunkSize - 1) / chunkSize).toInt()
        val entryBytes = if (resource.size > 0xFFFFFFFFL) 8 else 4
        val tableSize = (count - 1).coerceAtLeast(0).toLong() * entryBytes
        if (tableSize > resource.stored) fail("chunk table is larger than its resource", resource.offset)
        if (resource.stored > Int.MAX_VALUE) fail("resource of ${resource.stored} stored bytes cannot fit in a ByteArray", resource.offset)
        val data = source.readFullyChecked(resource.offset, resource.stored)
        val dataStart = tableSize.toInt()
        val dataSize = data.size - dataStart
        fun chunkStart(i: Int): Long = when {
            i == 0 -> 0
            i == count -> dataSize.toLong()
            entryBytes == 4 -> readU32Le(data, 4 * (i - 1))
            else -> readI64(data, 8 * (i - 1))
        }
        val out = ByteArray(size)
        for (i in 0 until count) {
            val start = chunkStart(i)
            val end = chunkStart(i + 1)
            if (start < 0 || end < start || end > dataSize) fail("chunk $i lies outside its resource", resource.offset)
            val expanded = minOf(chunkSize, size - i * chunkSize)
            val from = dataStart + start.toInt()
            val to = dataStart + end.toInt()
            when {
                to - from == expanded -> data.copyInto(out, i * chunkSize, from, to)
                to - from > expanded -> fail("chunk $i stores ${to - from} bytes for $expanded", resource.offset + from)
                else -> {
                    val chunk = try {
                        if (compression == XPRESS) XpressHuffman.decode(data, from, to, expanded)
                        else Lzx.decodeWimChunk(data, from, to, expanded, windowBits())
                    } catch (error: ArchiveFormatException) {
                        throw ArchiveFormatException("WIM: chunk $i: ${error.message}", resource.offset + from, cause = error)
                    }
                    chunk.copyInto(out, i * chunkSize)
                }
            }
        }
        return out
    }

    private fun windowBits(): Int = 31 - chunkSize.countLeadingZeroBits()

    // ---- Directory trees ----

    /** One image's directory tree, from its metadata resource. */
    private inner class Tree(
        private val data: ByteArray,
        private val prefix: String,
        private val byHash: Map<String, Resource>,
        private val entries: MutableList<ArchiveEntry>,
        private val items: MutableList<Item>,
    ) {
        private val visited = HashSet<Long>()

        fun walk() {
            if (data.size < 8) fail("metadata resource is too short")
            val security = readU32Le(data, 0)
            if (security < 8 || security > data.size) fail("security data length $security is outside the metadata")
            val root = (security + 7) and 7L.inv()
            val dentry = dentry(root) ?: fail("metadata has no root directory")
            if (dentry.subdirectory != 0L) children(dentry.subdirectory, prefix, 0)
        }

        private inner class Dentry(
            val next: Long, val attributes: Int, val subdirectory: Long, val modified: Long?,
            val name: String, val dataHash: ByteArray?,
        )

        private fun children(start: Long, path: String, depth: Int) {
            if (depth > MAX_DEPTH) fail("directories nest more than $MAX_DEPTH deep")
            if (!visited.add(start)) fail("directory at metadata offset $start is reached twice")
            var at = start
            while (true) {
                val dentry = dentry(at) ?: return
                at = dentry.next
                if (dentry.name.isEmpty()) fail("unnamed directory entry at metadata offset $at")
                if ('/' in dentry.name || dentry.name.any { it.code == 0 }) fail("directory entry name contains '/' or NUL")
                val isDirectory = dentry.attributes and ATTRIBUTE_DIRECTORY != 0
                val fullName = path + dentry.name + if (isDirectory) "/" else ""
                limits.checkEntryCount(entries.size + 1)
                val hash = dentry.dataHash
                val resource = hash?.let { byHash[it.toKey()] }
                if (hash != null && resource == null && !isDirectory) {
                    fail("no resource has the SHA-1 of '$fullName'")
                }
                val size = if (isDirectory) 0 else resource?.size ?: 0
                entries.add(ArchiveEntry(fullName, size, isDirectory) {
                    lastModifiedEpochSeconds = dentry.modified
                    compressedSize = if (isDirectory) 0 else resource?.stored ?: 0
                    codec = when {
                        resource == null || resource.flags and FLAG_COMPRESSED == 0 -> CodecId.STORE
                        compression == XPRESS -> CodecId("xpress")
                        compression == LZX -> CodecId("lzx")
                        else -> CodecId("lzms")
                    }
                }.let { if (resource != null) it.locatedAt(resource.offset) else it })
                items.add(Item(if (isDirectory) null else resource, hash))
                if (isDirectory && dentry.subdirectory != 0L) children(dentry.subdirectory, fullName, depth + 1)
            }
        }

        /** The directory entry at [at], or null for an end-of-directory marker. */
        private fun dentry(at: Long): Dentry? {
            if (at < 0 || at + 8 > data.size) fail("directory entry at metadata offset $at lies outside the metadata")
            val p = at.toInt()
            val length = readI64(data, p)
            if (length in 0..8) return null
            if (length < DENTRY_SIZE || length > data.size - p) fail("directory entry at metadata offset $at has length $length")
            val attributes = readU32Le(data, p + 8).toInt()
            val subdirectory = readI64(data, p + 16)
            val written = readI64(data, p + 56)
            val defaultHash = data.copyOfRange(p + 64, p + 84)
            val streams = readU16Le(data, p + 96)
            val shortNameBytes = readU16Le(data, p + 98)
            val nameBytes = readU16Le(data, p + 100)
            if (nameBytes and 1 != 0 || shortNameBytes and 1 != 0) fail("directory entry name has an odd byte count")
            val needed = DENTRY_SIZE + (if (nameBytes > 0) nameBytes + 2 else 0) + (if (shortNameBytes > 0) shortNameBytes + 2 else 0)
            if (length < needed) fail("directory entry at metadata offset $at is too short for its names")
            val name = CharArray(nameBytes / 2) { (readU16Le(data, p + DENTRY_SIZE + 2 * it)).toChar() }.concatToString()
            var next = at + align8(length)
            var unnamedStream: ByteArray? = null
            repeat(streams) {
                if (next + STREAM_SIZE > data.size) fail("stream entry at metadata offset $next lies outside the metadata")
                val s = next.toInt()
                val streamLength = readI64(data, s)
                if (streamLength < STREAM_SIZE || streamLength > data.size - s) fail("stream entry at metadata offset $next has length $streamLength")
                if (readU16Le(data, s + 36) == 0 && unnamedStream == null) unnamedStream = data.copyOfRange(s + 16, s + 36)
                next += align8(streamLength)
            }
            val reparse = attributes and ATTRIBUTE_REPARSE_POINT != 0
            val dataHash = (if (reparse) unnamedStream else defaultHash.takeIf { !it.isZero() } ?: unnamedStream)?.takeIf { !it.isZero() }
            return Dentry(next, attributes, subdirectory, fileTime(written), name, dataHash)
        }
    }

    private companion object {
        val MAGIC = byteArrayOf(0x4D, 0x53, 0x57, 0x49, 0x4D, 0, 0, 0) // "MSWIM\0\0\0"
        const val HEADER_SIZE = 208
        const val ENTRY_SIZE = 50
        const val DENTRY_SIZE = 102
        const val STREAM_SIZE = 38
        const val DEFAULT_CHUNK = 32768
        const val MAX_CHUNK = 1L shl 26
        const val MAX_DEPTH = 1024

        const val FLAG_COMPRESSION = 0x00000002
        const val FLAG_XPRESS = 0x00020000
        const val FLAG_LZX = 0x00040000
        const val FLAG_LZMS = 0x00080000

        const val FLAG_FREE = 0x01
        const val FLAG_METADATA = 0x02
        const val FLAG_COMPRESSED = 0x04
        const val FLAG_SOLID = 0x10

        const val ATTRIBUTE_DIRECTORY = 0x10
        const val ATTRIBUTE_REPARSE_POINT = 0x400

        const val NONE = 0
        const val XPRESS = 1
        const val LZX = 2
        const val LZMS = 3

        /** 100-nanosecond intervals between 1601-01-01 and 1970-01-01. */
        const val FILETIME_EPOCH = 116_444_736_000_000_000L

        fun readI64(bytes: ByteArray, at: Int): Long = readU32Le(bytes, at) or (readU32Le(bytes, at + 4) shl 32)

        fun align8(value: Long): Long = (value + 7) and 7L.inv()

        fun fileTime(value: Long): Long? = if (value == 0L) null else (value - FILETIME_EPOCH).floorDiv(10_000_000L)

        fun ByteArray.isZero(): Boolean = all { it.toInt() == 0 }

        fun ByteArray.toKey(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
