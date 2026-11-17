package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException

/**
 * The output of one decode, as LZW, PackBits and PalmDOC return it.
 *
 * Without partial output, a decode either succeeds or throws, so [error] is null. With partial
 * output, damaged input gives the bytes decoded before the damage, and [error] holds the failure.
 */
public class DecodeResult internal constructor(
    /** The decoded bytes. */
    public val output: ByteArray,
    /** The input bytes the decode read, counted from the start of its range. */
    public val inputConsumed: Int,
    /** The failure that ended a partial decode, or null when the input decoded whole. */
    public val error: ArchiveException?,
) {
    /** True when the input decoded with no error. */
    public val isComplete: Boolean get() = error == null

    override fun toString(): String = "DecodeResult(output=${output.size} B, inputConsumed=$inputConsumed, error=$error)"
}
