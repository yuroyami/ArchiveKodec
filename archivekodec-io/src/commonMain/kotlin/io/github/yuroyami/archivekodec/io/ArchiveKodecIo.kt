package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlinx.io.IOException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

/**
 * kotlinx-io convenience adapters for ArchiveKodec: drain a `RawSource` to a
 * `ByteArray`, wrap one as an in-memory [RandomAccessSource], and run a [Codec] over
 * a source. They live in a separate artifact so the kotlinx-io dependency never
 * reaches the core or consumers who only want the codecs.
 *
 * There is no `RawSink` bridge and no seekable file handle. Every function here
 * reads its source fully into memory.
 *
 * Filesystem access (Android SAF, the iOS sandbox) stays the consumer's concern.
 * These helpers operate on whatever source the app already holds.
 */

/**
 * Drain a [RawSource] fully into a `ByteArray` from its current position.
 * The caller retains ownership and must close it, including when reading fails.
 * This function does not synchronize access to the source.
 */
@Throws(ArchiveException::class)
public fun RawSource.readAllBytes(): ByteArray {
    val buffer = Buffer()
    try {
        while (readAtMostTo(buffer, CHUNK) != -1L) { /* keep pulling until exhausted */ }
        return buffer.readByteArray()
    } catch (error: IOException) {
        throw ArchiveIOException("source read failed: ${error.message}", error)
    }
}

/**
 * Read the remaining bytes of a [RawSource] into memory and wrap them as a seekable source.
 * The caller retains ownership of the original source and must close it on success
 * or failure. Closing the returned snapshot does not close the original source.
 * This function does not synchronize access to the original source.
 */
@Throws(ArchiveException::class)
public fun RawSource.toRandomAccessSource(): RandomAccessSource =
    ByteArrayRandomAccessSource(readAllBytes())

/**
 * Compress all bytes drained from [source]'s current position.
 * The caller owns [source] and must close it on success or failure.
 * This function does not synchronize access to [source].
 */
@Throws(ArchiveException::class)
public fun Codec.compress(source: RawSource): ByteArray = compress(source.readAllBytes())

/**
 * Decompress all bytes drained from [source]'s current position, enforcing [limits].
 * The caller owns [source] and must close it on success or failure.
 * This function does not synchronize access to [source].
 */
@Throws(ArchiveException::class)
public fun Codec.decompress(
    source: RawSource,
    limits: ArchiveLimits = ArchiveLimits.DEFAULT,
): ByteArray = decompress(source.readAllBytes(), limits)

private const val CHUNK = 64L * 1024
