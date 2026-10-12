package io.github.yuroyami.archivekodec.archive.tar

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.readFullyChecked
import kotlin.text.CharacterCodingException

/**
 * pax extended-header records (POSIX.1-2001 `length key=value\n`). [values] holds the
 * last value of each keyword; the repeated GNU sparse 0.0 keywords keep their order.
 */
internal class PaxRecords(
    val values: Map<String, ByteArray>,
    val sparseOffsets: List<String> = emptyList(),
    val sparseSizes: List<String> = emptyList(),
) {
    fun text(key: String, offset: Long): String? = values[key]?.let { decodeUtf8(it, key, offset) }

    /** Global records overridden by this local header; an empty value removes a keyword. */
    fun under(global: PaxRecords): PaxRecords {
        if (global.values.isEmpty()) return this
        val merged = LinkedHashMap(global.values)
        for ((key, value) in values) merged[key] = value
        return PaxRecords(merged, sparseOffsets, sparseSizes)
    }

    companion object {
        val EMPTY = PaxRecords(emptyMap())

        /** Largest extended header read into memory; real headers are a few hundred bytes. */
        const val MAX_BYTES = 16L * 1024 * 1024

        fun parse(source: RandomAccessSource, offset: Long, length: Long): PaxRecords {
            if (length > MAX_BYTES) {
                throw ArchiveUnsupportedException("TAR: pax extended header of $length bytes exceeds $MAX_BYTES bytes", offset)
            }
            val bytes = source.readFullyChecked(offset, length)
            val values = LinkedHashMap<String, ByteArray>()
            val offsets = ArrayList<String>()
            val sizes = ArrayList<String>()
            var at = 0
            while (at < bytes.size) {
                // Trailing NUL padding after the last record is tolerated.
                if (bytes[at].toInt() == 0 && (at until bytes.size).all { bytes[it].toInt() == 0 }) break
                var digitsEnd = at
                var recordLength = 0L
                while (digitsEnd < bytes.size && bytes[digitsEnd].toInt() in '0'.code..'9'.code) {
                    recordLength = recordLength * 10 + (bytes[digitsEnd] - '0'.code.toByte())
                    if (recordLength > bytes.size) break
                    digitsEnd++
                }
                val end = at + recordLength
                if (digitsEnd == at || digitsEnd >= bytes.size || bytes[digitsEnd].toInt() != ' '.code ||
                    end > bytes.size || end <= digitsEnd + 1 || bytes[(end - 1).toInt()].toInt() != '\n'.code
                ) {
                    throw ArchiveFormatException("TAR: malformed pax extended header record", offset + at)
                }
                val body = digitsEnd + 1
                var equals = body
                while (equals < end - 1 && bytes[equals].toInt() != '='.code) equals++
                if (equals == body || equals >= end - 1) {
                    throw ArchiveFormatException("TAR: pax record has no keyword", offset + at)
                }
                val key = decodeUtf8(bytes.copyOfRange(body, equals), "keyword", offset + body)
                val value = bytes.copyOfRange(equals + 1, (end - 1).toInt())
                when (key) {
                    "GNU.sparse.offset" -> offsets.add(decodeUtf8(value, key, offset + equals + 1))
                    "GNU.sparse.numbytes" -> sizes.add(decodeUtf8(value, key, offset + equals + 1))
                    else -> values[key] = value
                }
                at = end.toInt()
            }
            return PaxRecords(values, offsets, sizes)
        }

        private fun decodeUtf8(bytes: ByteArray, key: String, offset: Long): String = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw ArchiveFormatException("TAR: pax value for '$key' is not UTF-8", offset)
        }
    }
}

/** Parse a pax decimal integer such as `size`. */
internal fun paxNumber(text: String, key: String, offset: Long): Long {
    if (text.isEmpty() || text.any { it !in '0'..'9' }) {
        throw ArchiveFormatException("TAR: pax $key '$text' is not a nonnegative decimal", offset)
    }
    return text.toLongOrNull() ?: throw ArchiveFormatException("TAR: pax $key '$text' overflows Long", offset)
}

/** Parse a pax time such as `1700000000.123456`, rounded down to whole seconds. */
internal fun paxSeconds(text: String, offset: Long): Long {
    val negative = text.startsWith("-")
    val digits = if (negative) text.substring(1) else text
    val point = digits.indexOf('.')
    val whole = if (point < 0) digits else digits.substring(0, point)
    val fraction = if (point < 0) "" else digits.substring(point + 1)
    if (whole.isEmpty() || (whole + fraction).any { it !in '0'..'9' }) {
        throw ArchiveFormatException("TAR: pax mtime '$text' is not a decimal time", offset)
    }
    val seconds = whole.toLongOrNull() ?: throw ArchiveFormatException("TAR: pax mtime '$text' overflows Long", offset)
    return if (!negative) seconds else if (fraction.any { it != '0' }) -seconds - 1 else -seconds
}

/**
 * Where a sparse member's stored regions belong in its expanded content. Stored bytes are
 * consecutive at the entry's data offset; the gaps between regions read as zeros.
 */
internal class SparseMap(val realSize: Long, val offsets: LongArray, val lengths: LongArray) {
    val storedSize: Long get() = lengths.sum()

    companion object {
        /** Check ordering and bounds, so [TarReader] can expand the map without further checks. */
        fun of(realSize: Long, offsets: List<Long>, lengths: List<Long>, storedSize: Long, headerOffset: Long): SparseMap {
            if (offsets.size != lengths.size) {
                throw ArchiveFormatException("TAR: sparse map has ${offsets.size} offsets but ${lengths.size} sizes", headerOffset)
            }
            if (realSize < 0) throw ArchiveFormatException("TAR: negative sparse file size", headerOffset)
            var end = 0L
            var stored = 0L
            for (i in offsets.indices) {
                val start = offsets[i]
                val length = lengths[i]
                if (start < end || length < 0 || length > realSize - start) {
                    throw ArchiveFormatException("TAR: sparse region $i at $start of $length bytes is out of order or past $realSize", headerOffset)
                }
                end = start + length
                stored += length
            }
            if (stored != storedSize) {
                throw ArchiveFormatException("TAR: sparse regions hold $stored bytes but the member stores $storedSize", headerOffset)
            }
            return SparseMap(realSize, offsets.toLongArray(), lengths.toLongArray())
        }
    }
}
