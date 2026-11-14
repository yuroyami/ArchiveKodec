package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.ArchiveException

/**
 * What an [Inflate.inflate] or
 * [Zlib.decompress][io.github.yuroyami.archivekodec.wrapper.Zlib.decompress] call with
 * [InflateOptions] decoded, and how much of the input the stream took.
 */
public class InflateResult internal constructor(
    /** The decoded bytes. With [error], the bytes decoded before the damage. The caller owns the array. */
    public val output: ByteArray,
    /**
     * Bytes of the input range that the stream takes: a zlib header and trailer included,
     * [trailing data][InflateOptions.allowTrailingData] excluded. With [error], the bytes
     * read before decoding stopped.
     */
    public val inputConsumed: Int,
    /**
     * Null for a complete, valid stream. Otherwise the failure that stopped the decode; only
     * [InflateOptions.keepPartialOutput] makes it non-null, since without it the failure is thrown.
     */
    public val error: ArchiveException?,
) {
    /** True when the stream was complete and valid, so [output] is the whole output. */
    public val isComplete: Boolean get() = error == null

    /** Describe the sizes and the failure, not the bytes. */
    override fun toString(): String = "InflateResult(output=${output.size} B, inputConsumed=$inputConsumed, error=$error)"
}
