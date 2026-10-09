@file:JvmName("ArchiveKodecIo")

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
import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads

/**
 * kotlinx-io convenience adapters for ArchiveKodec: drain a `RawSource` to a
 * `ByteArray`, wrap one as an in-memory [RandomAccessSource], and run a [Codec] over
 * a source. They live in a separate artifact so the kotlinx-io dependency never
 * reaches the core or consumers who only want the codecs.
 *
 * There is no `RawSink` bridge. Every function here reads its source fully into
 * memory; for a seekable file, use [FileRandomAccessSource].
 *
 * Filesystem access (Android SAF, the iOS sandbox) stays the consumer's concern.
 * These helpers operate on whatever source the app already holds.
 */

/**
 * Drain a [RawSource] fully into a `ByteArray` from its current position.
 * Java calls `ArchiveKodecIo.readAllBytes(source)`.
 * The caller retains ownership and must close it, including when reading fails.
 * This function does not synchronize access to the source.
 *
 * @throws ArchiveIOException if the source reports an IO failure.
 * @throws IllegalStateException if the source is closed or the buffered byte count
 *   reaches kotlinx-io's ByteArray ceiling (`Int.MAX_VALUE`).
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
 *
 * @throws ArchiveIOException if the source reports an IO failure.
 * @throws IllegalStateException if the source is closed or the remaining bytes
 *   reach kotlinx-io's ByteArray ceiling (`Int.MAX_VALUE`).
 */
@Throws(ArchiveException::class)
public fun RawSource.toRandomAccessSource(): RandomAccessSource =
    ByteArrayRandomAccessSource(readAllBytes())

/**
 * Compress all bytes drained from [source]'s current position.
 * The caller owns [source] and must close it on success or failure.
 * This function does not synchronize access to [source].
 *
 * @throws ArchiveIOException if the source reports an IO failure.
 * @throws IllegalStateException if the source is closed or the input cannot be
 *   materialized by [readAllBytes].
 * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if compressed output cannot fit in one ByteArray.
 * @throws ArchiveException if a custom codec reports another archive failure.
 */
@Throws(ArchiveException::class)
public fun Codec.compress(source: RawSource): ByteArray = compress(source.readAllBytes())

/**
 * Decompress all bytes drained from [source]'s current position, enforcing [limits].
 * Java calls `ArchiveKodecIo.decompress(codec, source)` with default limits, or
 * supplies an explicit policy as the third argument.
 * The caller owns [source] and must close it on success or failure.
 * This function does not synchronize access to [source].
 *
 * @throws ArchiveIOException if the source reports an IO failure.
 * @throws IllegalStateException if the source is closed or the input cannot be
 *   materialized by [readAllBytes].
 * @throws io.github.yuroyami.archivekodec.ArchiveFormatException for malformed framing, invalid sizes or failed integrity checks.
 * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed DEFLATE data.
 * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException for an unsupported format feature or dictionary.
 * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if decoding exceeds [limits].
 * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if decoded output cannot fit in one ByteArray.
 * @throws ArchiveException if a custom codec reports another archive failure.
 */
@Throws(ArchiveException::class)
@JvmOverloads
public fun Codec.decompress(
    source: RawSource,
    limits: ArchiveLimits = ArchiveLimits.DEFAULT,
): ByteArray = decompress(source.readAllBytes(), limits)

private const val CHUNK = 64L * 1024
