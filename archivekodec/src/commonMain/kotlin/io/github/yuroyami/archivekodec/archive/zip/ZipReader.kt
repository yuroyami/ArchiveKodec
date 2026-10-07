package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.withEntryContext
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.archive.readFullyChecked
import io.github.yuroyami.archivekodec.archive.requireRange
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException

/**
 * ZIP reader over a seekable [RandomAccessSource] (+ZIP64). Reads the central
 * directory at the tail (the authoritative record). [entries] also validates local
 * headers and declared payload extents without reading payloads; [read] seeks to one header for
 * partial extract. This reader decodes STORE and DEFLATE. Other methods appear in
 * [entries], but [read] throws for them.
 *
 * Security: duplicate local-header offsets (the overlapping-bomb signature) are
 * rejected at parse time; [read] enforces [ArchiveLimits] (size + ratio) and verifies
 * the central-directory output size and stored CRC-32. This reader returns entry names raw. Run them through
 * [io.github.yuroyami.archivekodec.security.PathGuard] before writing to disk.
 */
class ZipReader(private val source: RandomAccessSource) : ArchiveReader {

    override fun toString(): String = "ZipReader(source=$source)"

    override val format = ArchiveFormat.ZIP

    private val cached: List<ArchiveEntry> by lazy { parseCentralDirectory() }

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = cached

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray = withEntryContext(entry.name, entry.dataOffset) {
        val lh = source.readFully(entry.dataOffset, 30)
        if (ZipFormat.u32(lh, 0) != ZipFormat.LOC) throw ArchiveFormatException("ZIP: invalid local file header", entry.dataOffset, entry.name)
        val method = ZipFormat.u16(lh, 8)
        val nameLen = ZipFormat.u16(lh, 26)
        val extraLen = ZipFormat.u16(lh, 28)
        source.requireRange(entry.dataOffset + 30, (nameLen + extraLen).toLong())
        val dataStart = entry.dataOffset + 30 + nameLen + extraLen

        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.compressedSize, entry.size)

        val codecId = ZipFormat.codecForMethod(method)
            ?: throw ArchiveUnsupportedException("ZIP method $method not supported", entry.dataOffset, entry.name)
        val codec = Codecs.byId(codecId)
            ?: throw ArchiveUnsupportedException("ZIP method $method ($codecId) not implemented yet", entry.dataOffset, entry.name)
        if (codecId == CodecId.STORE && entry.compressedSize != entry.size) {
            throw ArchiveFormatException("ZIP: stored compressed size ${entry.compressedSize} differs from output size ${entry.size}", dataStart, entry.name)
        }
        val comp = source.readFullyChecked(dataStart, entry.compressedSize)
        val out = try {
            if (codecId == CodecId.DEFLATE) Inflate.inflateExact(comp, limits, entry.size)
            else codec.decompress(comp, limits)
        } catch (error: ArchiveException) {
            throw error.withEntryContext(entry.name, dataStart, relativeOffset = true)
        }

        if (out.size.toLong() != entry.size) {
            throw ArchiveFormatException("ZIP: output size ${out.size} differs from declared size ${entry.size}", dataStart, entry.name)
        }

