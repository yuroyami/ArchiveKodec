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
 * require external serialization of operations, including [close].
 */
public interface ArchiveReader {
    /** The container format this reader handles. */
    public val format: ArchiveFormat

    /** Policy for listing and the default for each read. Custom readers default to [ArchiveLimits.DEFAULT]. */
    public val limits: ArchiveLimits get() = ArchiveLimits.DEFAULT

    /** List members from the header/directory only, enforcing [ArchiveLimits.maxEntries] in built-in readers. */
    @Throws(ArchiveException::class)
    public fun entries(): List<ArchiveEntry>

    /** Read one entry's uncompressed bytes, using this reader's policy unless [limits] overrides it. */
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
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
@Throws(ArchiveException::class)
public inline fun <T : ArchiveReader?, R> T.use(block: (T) -> R): R =
    AutoCloseable { this?.close() }.use { block(this) }
