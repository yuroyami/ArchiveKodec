package io.github.yuroyami.archivekodec.archive.squashfs

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
import io.github.yuroyami.archivekodec.archive.resolveIssued
import io.github.yuroyami.archivekodec.archive.withEntryContext
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.codec.lzma.LzmaAlone
import io.github.yuroyami.archivekodec.codec.lzma.Xz
import io.github.yuroyami.archivekodec.codec.lzo.Lzo1x
import io.github.yuroyami.archivekodec.codec.zstd.ZstdDecoder
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le
import io.github.yuroyami.archivekodec.wrapper.Zlib

/**
 * SquashFS 4.0 reader, the read-only filesystem of Linux live media, snaps and Android and
 * OpenWrt images. Blocks may be compressed with gzip, LZMA, LZO, xz (with the x86 BCJ
 * filter at most), LZ4 or zstd, as the superblock says.
 *
 * Directories are listed with a trailing slash. A symbolic link is listed with its link
 * mode and reads as its target text; devices, FIFOs and sockets are listed with their modes
 * and read as empty. Hard links list each name with the shared contents. Sparse blocks read
 * as zeros. Names are decoded with [nameDecoder] (UTF-8 by default). Owners and extended
 * attributes are not exposed. [limits] caps the listing count and supplies the default
 * policy for each read.
 *
 * Owns the supplied source; close this reader to release it, including after a failed
 * operation. This reader is not thread-safe.
 */
