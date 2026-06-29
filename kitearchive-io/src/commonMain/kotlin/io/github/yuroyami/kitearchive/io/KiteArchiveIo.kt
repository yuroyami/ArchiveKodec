package io.github.yuroyami.kitearchive.io

import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.kitearchive.archive.RandomAccessSource
import io.github.yuroyami.kitearchive.codec.Codec
import io.github.yuroyami.kitearchive.security.ArchiveLimits
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

/**
 * kotlinx-io bridge for KiteArchive. Adapts kotlinx-io `RawSource`/`RawSink` and
 * file handles to the pure core's byte / [RandomAccessSource] contracts, so the
 * experimental kotlinx-io dependency never reaches the core or consumers who only
 * want the codecs.
 *
 * Filesystem access (Android SAF, the iOS sandbox) stays the consumer's concern —
 * these helpers operate on whatever Source/Sink the app already holds.
 */

/** Drain a [RawSource] fully into a `ByteArray`. */
fun RawSource.readAllBytes(): ByteArray {
    val buffer = Buffer()
    while (readAtMostTo(buffer, CHUNK) != -1L) { /* keep pulling until exhausted */ }
    return buffer.readByteArray()
}

/** Read an entire [RawSource] into memory and wrap it as a seekable source. */
fun RawSource.toRandomAccessSource(): RandomAccessSource =
    ByteArrayRandomAccessSource(readAllBytes())

/** Compress all bytes drained from [source]. */
fun Codec.compress(source: RawSource): ByteArray = compress(source.readAllBytes())

/** Decompress all bytes drained from [source], enforcing [limits]. */
fun Codec.decompress(
    source: RawSource,
    limits: ArchiveLimits = ArchiveLimits.DEFAULT,
): ByteArray = decompress(source.readAllBytes(), limits)

private const val CHUNK = 64L * 1024
