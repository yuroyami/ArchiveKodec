package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
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
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException

/**
 * ZIP reader over a seekable [RandomAccessSource] (+ZIP64). Reads the central
 * directory at the tail (the authoritative record). [entries] also validates local
 * header names, methods, flags and declared payload extents; [read] rechecks the
 * local fields against the parsed directory before extracting one entry.
 * Filename comparison uses exact stored bytes. Masked local headers with
 * central-directory encryption are unsupported. This reader decodes STORE and
 * DEFLATE. Other methods appear in [entries], but [read] throws for them.
 *
 * Security: local-header and compressed-data extents must be disjoint and end
 * before the central directory. Duplicate local-header offsets are also rejected
 * at parse time; [read] enforces [ArchiveLimits] (size + ratio) and verifies
 * the central-directory output size and stored CRC-32. This reader returns entry names raw. Run them through
 * [io.github.yuroyami.archivekodec.security.PathGuard] before writing to disk.
 */
class ZipReader(source: RandomAccessSource) : ArchiveReader {

    private class Member(
        val entry: ArchiveEntry,
        val nameBytes: ByteArray,
        val method: Int,
        val flags: Int,
        val localExtraLength: Int,
    )

    private class Directory(val entries: List<ArchiveEntry>, val members: Map<Long, Member>)

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    override fun toString(): String = "ZipReader(source=$ownedSource)"

    override val format = ArchiveFormat.ZIP

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata { parseCentralDirectory() }

    @Throws(ArchiveException::class)
    override fun close() = ownedSource.close()

    /** Read a directory entry or an unchanged value copy; altered metadata is rejected. */
    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray = withEntryContext(entry.name, entry.dataOffset) {
        val source = ownedSource.source
        val member = directory().members[entry.dataOffset]
            ?: throw ArchiveFormatException("ZIP: entry is not in the central directory", entry.dataOffset, entry.name)
        if (entry != member.entry) {
            throw ArchiveFormatException("ZIP: supplied entry metadata differs from the central directory", member.entry.dataOffset, member.entry.name)
        }
        val lh = source.readFully(entry.dataOffset, 30)
        val dataStart = validateLocalHeader(member, lh, source)
        if (entry.isEncrypted) {
            val field = if (member.method == 99) 8 else 6
            throw ArchiveUnsupportedException("ZIP encryption is not supported", entry.dataOffset + field, entry.name)
        }
        if (member.flags and 0x20 != 0) {
            throw ArchiveUnsupportedException("ZIP compressed patched data is not supported", entry.dataOffset + 6, entry.name)
        }

        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.compressedSize, entry.size)

        val method = member.method
        val codecId = ZipFormat.codecForMethod(method)
            ?: throw ArchiveUnsupportedException("ZIP method $method not supported", entry.dataOffset + 8, entry.name)
        val codec = Codecs.byId(codecId)
            ?: throw ArchiveUnsupportedException("ZIP method $method ($codecId) not implemented yet", entry.dataOffset + 8, entry.name)
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

    private fun validateLocalHeader(member: Member, local: ByteArray, source: RandomAccessSource): Long =
        withEntryContext(member.entry.name, member.entry.dataOffset) {
            val entry = member.entry
            val offset = entry.dataOffset
            if (ZipFormat.u32(local, 0) != ZipFormat.LOC) {
                throw ArchiveFormatException("ZIP: invalid local file header", offset, entry.name)
            }
            val flags = ZipFormat.u16(local, 6)
            if ((flags or member.flags) and 0x2000 != 0) {
                throw ArchiveUnsupportedException("ZIP masked local headers with central directory encryption are not supported", offset + 6, entry.name)
            }
            if (flags != member.flags) {
                throw ArchiveFormatException("ZIP: local flags differ from the central directory", offset + 6, entry.name)
            }
            if (ZipFormat.u16(local, 8) != member.method) {
                throw ArchiveFormatException("ZIP: local method differs from the central directory", offset + 8, entry.name)
            }
            val nameLength = ZipFormat.u16(local, 26)
            if (nameLength != member.nameBytes.size) {
                throw ArchiveFormatException("ZIP: local filename length differs from the central directory", offset + 26, entry.name)
            }
            val extraLength = ZipFormat.u16(local, 28)
            if (extraLength != member.localExtraLength) {
                throw ArchiveFormatException("ZIP: local extra length changed since directory validation", offset + 28, entry.name)
            }
            source.requireRange(offset + 30, (nameLength + extraLength).toLong())
            if (!source.readFully(offset + 30, nameLength).contentEquals(member.nameBytes)) {
                throw ArchiveFormatException("ZIP: local filename differs from the central directory", offset + 30, entry.name)
            }
            val dataStart = offset + 30 + nameLength + extraLength
            source.requireRange(dataStart, entry.compressedSize)
            dataStart
        }

    private fun validateMemberExtents(members: Collection<Member>, directoryOffset: Long) {
        val ordered = members.sortedBy { it.entry.dataOffset }
        for (index in ordered.indices) {
            val member = ordered[index]
            val next = ordered.getOrNull(index + 1)
            val boundary = next?.entry?.dataOffset ?: directoryOffset
            // validateLocalHeader bounded the fixed header, variable fields and
            // compressed range against the source; their sum cannot overflow.
            val end = member.entry.dataOffset + 30 + member.nameBytes.size +
                member.localExtraLength + member.entry.compressedSize
            if (end > boundary) {
                val target = if (next == null) "central directory" else "next local header"
                throw ArchiveFormatException("ZIP: entry extent ending at $end overlaps $target at $boundary", boundary, member.entry.name)
            }
        }
    }

    private fun parseCentralDirectory(): Directory {
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
        val members = HashMap<Long, Member>()
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
            val flags = ZipFormat.u16(cd, p + 8)
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
            val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
            val name = nameBytes.decodeToString()

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
            if (localOffset > cdOffset || cdOffset - localOffset < 30) {
                throw ArchiveFormatException("ZIP: local header overlaps the central directory", cdOffset, name)
            }
            val local = source.readFully(localOffset, 30)
            if (ZipFormat.u32(local, 0) != ZipFormat.LOC) {
                throw ArchiveFormatException("ZIP: invalid local file header", localOffset, name)
            }
            if (members.containsKey(localOffset)) {
                throw DecompressionBombException("ZIP: multiple entries reference local header offset $localOffset", localOffset, name, null)
            }

            val isDir = name.endsWith("/")
            val entry = ArchiveEntry(
                name = name,
                size = uncompSize,
                isDirectory = isDir,
                compressedSize = compSize,
                codec = ZipFormat.codecForMethod(method) ?: CodecId.STORE,
                isEncrypted = flags and 0x41 != 0 || method == 99,
                crc32 = crc,
                dataOffset = localOffset, // local header offset; resolved to data in read()
            )
            val member = Member(entry, nameBytes, method, flags, ZipFormat.u16(local, 28))
            validateLocalHeader(member, local, source)
            members[localOffset] = member
            entries.add(entry)
            p += 46 + nameLen + extraLen + commentLen
            n++
        }
        val countForComparison = if (hasZip64) n else n and 0xFFFFL
        if (countForComparison != cdEntries) {
            throw ArchiveFormatException("ZIP: central directory count $n differs from declared count $cdEntries", cdOffset)
        }
        validateMemberExtents(members.values, cdOffset)
        return Directory(entries, members)
    }
}
