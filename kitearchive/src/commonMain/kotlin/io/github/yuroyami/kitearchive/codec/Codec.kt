package io.github.yuroyami.kitearchive.codec

import io.github.yuroyami.kitearchive.security.ArchiveLimits

/**
 * A single compression codec: a reversible byte transform. v0 is buffered
 * (whole-array in / whole-array out); the streaming `Source`/`Sink` form is the
 * planned API for large payloads (see PORTING_STATUS.md). [decompress] takes an
 * [ArchiveLimits] so the decompression-bomb guard is part of the contract, not an
 * afterthought.
 */
interface Codec {
    /** Which codec this is. */
    val id: CodecId

    /** Compress [data] fully in memory. */
    fun compress(data: ByteArray): ByteArray

    /** Decompress [data] fully in memory, enforcing [limits]. */
    fun decompress(data: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray
}
