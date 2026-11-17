package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.util.readI64Le
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le

/** End-record framing shared by the reader and the unknown-header opening fallback. */
internal object ZipEndRecord {
    data class Record(val offset: Long, val bytes: ByteArray)

    fun find(source: RandomAccessSource): Record? {
        val size = source.size
        if (size < 0) throw ArchiveFormatException("negative source size $size", 0)
        if (size < 22) return null
        // Most archives have no comment, so the record is the last 22 bytes. Checking those first
        // keeps the search from reading 64 KiB of a slow or damaged source.
        val last = source.readFully(size - 22, 22)
        if (readU32Le(last, 0) == ZipFormat.EOCD && readU16Le(last, 20) == 0) {
            val record = Record(size - 22, last)
            if (coherent(source, record)) return record
        }
        val count = minOf(size, 65557L).toInt()
        val start = size - count
        val tail = source.readFully(start, count)
        var first: Record? = null
        for (i in count - 22 downTo 0) {
            if (readU32Le(tail, i) == ZipFormat.EOCD && readU16Le(tail, i + 20) == count - i - 22) {
                val record = Record(start + i, tail.copyOfRange(i, i + 22))
                // A comment can hold bytes that frame like an end record. Prefer the record whose
                // directory checks out, and fall back to the last one in the file.
                if (coherent(source, record)) return record
                if (first == null) first = record
            }
        }
        return first
    }

    /**
     * True when a ZIP64 locator sits right before [record], or when the record names entries and
     * its directory, ending at the record, starts with a central directory header.
     */
    private fun coherent(source: RandomAccessSource, record: Record): Boolean {
        val at = record.offset
        if (at >= 20 && readU32Le(source.readFully(at - 20, 4), 0) == ZipFormat.Z64_LOC) return true
        val entries = readU16Le(record.bytes, 10)
        val directorySize = readU32Le(record.bytes, 12)
        if (entries == 0 || directorySize < 46 || directorySize > at) return false
        return hasDirectorySignature(source, at - directorySize, directorySize)
    }

    fun shift(value: Long, base: Long, fieldOffset: Long, name: String? = null): Long {
        if (value < 0 || base < 0 || value > Long.MAX_VALUE - base) {
            throw ArchiveFormatException("ZIP: relative offset $value with prefix $base exceeds source positions", fieldOffset, name)
        }
        return value + base
    }

    /** Prefer the contiguous end-derived directory, retaining already supported absolute gaps. */
    fun classicDirectory(source: RandomAccessSource, stored: Long, size: Long, end: Long): Long {
        val inferred = end - size // The caller has bounded the size and stored offset against end.
        if (inferred == stored || size == 0L || hasDirectorySignature(source, inferred, size)) return inferred
        return if (hasDirectorySignature(source, stored, size)) stored else inferred
    }

    private fun hasDirectorySignature(source: RandomAccessSource, offset: Long, size: Long): Boolean {
        if (size < 4) return false
        val signature = readU32Le(source.readFully(offset, 4), 0)
        return signature == ZipFormat.CEN || signature == ZipFormat.CEN_SIGNATURE
    }

    /**
     * The locator supplies a ZIP-relative lower bound. Search backwards in fixed
     * chunks for a record whose declared length ends at the physical locator.
     * A 55-byte overlap preserves complete headers at every chunk boundary.
     */
    fun zip64(source: RandomAccessSource, stored: Long, locator: Long): Record {
        if (stored < 0 || locator < 56 || stored > locator - 56) {
            throw ArchiveFormatException("ZIP: ZIP64 end record exceeds locator boundary (relative offset $stored)", locator + 8)
        }
        val direct = source.readFully(stored, 56)
        val directSignature = readU32Le(direct, 0) == ZipFormat.Z64_EOCD
        val directSize = readI64Le(direct, 4)
        val directFits = directSignature && directSize >= 44 && directSize <= locator - stored - 12
        if (directFits && directSize == locator - stored - 12) return Record(stored, direct)

        var fallback: Record? = null
        var end = locator - 56 + 1
        while (end > stored) {
            val start = maxOf(stored, end - 8192)
            val count = (end - start).toInt()
            val chunk = source.readFully(start, count + 55)
            for (i in count - 1 downTo 0) {
                if (readU32Le(chunk, i) != ZipFormat.Z64_EOCD) continue
                val position = start + i
                val length = readI64Le(chunk, i + 4)
                val record = Record(position, chunk.copyOfRange(i, i + 56))
                val remaining = locator - position - 12
                if (length != remaining) {
                    // A coherent directory also anchors a broken size field.
                    // Report it without searching through a potentially huge prefix.
                    if ((length < 44 || length > remaining) && coherentDirectory(source, record, stored)) return record
                    continue
                }
                // An extensible sector may itself contain fully framed signature
                // bytes. Its relative directory must identify real directory data.
                if (coherentDirectory(source, record, stored)) return record
                if (fallback == null) fallback = record
            }
            end = start
        }
        // Preserve absolute end records followed by a gap. A malformed candidate
        // is returned to the reader for the usual field-specific diagnostics.
        if (directFits) return Record(stored, direct)
        fallback?.let { return it }
        if (directSignature) throw ArchiveFormatException("ZIP: invalid ZIP64 end record size $directSize", stored + 4)
        throw ArchiveFormatException("ZIP: invalid ZIP64 end record", stored)
    }

    private fun coherentDirectory(source: RandomAccessSource, record: Record, relativeEnd: Long): Boolean {
        val size = readI64Le(record.bytes, 40)
        val offset = readI64Le(record.bytes, 48)
        if (size < 0 || offset < 0 || offset > relativeEnd || size > relativeEnd - offset) return false
        if (size == 0L) return readI64Le(record.bytes, 32) == 0L
        val physical = record.offset - relativeEnd + offset // Bounded above by record.offset.
        return hasDirectorySignature(source, physical, size)
    }
}
