package io.github.yuroyami.archivekodec.checksum

/**
 * A streaming, unkeyed integrity check (CRC / Adler family), implemented in pure
 * Kotlin. These checks detect accidental corruption; they do not authenticate data
 * or provide cryptographic integrity.
 *
 * [value] returns the result in the low bits of a `Long` (32-bit checksums fit in the
 * low 32 bits; CRC-64 uses the full width).
 *
 * Instances hold mutable state and are not thread-safe. Use one instance per
 * thread, or externally serialize all calls to [update], [value] and [reset].
 */
public interface Checksum {
    /**
     * Feed a slice of bytes into the running checksum. Omitting [length] selects
     * the suffix starting at [offset]. An empty slice at the end is valid.
     *
     * @throws IllegalArgumentException if the slice is outside [data]. Validation
     * happens before processing bytes, so a rejected update leaves the state unchanged.
     */
    @Throws(IllegalArgumentException::class)
    public fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset)

    /** Current checksum value (low bits of the Long). */
    public fun value(): Long

    /** Reset to the initial state so the instance can be reused. */
    public fun reset()
}

/** Validate with subtraction before computing an end that could otherwise overflow. */
internal fun checkedChecksumEnd(size: Int, offset: Int, length: Int): Int {
    require(offset >= 0 && length >= 0 && offset <= size && length <= size - offset) {
        "invalid checksum slice: offset $offset, length $length, size $size"
    }
    return offset + length
}
