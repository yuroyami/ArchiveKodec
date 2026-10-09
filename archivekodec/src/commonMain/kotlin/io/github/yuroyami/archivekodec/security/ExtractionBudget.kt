package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.ArchiveException

/**
 * An archive-wide output budget that a caller shares across reads, so a loop over every
 * entry cannot produce more than [maxBytes] in total. [ArchiveLimits.maxUncompressedBytes]
 * still caps each call on its own; the budget caps their sum.
 *
 * Pass it to [io.github.yuroyami.archivekodec.archive.ArchiveReader.read]: each read decodes
 * with its output ceiling lowered to [remainingBytes], so the decoder stops as soon as the
 * budget would be passed rather than after it finishes, and then charges what it produced.
 * A failed read charges nothing.
 *
 * Readers keep no running total, so one reader can serve several budgets. A budget itself
 * is mutable and meant for a single extraction on one thread; reads that share a budget
 * across threads must be serialized externally.
 */
public class ExtractionBudget public constructor(public val maxBytes: Long) {
    init {
        require(maxBytes >= 0) { "extraction budget: maxBytes must be nonnegative" }
    }

    /** Bytes charged so far. */
    public var usedBytes: Long = 0
        private set

    /** Bytes that may still be charged. */
    public val remainingBytes: Long get() = maxBytes - usedBytes

    /**
     * Record [bytes] of produced output.
     * @throws DecompressionBombException if the total would pass [maxBytes]; nothing is charged then.
     * @throws IllegalArgumentException if [bytes] is negative.
     */
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    public fun charge(bytes: Long) {
        require(bytes >= 0) { "extraction budget: charged bytes must be nonnegative" }
        if (bytes > remainingBytes) {
            throw DecompressionBombException(
                "archive output of $bytes B exceeds the $remainingBytes B left of budget $maxBytes B",
            )
        }
        usedBytes += bytes
    }

    /** Describe the budget and its use. */
    override fun toString(): String = "ExtractionBudget(maxBytes=$maxBytes, usedBytes=$usedBytes)"
}