        entry.crc32?.let { expected ->
            if (Crc32.of(out) != expected) {
                throw ArchiveFormatException("ZIP: CRC-32 mismatch", dataStart, entry.name)
            }
        }
        return@withEntryContext out
    }

    private fun parseCentralDirectory(): List<ArchiveEntry> {
        val size = source.size
        if (size < 22) throw ArchiveFormatException("ZIP: file is too small", 0)

        // Locate EOCD by scanning back over the last 22 + max-comment(65535) bytes.
        val scan = minOf(size, (22 + 0xFFFF).toLong()).toInt()
        val tail = source.readFully(size - scan, scan)
        var eocd = -1
        for (i in scan - 22 downTo 0) {
            if (ZipFormat.u32(tail, i) == ZipFormat.EOCD && ZipFormat.u16(tail, i + 20) == scan - i - 22) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw ArchiveFormatException("ZIP: missing end-of-central-directory", size - scan)
        val eocdOffset = size - scan + eocd
        fun requireDiskZero(disk: Long, offset: Long, name: String? = null) {
            if (disk != 0L) throw ArchiveUnsupportedException("ZIP: multi-disk archives are not supported (disk $disk)", offset, name)
        }

        var cdEntries = ZipFormat.u16(tail, eocd + 10).toLong()
        var cdSize = ZipFormat.u32(tail, eocd + 12)
        var cdOffset = ZipFormat.u32(tail, eocd + 16)
        var directoryEnd = eocdOffset

        // A present ZIP64 locator is authoritative even without classic sentinels.
        // The classic count 65535 also occurs without ZIP64, including wrapped counts.
        val locPos = directoryEnd - 20
        val locator = if (locPos >= 0) source.readFully(locPos, 20) else null
        val hasZip64 = locator != null && ZipFormat.u32(locator, 0) == ZipFormat.Z64_LOC
        for (field in listOf(4, 6)) {
            val disk = ZipFormat.u16(tail, eocd + field).toLong()
            if (!(hasZip64 && disk == 0xFFFFL)) requireDiskZero(disk, eocdOffset + field)
        }
        if (!hasZip64 && (cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL)) {
            throw ArchiveFormatException("ZIP: missing ZIP64 locator", locPos)
        }
        if (hasZip64) {
            checkNotNull(locator)
            requireDiskZero(ZipFormat.u32(locator, 4), locPos + 4)
            val disks = ZipFormat.u32(locator, 16)
            if (disks == 0L) throw ArchiveFormatException("ZIP: invalid disk count 0 in ZIP64 locator", locPos + 16)
            if (disks != 1L) throw ArchiveUnsupportedException("ZIP: multi-disk archives are not supported ($disks disks)", locPos + 16)
            val z64Off = ZipFormat.u64(locator, 8)
            if (z64Off < 0 || z64Off > locPos - 56) {
                throw ArchiveFormatException("ZIP: ZIP64 end record exceeds locator boundary", z64Off)
            }
            val z64 = source.readFully(z64Off, 56)
            if (ZipFormat.u32(z64, 0) != ZipFormat.Z64_EOCD) {
                throw ArchiveFormatException("ZIP: invalid ZIP64 end record", z64Off)
            }
            val recordSize = ZipFormat.u64(z64, 4)
            if (recordSize < 44 || recordSize > locPos - z64Off - 12) {
                throw ArchiveFormatException("ZIP: invalid ZIP64 end record size $recordSize", z64Off + 4)
            }
            requireDiskZero(ZipFormat.u32(z64, 16), z64Off + 16)
            requireDiskZero(ZipFormat.u32(z64, 20), z64Off + 20)
            cdEntries = ZipFormat.u64(z64, 32)
            val diskEntries = ZipFormat.u64(z64, 24)
            if (diskEntries != cdEntries) {
                throw ArchiveFormatException("ZIP: per-disk count $diskEntries differs from total count $cdEntries", z64Off + 24)
            }
            cdSize = ZipFormat.u64(z64, 40)
            cdOffset = ZipFormat.u64(z64, 48)
            directoryEnd = z64Off
        } else {
            val diskEntries = ZipFormat.u16(tail, eocd + 8).toLong()
            if (diskEntries != cdEntries) {
                throw ArchiveFormatException("ZIP: per-disk count $diskEntries differs from total count $cdEntries", eocdOffset + 8)
            }
        }
        source.requireRange(cdOffset, cdSize)
        if (cdOffset > directoryEnd || cdSize > directoryEnd - cdOffset) {
            throw ArchiveFormatException("ZIP: central directory overlaps end records", cdOffset)
        }
        if (cdEntries < 0 || cdEntries > Int.MAX_VALUE || cdEntries > cdSize / 46) {
            throw ArchiveFormatException("ZIP: invalid directory count $cdEntries for $cdSize bytes", cdOffset)
        }

        val cd = source.readFullyChecked(cdOffset, cdSize)
        val entries = ArrayList<ArchiveEntry>()
        val seenOffsets = HashSet<Long>()
        var p = 0
        var n = 0L
        while (p < cd.size) {
            if (cd.size - p >= 4 && ZipFormat.u32(cd, p) == ZipFormat.CEN_SIGNATURE) {
                if (cd.size - p < 6 || ZipFormat.u16(cd, p + 4) != cd.size - p - 6) {
                    throw ArchiveFormatException("ZIP: invalid central directory digital signature length", cdOffset + p)
                }
                // Recognize and bound the optional record; this does not authenticate it.
                p = cd.size
                break
            }
            if (p > cd.size - 46) {
                throw ArchiveFormatException("ZIP: truncated central directory at entry $n of $cdEntries", cdOffset + p)
            }
            if (ZipFormat.u32(cd, p) != ZipFormat.CEN) throw ArchiveFormatException("ZIP: invalid central directory signature", cdOffset + p)
            val method = ZipFormat.u16(cd, p + 10)
            val crc = ZipFormat.u32(cd, p + 16)
            var compSize = ZipFormat.u32(cd, p + 20)
            var uncompSize = ZipFormat.u32(cd, p + 24)
            val nameLen = ZipFormat.u16(cd, p + 28)
            val extraLen = ZipFormat.u16(cd, p + 30)
            val commentLen = ZipFormat.u16(cd, p + 32)
            var startDisk = ZipFormat.u16(cd, p + 34).toLong()
            var diskFieldOffset = cdOffset + p + 34
            var localOffset = ZipFormat.u32(cd, p + 42)
            val recordSize = 46 + nameLen + extraLen + commentLen
            if (recordSize > cd.size - p) {
                throw ArchiveFormatException("ZIP: central directory fields exceed record at entry $n", cdOffset + p)
            }
            val name = cd.decodeToString(p + 46, p + 46 + nameLen)

            val zip64Output = uncompSize == 0xFFFFFFFFL
            val zip64Compressed = compSize == 0xFFFFFFFFL
            val zip64Offset = localOffset == 0xFFFFFFFFL
            val zip64Disk = startDisk == 0xFFFFL
            if (zip64Output || zip64Compressed || zip64Offset || zip64Disk) {
                var ep = p + 46 + nameLen
                val extraStart = ep
                val extraEnd = ep + extraLen
                var found = false
                while (ep < extraEnd) {
                    if (extraEnd - ep < 4) throw ArchiveFormatException("ZIP: truncated extra field header", cdOffset + ep, name)
                    val id = ZipFormat.u16(cd, ep)
                    val sz = ZipFormat.u16(cd, ep + 2)
                    var dp = ep + 4
                    if (sz > extraEnd - dp) throw ArchiveFormatException("ZIP: truncated extra field", cdOffset + ep, name)
                    if (id == 0x0001) { // ZIP64 extended info: fields present in this order
                        if (found) throw ArchiveFormatException("ZIP: duplicate required ZIP64 extra field", cdOffset + ep, name)
                        found = true
                        val needed = (if (zip64Output) 8 else 0) + (if (zip64Compressed) 8 else 0) +
                            (if (zip64Offset) 8 else 0) + (if (zip64Disk) 4 else 0)
                        if (sz < needed) throw ArchiveFormatException("ZIP: truncated ZIP64 entry fields", cdOffset + dp, name)
                        fun signedValue(field: String): Long {
                            val value = ZipFormat.u64(cd, dp)
                            if (value < 0) throw ArchiveFormatException("ZIP: ZIP64 $field exceeds Long", cdOffset + dp, name)
                            dp += 8
                            return value
                        }
                        if (zip64Output) uncompSize = signedValue("uncompressed size")
                        if (zip64Compressed) compSize = signedValue("compressed size")
                        if (zip64Offset) localOffset = signedValue("local header offset")
                        if (zip64Disk) {
                            diskFieldOffset = cdOffset + dp
                            startDisk = ZipFormat.u32(cd, dp)
                        }
                    }
                    ep += 4 + sz
                }
                if (!found) throw ArchiveFormatException("ZIP: missing required ZIP64 extra field", cdOffset + extraStart, name)
            }

            requireDiskZero(startDisk, diskFieldOffset, name)
            if (uncompSize < 0) throw ArchiveFormatException("ZIP: uncompressed size exceeds Long", cdOffset + p, name)
            val local = source.readFully(localOffset, 30)
            if (ZipFormat.u32(local, 0) != ZipFormat.LOC) {
                throw ArchiveFormatException("ZIP: invalid local file header", localOffset, name)
            }
            val localFieldsSize = ZipFormat.u16(local, 26) + ZipFormat.u16(local, 28)
            source.requireRange(localOffset + 30, localFieldsSize.toLong())
            source.requireRange(localOffset + 30 + localFieldsSize, compSize)
            if (!seenOffsets.add(localOffset)) {
                throw DecompressionBombException("overlapping local header offset $localOffset (zip-bomb signature)")
            }

            val isDir = name.endsWith("/")
            entries.add(
                ArchiveEntry(
                    name = name,
                    size = uncompSize,
                    isDirectory = isDir,
                    compressedSize = compSize,
                    codec = ZipFormat.codecForMethod(method) ?: CodecId.STORE,
                    crc32 = crc,
                    dataOffset = localOffset, // local header offset; resolved to data in read()
                ),
            )
            p += 46 + nameLen + extraLen + commentLen
            n++
        }
        val countForComparison = if (hasZip64) n else n and 0xFFFFL
        if (countForComparison != cdEntries) {
            throw ArchiveFormatException("ZIP: central directory count $n differs from declared count $cdEntries", cdOffset)
        }
        return entries
    }
}
