package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * A single compression codec: a reversible byte transform. Every codec is buffered,
 * so it takes a whole array and returns a whole array. [decompress] takes an
 * [ArchiveLimits], so the decompression-bomb guard is part of the contract rather
 * than an option a caller can forget.
 */
public interface Codec {
    /** Which codec this is. */
    public val id: CodecId

    /** Compress [data] fully in memory. */
    @Throws(ArchiveException::class)
    public fun compress(data: ByteArray): ByteArray

    /** Decompress [data] fully in memory, enforcing [limits]. */
    @Throws(ArchiveException::class)
    public fun decompress(data: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray
}
