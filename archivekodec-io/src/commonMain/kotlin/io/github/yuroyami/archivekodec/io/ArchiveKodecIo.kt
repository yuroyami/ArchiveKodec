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

/** Drain a [RawSource] fully into a `ByteArray`. */
@Throws(ArchiveException::class)
fun RawSource.readAllBytes(): ByteArray {
    val buffer = Buffer()
    try {
        while (readAtMostTo(buffer, CHUNK) != -1L) { /* keep pulling until exhausted */ }
        return buffer.readByteArray()
    } catch (error: IOException) {
        throw ArchiveIOException("source read failed: ${error.message}", error)
    }
}

/** Read an entire [RawSource] into memory and wrap it as a seekable source. */
@Throws(ArchiveException::class)
fun RawSource.toRandomAccessSource(): RandomAccessSource =
    ByteArrayRandomAccessSource(readAllBytes())

/** Compress all bytes drained from [source]. */
@Throws(ArchiveException::class)
fun Codec.compress(source: RawSource): ByteArray = compress(source.readAllBytes())

/** Decompress all bytes drained from [source], enforcing [limits]. */
@Throws(ArchiveException::class)
fun Codec.decompress(
    source: RawSource,
    limits: ArchiveLimits = ArchiveLimits.DEFAULT,
): ByteArray = decompress(source.readAllBytes(), limits)

private const val CHUNK = 64L * 1024