public class SquashfsReader(
    source: RandomAccessSource,
    private val nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
) : ArchiveReader {
    public constructor(source: RandomAccessSource, limits: ArchiveLimits) : this(source, ArchiveNameDecoder.UTF8, limits)
    public constructor(source: RandomAccessSource) : this(source, ArchiveLimits.DEFAULT)

    /** A listed member's data: a file's blocks and fragment, or a link target. */
    private class Item(
        val blocksStart: Long, val fragment: Long, val fragmentOffset: Long,
        val blockList: Long, val blockListOffset: Int, val target: ByteArray?,
    )

    private class Directory(val entries: List<ArchiveEntry>, val items: List<Item>)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    override val format: ArchiveFormat = ArchiveFormat.SQUASHFS

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "SquashfsReader(source=$ownedSource)"

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata { parse() }

    @Throws(ArchiveException::class)
    override fun close() {
        metadataCache.clear()
        fragments = null
        cachedFragment = -1
        cachedFragmentBytes = null
        ownedSource.close()
    }

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "SquashFS")
        val item = directory.items[listed.slotIn(this)]
        return withEntryContext(listed.name, listed.dataOffset) {
            limits.checkOutputSize(listed.size)
            when {
                item.target != null -> item.target.copyOf()
                listed.isDirectory || listed.size == 0L -> ByteArray(0)
                else -> fileBytes(item, listed.size, limits)
            }
        }
    }

    // ---- Superblock ----

    private var compression = 0
    private var blockSize = 0
    private var bytesUsed = 0L
    private var inodeTable = 0L
    private var directoryTable = 0L
    private var fragmentTable = 0L
    private var fragmentCount = 0L
    private lateinit var blockLimits: ArchiveLimits
    private lateinit var metadataLimits: ArchiveLimits

    private fun fail(message: String, offset: Long? = null): Nothing = throw ArchiveFormatException("SquashFS: $message", offset)

    private fun parse(): Directory {
        if (source.size < SUPERBLOCK) fail("file is shorter than the superblock", 0)
        val sb = source.readFully(0, SUPERBLOCK)
        if (readU32Le(sb, 0) != MAGIC) fail("missing hsqs signature", 0)
        val major = readU16Le(sb, 28)
        val minor = readU16Le(sb, 30)
        if (major != 4 || minor != 0) throw ArchiveUnsupportedException("SquashFS: version $major.$minor is not supported; only 4.0 is", 28)
        compression = readU16Le(sb, 20)
        if (compression !in GZIP..ZSTD) throw ArchiveUnsupportedException("SquashFS: unknown compression id $compression", 20)
        blockSize = readU32Le(sb, 12).let { if (it > MAX_BLOCK) fail("block size $it is above $MAX_BLOCK", 12) else it.toInt() }
        if (blockSize < MIN_BLOCK || blockSize and (blockSize - 1) != 0 || 1 shl readU16Le(sb, 22) != blockSize) {
            fail("block size $blockSize does not match its log ${readU16Le(sb, 22)}", 12)
        }
        fragmentCount = readU32Le(sb, 16)
        bytesUsed = readI64(sb, 40)
        if (bytesUsed < SUPERBLOCK || bytesUsed > source.size) fail("image claims $bytesUsed bytes but the file has ${source.size}", 40)
        inodeTable = readI64(sb, 64)
        directoryTable = readI64(sb, 72)
        fragmentTable = readI64(sb, 80)
        if (inodeTable !in SUPERBLOCK until bytesUsed || directoryTable !in inodeTable until bytesUsed) fail("metadata tables lie outside the image", 64)
        val outer = limits
        blockLimits = ArchiveLimits {
            maxUncompressedBytes = blockSize.toLong(); maxCompressionRatio = Int.MAX_VALUE; maxDecoderMemory = outer.maxDecoderMemory
        }
        metadataLimits = ArchiveLimits {
            maxUncompressedBytes = METADATA_BLOCK.toLong(); maxCompressionRatio = Int.MAX_VALUE; maxDecoderMemory = outer.maxDecoderMemory
        }

        val entries = ArrayList<ArchiveEntry>()
        val items = ArrayList<Item>()
        val rootRef = readI64(sb, 32)
        val root = inode(rootRef ushr 16, (rootRef and 0xFFFF).toInt())
        if (root.type != DIRECTORY) fail("root inode is not a directory", 32)
        Walker(entries, items).directory(root, "", 0)
        return Directory(entries.issuedBy(this, limits), items)
    }

    // ---- Blocks ----

    private fun decompress(data: ByteArray, maximum: Int, offset: Long): ByteArray {
        val limits = if (maximum == METADATA_BLOCK) metadataLimits else blockLimits
        val out = try {
            when (compression) {
                GZIP -> Zlib.decompress(data, limits)
                LZMA -> LzmaAlone.decompress(data, limits)
                LZO -> Lzo1x.decode(data, 0, data.size, maximum)
                XZ -> Xz.decompress(data, limits)
                LZ4 -> Lz4.blockDecompress(data, 0, data.size, maximum, maximum)
                else -> ZstdDecoder.decompress(data, limits)
            }
        } catch (error: ArchiveUnsupportedException) {
            throw ArchiveUnsupportedException("SquashFS: block at $offset: ${error.message}", offset, cause = error)
        } catch (error: ArchiveException) {
            throw ArchiveFormatException("SquashFS: block at $offset: ${error.message}", offset, cause = error)
        }
        if (out.size > maximum) fail("block at $offset expands past $maximum bytes", offset)
        return out
    }

    private class MetadataBlock(val bytes: ByteArray, val next: Long)

    private val metadataCache = HashMap<Long, MetadataBlock>()

    /** The metadata block at absolute [position]: a 16-bit header, then up to 8 KiB of data. */
    private fun metadataBlock(position: Long): MetadataBlock = metadataCache.getOrPut(position) {
        if (position < SUPERBLOCK || position + 2 > bytesUsed) fail("metadata block at $position lies outside the image", position)
        val header = readU16Le(source.readFully(position, 2), 0)
        val length = header and 0x7FFF
        if (length == 0 || length > METADATA_BLOCK || position + 2 + length > bytesUsed) fail("metadata block at $position has length $length", position)
        val raw = source.readFully(position + 2, length)
        val bytes = if (header and 0x8000 != 0) raw else decompress(raw, METADATA_BLOCK, position)
        if (metadataCache.size >= MAX_CACHED_BLOCKS) metadataCache.clear()
        MetadataBlock(bytes, position + 2 + length)
    }

    /** Bytes read across consecutive metadata blocks, starting [offset] bytes into the block at [position]. */
    private inner class MetadataStream(private var position: Long, private var offset: Int) {
        private var block = metadataBlock(position)

        init {
            if (offset > block.bytes.size) fail("metadata offset $offset is past its block", position)
        }

        /** Where the next byte comes from: its block and the offset in it. */
        val here: Pair<Long, Int> get() = if (offset == block.bytes.size) block.next to 0 else position to offset

        fun bytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var done = 0
            while (done < count) {
                if (offset == block.bytes.size) {
                    position = block.next
                    block = metadataBlock(position)
                    offset = 0
                }
                val n = minOf(count - done, block.bytes.size - offset)
                block.bytes.copyInto(out, done, offset, offset + n)
                offset += n
                done += n
            }
            return out
        }

        fun skip(count: Long) {
            var left = count
            while (left > 0) {
                if (offset == block.bytes.size) {
                    position = block.next
                    block = metadataBlock(position)
                    offset = 0
                }
                val n = minOf(left, (block.bytes.size - offset).toLong()).toInt()
                offset += n
                left -= n
            }
        }
    }

    // ---- Inodes ----

    private class Inode(
        val type: Int, val mode: Int, val modified: Long,
        val size: Long = 0, val blocksStart: Long = 0, val fragment: Long = NO_FRAGMENT, val fragmentOffset: Long = 0,
        val blockList: Long = 0, val blockListOffset: Int = 0,
        val listing: Long = 0, val listingOffset: Int = 0, val target: ByteArray? = null,
    )

    /** The inode at [block] (relative to the inode table) and [offset] within it. */
    private fun inode(block: Long, offset: Int): Inode {
        val stream = MetadataStream(inodeTable + block, offset)
        val header = stream.bytes(16)
        val type = readU16Le(header, 0)
        val permissions = readU16Le(header, 2) and 0xFFF
        val modified = readU32Le(header, 8)
        return when (type) {
            BASIC_DIRECTORY -> {
                val b = stream.bytes(16)
                Inode(DIRECTORY, S_IFDIR or permissions, modified,
                    size = readU16Le(b, 8).toLong(), listing = readU32Le(b, 0), listingOffset = readU16Le(b, 10))
            }
            EXTENDED_DIRECTORY -> {
                val b = stream.bytes(24)
                Inode(DIRECTORY, S_IFDIR or permissions, modified,
                    size = readU32Le(b, 4), listing = readU32Le(b, 8), listingOffset = readU16Le(b, 18))
            }
            BASIC_FILE, EXTENDED_FILE -> {
                val extended = type == EXTENDED_FILE
                val b = stream.bytes(if (extended) 40 else 16)
                val start = if (extended) readI64(b, 0) else readU32Le(b, 0)
                val size = if (extended) readI64(b, 8) else readU32Le(b, 12)
                val fragment = if (extended) readU32Le(b, 28) else readU32Le(b, 4)
                val fragmentOffset = if (extended) readU32Le(b, 32) else readU32Le(b, 8)
                if (size < 0 || start < 0) fail("file inode has a negative size or start", inodeTable + block)
                val (listBlock, listOffset) = stream.here
                Inode(FILE, S_IFREG or permissions, modified, size, start, fragment, fragmentOffset, listBlock, listOffset)
            }
            BASIC_SYMLINK, EXTENDED_SYMLINK -> {
                val b = stream.bytes(8)
                val length = readU32Le(b, 4)
                if (length > MAX_TARGET) fail("symbolic link target of $length bytes", inodeTable + block)
                Inode(SYMLINK, S_IFLNK or permissions, modified, target = stream.bytes(length.toInt()))
            }
            BASIC_BLOCK_DEVICE, EXTENDED_BLOCK_DEVICE -> Inode(OTHER, S_IFBLK or permissions, modified)
            BASIC_CHAR_DEVICE, EXTENDED_CHAR_DEVICE -> Inode(OTHER, S_IFCHR or permissions, modified)
            BASIC_FIFO, EXTENDED_FIFO -> Inode(OTHER, S_IFIFO or permissions, modified)
            BASIC_SOCKET, EXTENDED_SOCKET -> Inode(OTHER, S_IFSOCK or permissions, modified)
            else -> fail("unknown inode type $type", inodeTable + block)
        }
    }

    // ---- Directories ----

    private inner class Walker(private val entries: MutableList<ArchiveEntry>, private val items: MutableList<Item>) {
        private val visited = HashSet<Long>()

        fun directory(inode: Inode, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) fail("directories nest more than $MAX_DEPTH deep")
            if (inode.size < 3) fail("directory listing size ${inode.size} is below 3")
            var left = inode.size - 3
            if (left == 0L) return
            // Empty directories share a listing position; others never do.
            if (!visited.add((inode.listing shl 16) or inode.listingOffset.toLong())) fail("a directory is reached twice")
            val stream = MetadataStream(directoryTable + inode.listing, inode.listingOffset)
            while (left > 0) {
                if (left < 12) fail("directory header runs past its listing")
                val header = stream.bytes(12)
                left -= 12
                val count = readU32Le(header, 0) + 1
                if (count > 256) fail("directory header counts $count entries")
                val inodeBlock = readU32Le(header, 4)
                repeat(count.toInt()) {
                    if (left < 8) fail("directory entry runs past its listing")
                    val e = stream.bytes(8)
                    val nameLength = readU16Le(e, 6) + 1
                    left -= 8 + nameLength
                    if (left < 0) fail("directory entry name runs past its listing")
                    val raw = stream.bytes(nameLength)
                    if (raw.any { it == '/'.code.toByte() || it.toInt() == 0 }) fail("directory entry name contains '/' or NUL")
                    val name = nameDecoder.decodeStoredName(raw, directoryTable + inode.listing)
                    val child = inode(inodeBlock, readU16Le(e, 0))
                    add(child, prefix + name, depth)
                }
            }
        }

        private fun add(inode: Inode, path: String, depth: Int) {
            limits.checkEntryCount(entries.size + 1)
            val isDirectory = inode.type == DIRECTORY
            val size = when (inode.type) {
                FILE -> inode.size
                SYMLINK -> inode.target!!.size.toLong()
                else -> 0
            }
            entries.add(ArchiveEntry(if (isDirectory) "$path/" else path, size, isDirectory) {
                lastModifiedEpochSeconds = inode.modified
                unixMode = inode.mode
                compressedSize = size
                codec = if (inode.type == FILE) CODECS[compression] else CodecId.STORE
            }.let { if (inode.type == FILE) it.locatedAt(inode.blocksStart) else it })
            items.add(Item(inode.blocksStart, inode.fragment, inode.fragmentOffset, inode.blockList, inode.blockListOffset, inode.target))
            if (isDirectory) directory(inode, "$path/", depth + 1)
        }
    }

    // ---- File data ----

    private fun fileBytes(item: Item, size: Long, limits: ArchiveLimits): ByteArray {
        if (size > Int.MAX_VALUE) fail("file of $size bytes cannot fit in a ByteArray")
        val hasFragment = item.fragment != NO_FRAGMENT
        val blocks = if (hasFragment) size / blockSize else (size + blockSize - 1) / blockSize
        val sizes = MetadataStream(item.blockList, item.blockListOffset).bytes((blocks * 4).toInt())
        var stored = 0L
        for (i in 0 until blocks.toInt()) stored += readU32Le(sizes, 4 * i) and BLOCK_SIZE_MASK
        limits.checkRatio(stored, size)
        val out = ByteArray(size.toInt())
        var position = item.blocksStart
        for (i in 0 until blocks.toInt()) {
            val word = readU32Le(sizes, 4 * i)
            val length = (word and BLOCK_SIZE_MASK).toInt()
            val expected = minOf(blockSize.toLong(), size - i.toLong() * blockSize).toInt()
            if (length != 0) { // zero: a sparse block of zeros
                if (position < 0 || position + length > bytesUsed) fail("data block lies outside the image", position)
                val raw = source.readFully(position, length)
                val block = if (word and BLOCK_UNCOMPRESSED != 0L) raw else decompress(raw, blockSize, position)
                if (block.size != expected) fail("data block at $position holds ${block.size} bytes, not $expected", position)
                block.copyInto(out, i * blockSize)
                position += length
            }
        }
        if (hasFragment) {
            val tail = (size - blocks * blockSize).toInt()
            val fragment = fragmentBytes(item.fragment)
            if (item.fragmentOffset < 0 || item.fragmentOffset + tail > fragment.size) fail("file tail lies outside its fragment")
            fragment.copyInto(out, (blocks * blockSize).toInt(), item.fragmentOffset.toInt(), item.fragmentOffset.toInt() + tail)
        }
        return out
    }

    private var fragments: LongArray? = null
    private var cachedFragment = -1L
    private var cachedFragmentBytes: ByteArray? = null

    private fun fragmentBytes(index: Long): ByteArray {
        if (index == cachedFragment) return cachedFragmentBytes!!
        val table = fragments ?: fragmentTable().also { fragments = it }
        if (index < 0 || index >= table.size / 2) fail("fragment $index of ${table.size / 2}")
        val start = table[2 * index.toInt()]
        val word = table[2 * index.toInt() + 1]
        val length = (word and BLOCK_SIZE_MASK).toInt()
        if (start < 0 || start + length > bytesUsed) fail("fragment $index lies outside the image", start)
        val raw = source.readFully(start, length)
        val bytes = if (word and BLOCK_UNCOMPRESSED != 0L) raw else decompress(raw, blockSize, start)
        cachedFragment = index
        cachedFragmentBytes = bytes
        return bytes
    }

    /** Fragment start and size words, from 16-byte entries in metadata blocks listed at the table start. */
    private fun fragmentTable(): LongArray {
        val count = fragmentCount
        val pointers = ((count + ENTRIES_PER_BLOCK - 1) / ENTRIES_PER_BLOCK).toInt()
        if (fragmentTable < 0 || fragmentTable + 8L * pointers > bytesUsed) fail("fragment table lies outside the image", fragmentTable)
        val list = source.readFully(fragmentTable, 8 * pointers)
        val table = LongArray((2 * count).toInt())
        var n = 0
        for (p in 0 until pointers) {
            val block = metadataBlock(readI64(list, 8 * p)).bytes
            var at = 0
            while (at + 16 <= block.size && n < count) {
                table[2 * n] = readI64(block, at)
                table[2 * n + 1] = readU32Le(block, at + 8)
                n++
                at += 16
            }
        }
        if (n.toLong() != count) fail("fragment table holds $n of $count entries", fragmentTable)
        return table
    }

    private companion object {
        const val MAGIC = 0x73717368L // "hsqs"
        const val SUPERBLOCK = 96
        const val METADATA_BLOCK = 8192
        const val MIN_BLOCK = 4096
        const val MAX_BLOCK = 1L shl 20
        const val MAX_DEPTH = 1024
        const val MAX_TARGET = 65536L
        const val MAX_CACHED_BLOCKS = 4096
        const val ENTRIES_PER_BLOCK = 512
        const val NO_FRAGMENT = 0xFFFFFFFFL
        const val BLOCK_SIZE_MASK = 0x00FFFFFFL
        const val BLOCK_UNCOMPRESSED = 0x01000000L

        const val GZIP = 1
        const val LZMA = 2
        const val LZO = 3
        const val XZ = 4
        const val LZ4 = 5
        const val ZSTD = 6
        val CODECS = mapOf(
            GZIP to CodecId.ZLIB, LZMA to CodecId.LZMA, LZO to CodecId("lzo"), XZ to CodecId.XZ,
            LZ4 to CodecId("lz4-block"), ZSTD to CodecId.ZSTD,
        ).let { map -> Array(7) { map[it] ?: CodecId.STORE } }

        const val BASIC_DIRECTORY = 1
        const val BASIC_FILE = 2
        const val BASIC_SYMLINK = 3
        const val BASIC_BLOCK_DEVICE = 4
        const val BASIC_CHAR_DEVICE = 5
        const val BASIC_FIFO = 6
        const val BASIC_SOCKET = 7
        const val EXTENDED_DIRECTORY = 8
        const val EXTENDED_FILE = 9
        const val EXTENDED_SYMLINK = 10
        const val EXTENDED_BLOCK_DEVICE = 11
        const val EXTENDED_CHAR_DEVICE = 12
        const val EXTENDED_FIFO = 13
        const val EXTENDED_SOCKET = 14

        const val DIRECTORY = 1
        const val FILE = 2
        const val SYMLINK = 3
        const val OTHER = 4

        const val S_IFDIR = 0x4000
        const val S_IFREG = 0x8000
        const val S_IFLNK = 0xA000
        const val S_IFBLK = 0x6000
        const val S_IFCHR = 0x2000
        const val S_IFIFO = 0x1000
        const val S_IFSOCK = 0xC000

        fun readI64(bytes: ByteArray, at: Int): Long = readU32Le(bytes, at) or (readU32Le(bytes, at + 4) shl 32)
    }
}
