package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Read side of a container. [entries] lists members from the header alone and touches
 * no payload, which is "browse without extract". [read] materialises one entry's
 * uncompressed bytes under the bomb [ArchiveLimits].
 *
 * Built-in readers take ownership of their supplied [RandomAccessSource]. Close
 * the reader to release it, including after a listing or read failure. Readers
 * are not thread-safe, even with a thread-safe source: keep each reader on one
 * thread or externally serialize all operations, including [close].
 */
public interface ArchiveReader {
    /** The container format this reader handles. */
    public val format: ArchiveFormat

    /** Policy for listing and the default for each read. Custom readers default to [ArchiveLimits.DEFAULT]. */
    public val limits: ArchiveLimits get() = ArchiveLimits.DEFAULT

    /**
     * List members from the header/directory only, enforcing [ArchiveLimits.maxEntries]
     * in built-in readers. The returned read-only list may be shared with the reader's
     * metadata cache. Do not cast it to a mutable collection or modify it through
     * Java collection methods; use `toMutableList()` for an independently editable list.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveClosedException if the reader is closed.
     * @throws io.github.yuroyami.archivekodec.ArchiveFormatException for malformed metadata or invalid filename encoding.
     * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException for an unsupported container dialect or directory feature.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if the entry count exceeds this reader's policy.
     * @throws ArchiveException if the source or a custom filename decoder reports another archive failure.
     */
    @Throws(ArchiveException::class)
    public fun entries(): List<ArchiveEntry>

    /**
     * Read one entry's uncompressed bytes, using this reader's policy unless [limits] overrides it.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveClosedException if the reader is closed.
     * @throws io.github.yuroyami.archivekodec.ArchiveFormatException for invalid entry metadata, truncated data or a failed integrity check.
     * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException for encryption, an unavailable codec or an unsupported format feature.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed or truncated DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if an output or compression-ratio limit is exceeded.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     * @throws ArchiveException if listing or reading the underlying source reports another archive failure.
     */
    @Throws(ArchiveException::class)
    public fun read(entry: ArchiveEntry, limits: ArchiveLimits = this.limits): ByteArray

    /**
     * Release the underlying source and cached metadata. Built-in readers mark
     * themselves closed before attempting source cleanup; repeated calls are
     * no-ops, including after cleanup throws. Subsequent [entries] and [read]
     * calls throw [io.github.yuroyami.archivekodec.ArchiveClosedException].
     *
     * The default is for resource-free custom readers. Custom implementations
     * retaining a source or handle must override this method to release it.
     *
     * @throws ArchiveException if closing the underlying source reports an archive failure.
     */
    @Throws(ArchiveException::class)
    public fun close() {}
}

/**
 * A view that closes this reader, suitable for standard Kotlin resource cleanup
 * and Java try-with-resources. It shares ownership with this reader.
 *
 * Kept out of Objective-C so [ArchiveReader.close] retains its NSError contract;
 * Kotlin/Native's AutoCloseable.close has a different throws filter.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
public fun ArchiveReader.asAutoCloseable(): AutoCloseable = AutoCloseable { close() }

/**
 * Execute [block] and close this reader on normal or exceptional return. A close
 * failure is suppressed on a primary block failure, as in standard Kotlin [use].
 * A nullable receiver is passed to [block] and needs no cleanup when null.
 *
 * @throws ArchiveException if cleanup fails after a successful block.
 * @throws Throwable if [block] throws; cleanup failures are then suppressed on that failure.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
@Throws(ArchiveException::class)
public inline fun <T : ArchiveReader?, R> T.use(block: (T) -> R): R =
    AutoCloseable { this?.close() }.use { block(this) }
