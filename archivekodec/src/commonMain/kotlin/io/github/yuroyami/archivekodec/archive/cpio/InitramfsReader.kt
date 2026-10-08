package io.github.yuroyami.archivekodec.archive.cpio

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * Browse an entire uncompressed Linux initramfs buffer of newc/crc records.
 * Unlike [CpioReader], continues after each `TRAILER!!!`, resets hard-link state
 * there and exposes the resulting [segments]. Zero padding is allowed before,
 * between and after records; raw headers must start at absolute four-byte offsets.
 * A final segment may omit its trailer. Empty and all-zero buffers have no segments.
 *
 * Listing reads only metadata. It either validates the complete buffer or throws;
 * a malformed or unsupported later segment never returns a partial listing.
 * Compressed segments (even gzip) and odc/binary cpio are unsupported. Failures
 * report absolute source offsets. [limits] bounds entries across the entire buffer
 * and segments independently, including empty segments. Reads retain CPIO's
 * hard-link resolution and crc byte-sum verification, with per-call output limits.
 *
 * Owns the supplied source; close this reader even after failure. Not thread-safe:
 * externally serialize all access, including [close]. Returned lists are read-only
 * cached metadata. No files are extracted and no filesystem overlay is applied;
 * duplicate names remain in source order. The format sniffer and general open
 * factory still select the single-archive [CpioReader]; select this reader explicitly.
 */
public class InitramfsReader(
    source: RandomAccessSource,
    nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
) : ArchiveReader {
    public constructor(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder) : this(source, nameDecoder, ArchiveLimits.DEFAULT)
    public constructor(source: RandomAccessSource) : this(source, ArchiveNameDecoder.UTF8)
    public constructor(source: RandomAccessSource, limits: ArchiveLimits) : this(source, ArchiveNameDecoder.UTF8, limits)

    private val delegate = CpioReader.initramfs(source, nameDecoder, limits)

    override val format: ArchiveFormat = ArchiveFormat.CPIO

    /**
     * Describe every segment, excluding surrounding zero padding from its bounds.
     * Entry offsets refer to the original source, not to a segment-relative view.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveClosedException if closed.
     * @throws io.github.yuroyami.archivekodec.ArchiveFormatException for invalid metadata, padding alignment or filename encoding.
     * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException for a compressed segment or unsupported CPIO dialect.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if the entry or segment count exceeds [limits].
     * @throws ArchiveException if the source or custom filename decoder reports another failure.
     */
    @Throws(ArchiveException::class)
    public fun segments(): List<InitramfsSegment> = delegate.segments()

    /**
     * Flatten all [segments] in source order, with the same complete-buffer validation.
     * @throws ArchiveException for any listing failure documented by [segments], including the segment limit.
     */
    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = delegate.entries()

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray = delegate.read(entry, limits)

    @Throws(ArchiveException::class)
    override fun close(): Unit = delegate.close()

    /** Describe the owned reader without parsing metadata or exposing payloads. */
    override fun toString(): String = "InitramfsReader($delegate)"
}

/** Immutable metadata for a sequence of newc/crc records, delimited by a trailer or EOF. */
public class InitramfsSegment internal constructor(
    /** Absolute offset of the first header, including a trailer-only segment's header. */
    public val startOffset: Long,
    /** Exclusive end of the final aligned record, including its trailer when present. */
    public val endOffset: Long,
    /** Whether `TRAILER!!!` ended this segment; false for a final unterminated segment. */
    public val hasTrailer: Boolean,
    /** Read-only members in source order, excluding the trailer. Use the owning reader to read them. */
    public val entries: List<ArchiveEntry>,
) {
    /** Bounded description of offsets and count, without listing names or payloads. */
    override fun toString(): String = "InitramfsSegment(startOffset=$startOffset, endOffset=$endOffset, " +
        "hasTrailer=$hasTrailer, entries=${entries.size})"
}
