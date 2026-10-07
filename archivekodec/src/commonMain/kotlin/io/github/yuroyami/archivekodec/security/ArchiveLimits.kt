package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.ArchiveException

/**
 * Decompression-bomb defences, applied **during** decode (never trust a declared
 * size for allocation). Passed into every `decompress`/`read` call; the defaults are
 * deliberately safe so a caller cannot forget them.
 *
 * See the classic 42.zip (42 KB → 4.5 PB) and Fifield's non-recursive overlapping
 * bombs (~10 MB → 281 TB in one pass). The only universal defence is a hard cap on
 * bytes written, which [checkOutputSize] enforces.
 */
data class ArchiveLimits(
    /** Hard ceiling on total uncompressed bytes produced. Default 2 GiB. */
    val maxUncompressedBytes: Long = 2L * 1024 * 1024 * 1024,
    /** Per-entry compression-ratio ceiling. Default 10000:1. */
    val maxCompressionRatio: Int = 10_000,
    /** Maximum number of entries in a container. Default 100k. */
    val maxEntries: Int = 100_000,
    /** Maximum auto-extract nesting depth (archive-in-archive). Default 8. */
    val maxNestingDepth: Int = 8,
) {
    /** @throws DecompressionBombException if [bytes] exceeds [maxUncompressedBytes]. */
    fun checkOutputSize(bytes: Long) {
        if (bytes > maxUncompressedBytes) {
            throw DecompressionBombException(
                "uncompressed output $bytes B exceeds limit $maxUncompressedBytes B",
            )
        }
    }

    /** @throws DecompressionBombException if the ratio exceeds [maxCompressionRatio]. */
    fun checkRatio(compressedBytes: Long, uncompressedBytes: Long) {
        if (compressedBytes <= 0) return
        val quotient = uncompressedBytes / compressedBytes
        val remainder = uncompressedBytes % compressedBytes
        if (quotient > maxCompressionRatio || (quotient == maxCompressionRatio.toLong() && remainder > 0)) {
            throw DecompressionBombException(
                "compression ratio $uncompressedBytes/$compressedBytes exceeds ${maxCompressionRatio}:1",
            )
        }
    }

    /** @throws DecompressionBombException if an entry count exceeds [maxEntries]. */
    fun checkEntryCount(count: Int) {
        if (count > maxEntries) {
            throw DecompressionBombException("entry count $count exceeds limit $maxEntries")
        }
    }

    companion object {
        /** The safe default applied when a caller doesn't pass its own. */
        val DEFAULT = ArchiveLimits()

        /** Opt-out for trusted input: every guard relaxed to its maximum. */
        val UNBOUNDED = ArchiveLimits(
            maxUncompressedBytes = Long.MAX_VALUE,
            maxCompressionRatio = Int.MAX_VALUE,
            maxEntries = Int.MAX_VALUE,
            maxNestingDepth = Int.MAX_VALUE,
        )
    }
}

/** Thrown when input trips a decompression-bomb guard. */
class DecompressionBombException(message: String) : ArchiveException(message)
