@file:JvmName("ArchiveReaders")

package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.security.ExtractionBudget
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.jvm.JvmName
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
 *
 * Custom readers return an open [ArchiveFormat] identifier. Register their probe
 * and factory in [FormatRegistry] with a [FormatSpec] using the same identifier;
 * factories receive [ArchiveOpenOptions] and must honor its policy and ownership
 * contract. Supply the registry to [io.github.yuroyami.archivekodec.ArchiveKodec.open].
 *
 * Calling a reader is stable. Implementing one requires opting in to
 * [ExperimentalArchiveImplementation], because members may be added to this interface.
 */
@SubclassOptInRequired(ExperimentalArchiveImplementation::class)
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
     * @throws io.github.yuroyami.archivekodec.security.ArchivePathException for an unsafe name when the policy's `rejectUnsafeNames` is set.
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
     * Read with this reader's [limits]. Also callable without an explicit policy from Java and Swift.
     * @throws ArchiveException for the listing, decoding, integrity and limit failures documented by [read].
     */
    @Throws(ArchiveException::class)
    public fun read(entry: ArchiveEntry): ByteArray = read(entry, limits)

    /**
     * Read one entry and charge its size to [budget], an archive-wide total shared across
     * reads. The decode runs with its output ceiling lowered to the budget's remaining bytes,
     * so it stops as soon as the budget would be passed. A failed read charges nothing.
     * In Kotlin, `reader.read(entry, budget = budget)` keeps this reader's [limits].
     *
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if the entry
     *   would pass the budget, or for any limit failure documented by [read].
     * @throws ArchiveException for the listing, decoding and integrity failures documented by [read].
     */
    @Throws(ArchiveException::class)
    public fun read(entry: ArchiveEntry, limits: ArchiveLimits = this.limits, budget: ExtractionBudget): ByteArray {
        val remaining = budget.remainingBytes
        if (remaining >= limits.maxUncompressedBytes) return read(entry, limits).also { budget.charge(it.size.toLong()) }
        val bytes = try {
            read(entry, limits.copy { maxUncompressedBytes = remaining })
        } catch (e: DecompressionBombException) {
            throw DecompressionBombException(
                "${e.message} (read under an extraction budget with $remaining B of ${budget.maxBytes} B left)",
                e.offset, e.entryName, e,
            )
        }
        budget.charge(bytes.size.toLong())
        return bytes
    }

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
 * Java calls `ArchiveReaders.asAutoCloseable(reader)`.
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
