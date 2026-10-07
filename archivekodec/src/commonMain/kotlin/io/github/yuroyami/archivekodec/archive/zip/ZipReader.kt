package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.ArchiveFormatException
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
 * the stored CRC-32. This reader returns entry names raw. Run them through
 * [io.github.yuroyami.archivekodec.security.PathGuard] before writing to disk.
 */
class ZipReader(private val source: RandomAccessSource) : ArchiveReader {

    override val format = ArchiveFormat.ZIP

    private val cached: List<ArchiveEntry> by lazy { parseCentralDirectory() }

    override fun entries(): List<ArchiveEntry> = cached

    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        if (entry.isDirectory) return ByteArray(0)
        val lh = source.readFully(entry.dataOffset, 30)
        if (ZipFormat.u32(lh, 0) != ZipFormat.LOC) error("bad local file header at ${entry.dataOffset}")
        val method = ZipFormat.u16(lh, 8)
        val nameLen = ZipFormat.u16(lh, 26)
        val extraLen = ZipFormat.u16(lh, 28)
        source.requireRange(entry.dataOffset + 30, (nameLen + extraLen).toLong())
        val dataStart = entry.dataOffset + 30 + nameLen + extraLen

        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.compressedSize, entry.size)

        val comp = source.readFullyChecked(dataStart, entry.compressedSize)
        val codecId = ZipFormat.codecForMethod(method)
            ?: throw UnsupportedOperationException("ZIP method $method not supported")
        val codec = Codecs.byId(codecId)
            ?: throw UnsupportedOperationException("ZIP method $method ($codecId) not implemented yet")
        val out = codec.decompress(comp, limits)

        entry.crc32?.let { expected ->
            if (Crc32.of(out) != expected) {
                throw ArchiveFormatException("ZIP: CRC-32 mismatch", dataStart, entry.name)
            }
        }
        return out
    }

    private fun parseCentralDirectory(): List<ArchiveEntry> {
        val size = source.size
        if (size < 22) error("not a ZIP file (too small)")

        // Locate EOCD by scanning back over the last 22 + max-comment(65535) bytes.
        val scan = minOf(size, (22 + 0xFFFF).toLong()).toInt()
        val tail = source.readFully(size - scan, scan)
        var eocd = -1
        for (i in scan - 22 downTo 0) {
            if (ZipFormat.u32(tail, i) == ZipFormat.EOCD) { eocd = i; break }
        }
        if (eocd < 0) error("not a ZIP file (no end-of-central-directory)")

        var cdEntries = ZipFormat.u16(tail, eocd + 10).toLong()
        var cdSize = ZipFormat.u32(tail, eocd + 12)
        var cdOffset = ZipFormat.u32(tail, eocd + 16)

        // ZIP64: any sentinel field means read the real values from the ZIP64 EOCD.
        if (cdEntries == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val locPos = size - scan + eocd - 20
            val locator = source.readFully(locPos, 20)
            if (ZipFormat.u32(locator, 0) != ZipFormat.Z64_LOC) {
                throw ArchiveFormatException("ZIP: missing ZIP64 locator", locPos)
            }
            val z64Off = ZipFormat.u64(locator, 8)
            val z64 = source.readFully(z64Off, 56)
            if (ZipFormat.u32(z64, 0) != ZipFormat.Z64_EOCD) {
                throw ArchiveFormatException("ZIP: invalid ZIP64 end record", z64Off)
            }
            cdEntries = ZipFormat.u64(z64, 32)
            cdSize = ZipFormat.u64(z64, 40)
            cdOffset = ZipFormat.u64(z64, 48)
        }
        source.requireRange(cdOffset, cdSize)
        if (cdEntries < 0 || cdEntries > Int.MAX_VALUE || cdEntries > cdSize / 46) {
            throw ArchiveFormatException("ZIP: invalid directory count $cdEntries for $cdSize bytes", cdOffset)
        }

        val cd = source.readFullyChecked(cdOffset, cdSize)
        val entries = ArrayList<ArchiveEntry>()
        val seenOffsets = HashSet<Long>()
        var p = 0
        var n = 0L
        while (n < cdEntries) {
            if (p > cd.size - 46) {
                throw ArchiveFormatException("ZIP: truncated central directory at entry $n of $cdEntries", cdOffset + p)
            }
            if (ZipFormat.u32(cd, p) != ZipFormat.CEN) error("bad central directory signature")
            val method = ZipFormat.u16(cd, p + 10)
            val crc = ZipFormat.u32(cd, p + 16)
            var compSize = ZipFormat.u32(cd, p + 20)
            var uncompSize = ZipFormat.u32(cd, p + 24)
            val nameLen = ZipFormat.u16(cd, p + 28)
            val extraLen = ZipFormat.u16(cd, p + 30)
            val commentLen = ZipFormat.u16(cd, p + 32)
            var localOffset = ZipFormat.u32(cd, p + 42)
            val recordSize = 46 + nameLen + extraLen + commentLen
            if (recordSize > cd.size - p) {
                throw ArchiveFormatException("ZIP: central directory fields exceed record at entry $n", cdOffset + p)
            }
            val name = cd.decodeToString(p + 46, p + 46 + nameLen)

            if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                var ep = p + 46 + nameLen
                val extraEnd = ep + extraLen
                while (ep + 4 <= extraEnd) {
                    val id = ZipFormat.u16(cd, ep)
                    val sz = ZipFormat.u16(cd, ep + 2)
                    var dp = ep + 4
                    if (sz > extraEnd - dp) throw ArchiveFormatException("ZIP: truncated extra field", cdOffset + ep, name)
                    if (id == 0x0001) { // ZIP64 extended info: fields present in this order
                        val needed = (if (uncompSize == 0xFFFFFFFFL) 8 else 0) +
                            (if (compSize == 0xFFFFFFFFL) 8 else 0) + (if (localOffset == 0xFFFFFFFFL) 8 else 0)
                        if (sz < needed) throw ArchiveFormatException("ZIP: truncated ZIP64 entry fields", cdOffset + dp, name)
                        if (uncompSize == 0xFFFFFFFFL) { uncompSize = ZipFormat.u64(cd, dp); dp += 8 }
                        if (compSize == 0xFFFFFFFFL) { compSize = ZipFormat.u64(cd, dp); dp += 8 }
                        if (localOffset == 0xFFFFFFFFL) { localOffset = ZipFormat.u64(cd, dp); dp += 8 }
                    }
                    ep += 4 + sz
                }
            }

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
        return entries
    }
}
