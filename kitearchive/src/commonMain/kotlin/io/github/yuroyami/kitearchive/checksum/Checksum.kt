package io.github.yuroyami.kitearchive.checksum

/**
 * A streaming, unkeyed integrity check (CRC / Adler family). All implementations are
 * pure-Kotlin and table-driven — these are cheap (GB/s), export-safe, and need no
 * constant-time care, so they stay in the core forever (unlike AES, which the design
 * delegates to a platform crypto provider).
 *
 * [value] returns the result in the low bits of a `Long` (32-bit checksums fit in the
 * low 32 bits; CRC-64 uses the full width).
 */
interface Checksum {
    /** Feed a slice of bytes into the running checksum. */
    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size)

    /** Current checksum value (low bits of the Long). */
    fun value(): Long

    /** Reset to the initial state so the instance can be reused. */
    fun reset()
}
